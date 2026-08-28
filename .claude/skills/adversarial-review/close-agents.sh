#!/usr/bin/env bash
#
# Ends every agent shell a review started, and proves that it did.
#
# A review outlives the tool call that starts it, so a reviewer is usually backgrounded and
# something waits on it. Both are processes, and both have to be ended when the round is over —
# `review-sandbox.sh close` ends what holds the *checkout*, and knows nothing about a waiter.
#
# Agents are addressed by **recorded pid**, never by a pattern match on a command line. That is not
# fastidiousness; it is the defect this script exists for. A waiter written as
#
#     until ! pgrep -f "codex exec" > /dev/null; do sleep 10; done
#
# has the string `codex exec` in its own argv, so `pgrep` matches the waiter itself: it is its own
# reason to keep waiting and can never exit. Measured on this machine, five of them accumulated over
# rounds and were still running a day later, each holding a `sleep`. Matching on the process *name*
# instead (`pgrep -x codex`) fixes the self-match and introduces a worse bug, because it also matches
# the interactive codex sessions the owner is running in other terminals.
#
# Usage:
#   close-agents.sh record <dir> <pid>...   remember agents belonging to this review
#   close-agents.sh check  <dir>            list which are still alive; 0 if none, 1 if any
#   close-agents.sh close  <dir>            end them all, then prove none is left
set -uo pipefail

command=${1:-}
directory=${2:-}

if [ -z "$command" ] || [ -z "$directory" ]; then
    echo "usage: close-agents.sh {record|check|close} <review-scratch-dir> [pid...]" >&2
    exit 64
fi

ledger="$directory/agents.pid"

# Never signal this process, its ancestors, or init: a review's own shell is not one of its agents,
# and killing an ancestor takes down the session that is trying to clean up.
forbidden() {
    local candidate=$1 walk=$$
    [ "$candidate" -le 1 ] && return 0
    while [ "$walk" -gt 1 ]; do
        [ "$candidate" = "$walk" ] && return 0
        walk=$(ps -o ppid= -p "$walk" 2>/dev/null | tr -d ' ')
        [ -z "$walk" ] && break
    done
    return 1
}

alive() { kill -0 "$1" 2>/dev/null; }

case "$command" in
record)
    shift 2
    mkdir -p "$directory"
    for pid in "$@"; do
        if forbidden "$pid"; then
            echo "close-agents: refusing to record $pid (self or ancestor)" >&2
            exit 1
        fi
        echo "$pid" >>"$ledger"
    done
    echo "close-agents: recorded $* in $ledger"
    ;;

check)
    # Exits 0 when nothing is running and 1 when something is, deliberately, so a caller can gate on
    # it. Said here because an exit status that falls out of the last test in a branch is an accident
    # waiting to be relied on.
    [ -f "$ledger" ] || { echo "close-agents: nothing recorded"; exit 0; }
    living=0
    while read -r pid; do
        [ -z "$pid" ] && continue
        if alive "$pid"; then
            living=$((living + 1))
            echo "close-agents: still running $pid $(ps -o args= -p "$pid" 2>/dev/null | cut -c1-70)"
        fi
    done <"$ledger"
    if [ "$living" -eq 0 ]; then
        echo "close-agents: nothing was still running"
        exit 0
    fi
    exit 1
    ;;

close)
    [ -f "$ledger" ] || { echo "close-agents: nothing recorded, nothing to close"; exit 0; }

    # Children first. Killing a waiter shell on its own leaves its `sleep` reparented to init, still
    # holding whatever stream the shell was launched into.
    while read -r pid; do
        [ -z "$pid" ] && continue
        forbidden "$pid" && continue
        pkill -TERM -P "$pid" 2>/dev/null
        kill -TERM "$pid" 2>/dev/null && echo "close-agents: TERM $pid"
    done <"$ledger"

    # Give them a moment, then insist. Under `set -e` a failing signal would abort the loop and
    # leave the rest running, which is why this script does not use it.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        remaining=0
        while read -r pid; do
            [ -z "$pid" ] && continue
            alive "$pid" && remaining=$((remaining + 1))
        done <"$ledger"
        [ "$remaining" -eq 0 ] && break
        sleep 0.5
    done

    stubborn=""
    while read -r pid; do
        [ -z "$pid" ] && continue
        forbidden "$pid" && continue
        if alive "$pid"; then
            pkill -KILL -P "$pid" 2>/dev/null
            kill -KILL "$pid" 2>/dev/null
            stubborn="$stubborn $pid"
        fi
    done <"$ledger"
    [ -n "$stubborn" ] && echo "close-agents: KILL required for$stubborn"

    sleep 0.5
    left=""
    while read -r pid; do
        [ -z "$pid" ] && continue
        alive "$pid" && left="$left $pid"
    done <"$ledger"

    if [ -n "$left" ]; then
        echo "close-agents: FAILED — still running$left" >&2
        exit 1
    fi

    rm -f "$ledger"
    echo "close-agents: closed; nothing was still running"
    ;;

*)
    echo "close-agents: unknown command $command" >&2
    exit 64
    ;;
esac
