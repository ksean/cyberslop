#!/usr/bin/env bash
# NFR-15: the reviewer's checkout and the read boundary around it.
#
# `--sandbox read-only` restrains writing, not reading: a reviewer launched with it alone reads
# /etc/hostname and reads this repository by absolute path. Everything below exists because some
# earlier, plausible-looking version of it did not hold and a reviewer proved so; review-plan.md on
# the review branch records which.
#
#   checkout  a SHALLOW CLONE cut at BASE - tracked content plus this change's uncommitted files,
#             and no history older than the range, because specs/plan.md records that deeper history
#             still carries real statement figures.
#   ledger    review-plan.md committed onto the review branch THROUGH the PII scan, which a clone's
#             own hooks would not run.
#   run       an OS READ BOUNDARY by WHITELIST with a cleared environment, in a PRIVATE NETWORK
#             NAMESPACE whose one door is a relay (relay.py) admitting the model provider's hosts
#             alone - proved from inside before a reviewer is spent, so the database and the
#             application holding real rows may stay up (NFR-16).
#   close     the end of the round's PROCESSES, which no other step performs: a bwrap boundary is
#             reparented rather than reaped when its launcher dies, so an interrupted round leaves a
#             reviewer running on the network while the checkout it reads is deleted under it.
#
# Usage:
#   review-sandbox.sh checkout <branch> <base-commit> <dest>
#   review-sandbox.sh ledger   <dest> <branch> <file> <message>
#   review-sandbox.sh run      <dest> <lens> <effort> <brief-file> <out-file>
#   review-sandbox.sh close    <dest>
set -euo pipefail

# Ambient git state can point --show-toplevel at a directory that is not this checkout, which would
# make the boundary's absence check protect a path nobody is using.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR

die() { printf 'review-sandbox: %s\n' "$*" >&2; exit 1; }

repo=$(git rev-parse --show-toplevel) || die "not inside a git work tree"
model=${REVIEW_MODEL:-gpt-5.6-sol}
# The names the relay admits, on port 443 only. This host's codex is on ChatGPT auth and talks to
# chatgpt.com; the others are the provider's API and login hosts for a codex configured that way.
hosts=${REVIEW_MODEL_HOSTS:-chatgpt.com,auth.openai.com,api.openai.com}
relay_port=3128
lensdir=""
boundary_pid=""
relay_pid=""
watchdog_pid=""
scratch_index=""
fresh_clone=""

# EXIT alone is not enough (NFR-15d). A round outlasts the tool call that starts it, so the likely
# ending is an interrupt rather than a return - and an interrupt that leaves the boundary behind
# leaves a reviewer on the network reading a checkout about to be deleted under it, along with the
# credential this function is deleting. Kill the boundary FIRST, then take the credential away.
#
# Every step below is guarded with `|| true`, and that is not belt-and-braces: under `set -e` a
# failing step ABORTS THE TRAP, and `wait` on a process you have just killed returns 143. That is
# how the signal path used to end without reaching the `rm` and leave a lens directory - holding a
# copy of the credential - behind on every interrupted round.
# The watchdog is a subshell, and a subshell ended with TERM does not pass it on: `kill $watchdog`
# alone left its `sleep` orphaned, still holding whatever stdout this script was launched into - a
# caller's pipe then never saw EOF, and a round that had ended was reported by nothing until the
# sleep ran out and signalled a pid that was no longer the boundary's. So the watchdog detaches
# from every inherited stream the moment it starts, and ends its own sleep when it is ended.
start_watchdog() {
    ( exec </dev/null >/dev/null 2>&1
      sleeper=""
      trap 'kill "$sleeper" 2>/dev/null; exit 0' TERM INT HUP
      sleep "$1" & sleeper=$!
      wait "$sleeper" || exit 0
      kill -TERM "$2" 2>/dev/null ) &
    watchdog_pid=$!
}

end_watchdog() {
    [ -n "$watchdog_pid" ] || return 0
    kill -TERM "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
    watchdog_pid=""
}

cleanup() {
    if [ -n "$boundary_pid" ]; then
        kill -TERM "$boundary_pid" 2>/dev/null || true
        wait "$boundary_pid" 2>/dev/null || true
        boundary_pid=""
    fi
    end_watchdog
    if [ -n "$relay_pid" ]; then
        kill -TERM "$relay_pid" 2>/dev/null || true
        wait "$relay_pid" 2>/dev/null || true
        relay_pid=""
    fi
    [ -n "$lensdir" ] && rm -rf "$lensdir"
    [ -n "$scratch_index" ] && rm -f "$scratch_index"
    [ -n "$fresh_clone" ] && rm -rf "$fresh_clone"
    return 0
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM HUP

# Is this path the ROOT of a clone of this repository? `git -C` searches parent directories, so
# asking only for the origin makes every subdirectory of a review clone answer yes - and this is the
# test guarding a recursive delete and, in `run`, guarding what gets mounted for a reviewer.
# The marker lives inside .git/, so it is invisible to `git status` and cannot disturb the
# destination comparison - and it is what distinguishes OUR scratch clone from someone's legitimate
# working clone of the same repository, which the origin test alone would have authorized deleting.
marker_of() { printf '%s/.git/review-sandbox-clone' "$1"; }

# Beside the marker, and for the same reason: inside .git/ it is invisible to `git status`, so it
# cannot disturb the destination comparison `checkout` makes.
pidfile_of() { printf '%s/.git/review-sandbox-pids' "$1"; }

# The lens directories the round made, for the one ending no trap can clean up after: SIGKILL runs
# nothing, so `close` is the only thing left that can take away the credential copied into them.
scratchfile_of() { printf '%s/.git/review-sandbox-scratch' "$1"; }

# The tree the reviewer was meant to read, recorded at checkout (and again by `ledger`), so `run`
# can refuse a clone that was edited in the writable interval between the two.
treefile_of() { printf '%s/.git/review-sandbox-tree' "$1"; }

# One object id for the whole working tree of a clone - tracked, modified and untracked alike -
# taken through a scratch index so the clone's own index is left as it is.
tree_of() {
    local d=$1 idx id
    idx=$(mktemp) || return 1
    id=$(GIT_INDEX_FILE=$idx git -C "$d" read-tree HEAD \
         && GIT_INDEX_FILE=$idx git -C "$d" add -A \
         && GIT_INDEX_FILE=$idx git -C "$d" write-tree) || { rm -f "$idx"; return 1; }
    rm -f "$idx"
    printf '%s\n' "$id"
}

is_review_clone() {
    local d=$1 top origin
    [ -d "$d" ] || return 1
    top=$(git -C "$d" rev-parse --show-toplevel 2>/dev/null) || return 1
    [ "$(realpath "$top")" = "$(realpath "$d")" ] || return 1
    origin=$(git -C "$d" remote get-url origin 2>/dev/null) || return 1
    [ "$origin" = "file://$repo" ] || return 1
    [ -f "$(marker_of "$d")" ]
}

require_review_clone() {
    is_review_clone "$1" \
        || die "$1 is not the root of a clone of $repo - refusing (run 'checkout' first)"
}

guard_dest() {
    local d=${1%/}
    [ -n "$d" ] || die "destination is empty"
    case $d in /*) : ;; *) die "destination must be an absolute path: $d" ;; esac
    case $(realpath -m "$d") in
        /|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var)
            die "refusing to use the system directory $d as a review checkout" ;;
        "$(realpath "$HOME")"|"$(realpath "$repo")"|"$(realpath "$repo")"/*)
            die "refusing to use $d as a review checkout" ;;
    esac
    [ -d "$(dirname "$d")" ] || die "the parent of $d does not exist"
    [ ! -e "$d" ] || is_review_clone "$d" \
        || die "$d already exists and is not the root of a clone of $repo; refusing to delete it"
}

# The scanner names the value it matched, so its output must never be echoed: an agent's stderr is
# tool output, and a refusal that prints the account number it caught has published it.
scan_quietly() {
    local log; log=$(mktemp)
    if ! "$repo/scripts/pii-scan.sh" "$@" >"$log" 2>&1; then
        printf 'review-sandbox: the PII scan refused this content. Its report names the matched\n' >&2
        printf '  value, so it is NOT reproduced here; read it yourself at: %s\n' "$log" >&2
        exit 1
    fi
    rm -f "$log"
}

# Every port a local service might be listening on, read out of the project's own configuration.
# Parsing the JDBC URL positionally does not work here - its host is itself a `${DB_HOST:localhost}`
# placeholder, so "the part after the last colon" is not the port. Collect every `${NAME:1234}`
# token (and compose's `${NAME:-1234}` spelling of the same thing) and every `*port*=1234`
# assignment instead, resolve each against the environment, and probe the lot FROM INSIDE the
# boundary, where every one of them must fail to answer: over-probing only ever refuses a review,
# which is the safe direction.
collect_ports() {
    local f=$1 tok inner name def
    [ -f "$f" ] || return 0
    while read -r tok; do
        [ -n "$tok" ] || continue
        inner=${tok#'${'}; inner=${inner%\}}
        name=${inner%%:*}; def=${inner#*:}; def=${def#-}
        # Only a shell identifier can be looked up in the environment. `${!name}` on anything else
        # is a fatal expansion error, and inside the process substitution that calls this it would
        # end the whole port list at that token - silently, leaving nothing to probe.
        case $name in
            *[!A-Za-z0-9_]* | [0-9]* | '') printf '%s\n' "$def" ;;
            *) printf '%s\n' "${!name:-$def}" ;;
        esac
    # "port" must be a whole segment: `im-port-` and `ex-port-` otherwise contribute thread counts
    # and batch sizes, and probing those refuses reviews for no reason.
    done < <(grep -oiE '\$\{([A-Za-z0-9_]*[._-])?port:-?[0-9]+\}' "$f" || true)
    grep -oiE '(^|[._-])port *= *[0-9]+' "$f" 2>/dev/null | sed 's/.*= *//' || true
}

# ---------------------------------------------------------------- checkout

cmd_checkout() {
    local branch=$1 base=$2 dest=$3
    [ -n "$branch" ] && [ -n "$base" ] && [ -n "$dest" ] || die "checkout <branch> <base-commit> <dest>"
    guard_dest "$dest"
    git -C "$repo" rev-parse -q --verify "$base^{commit}" >/dev/null || die "no such base commit: $base"

    # The review branch is re-cut from HEAD every round. Cloning a branch created once means a
    # correction COMMITTED to main since then is in neither the clone nor `git diff`, and every
    # check still passes while the reviewer reads the uncorrected change.
    git -C "$repo" branch -f "$branch" HEAD

    # The whole working tree is staged - into a PRIVATE index, never the maintainer's own. Staging
    # into the real one would leave every untracked file in the tree staged after the round, for the
    # next `git commit` to pick up whether or not it was meant to: one stray text dump would then
    # have only the pre-commit hook between it and history. The scanner and the patch below both
    # read this index, because every git call they make inherits GIT_INDEX_FILE.
    scratch_index=$(mktemp) || die "no scratch index"
    export GIT_INDEX_FILE=$scratch_index
    git -C "$repo" read-tree HEAD
    git -C "$repo" add -A

    # The scanner reads text, so anything git calls binary passes it unseen - and .gitignore's
    # frontend negations un-ignore images there, so a statement arriving as a screenshot would be
    # staged and sent. An extension denylist is the wrong shape for this (it missed AVIF); ask git
    # what it cannot diff as text instead, which is the same judgement the scanner makes.
    local binaries n
    binaries=$(git -C "$repo" diff --cached --numstat | awk '$1=="-" && $2=="-" {print $3}')
    if [ -n "$binaries" ]; then
        n=$(printf '%s\n' "$binaries" | wc -l)
        local list; list=$(mktemp); printf '%s\n' "$binaries" >"$list"
        # The paths are not echoed: a filename can carry an account-holder's name or an account tail.
        die "$n staged file(s) are binary and cannot be read by the PII scanner. Refusing - a
statement arriving as an image or a document passes a text scan unseen (NFR-11). The list is at
$list; it is not reproduced here because a filename can itself carry a real value."
    fi

    local depth
    depth=$(( $(git -C "$repo" rev-list --count "$base..$branch") + 1 ))

    # Build the patch FIRST and scan the patch itself, so what is scanned is exactly the bytes that
    # travel. Scanning the index and then re-reading it leaves a window in which a concurrent `git
    # add` replaces a staged blob under the same pathname.
    local patch=""
    if ! git -C "$repo" diff --cached --quiet; then
        patch=$(mktemp)
        git -C "$repo" diff --cached --binary >"$patch"
        scan_quietly --files "$patch"
    fi
    scan_quietly --staged

    # Back to the real index before anything clones: a clone's own checkout writes an index too,
    # and with GIT_INDEX_FILE still exported it would write it here.
    unset GIT_INDEX_FILE
    rm -f "$scratch_index"; scratch_index=""

    # The pids and lens directories an earlier round recorded live inside the clone's .git, and
    # the clone is about to be rebuilt. They are carried across rather than lost with it: `close`
    # is run once, at the end, and a round that was SIGKILLed before this re-checkout would
    # otherwise have left its credential copies with no record that could find them. And a
    # reviewer still holding the checkout refuses the re-cut outright - deleting the tree under a
    # live reviewer is the failure `close` exists for, and `close` is what must run first.
    local kept_pids="" kept_scratch=""
    if [ -e "$dest" ]; then
        local mine=() holders=() p
        while read -r p; do [ -n "$p" ] && mine+=("$p"); done < <(self_and_ancestors)
        while read -r p; do
            [ -n "$p" ] || continue
            in_our_tree "$p" && continue
            holders+=("$p")
        done < <(processes_holding "$dest")
        [ ${#holders[@]} -eq 0 ] \
            || die "${#holders[@]} process(es) still hold $dest (pids: ${holders[*]}) - run 'close' \
before re-cutting the checkout, or a live reviewer reads a tree deleted under it"
        [ ! -f "$(pidfile_of "$dest")" ] || kept_pids=$(cat "$(pidfile_of "$dest")")
        [ ! -f "$(scratchfile_of "$dest")" ] || kept_scratch=$(cat "$(scratchfile_of "$dest")")
    fi

    # Cloned beside the destination and moved into place only once it is whole, so a clone that
    # fails midway leaves the old checkout - and the records inside it - where they were.
    fresh_clone="$dest.fresh.$$"
    rm -rf "$fresh_clone"
    git clone -q --depth="$depth" --branch "$branch" --no-local "file://$repo" "$fresh_clone"
    printf 'created by review-sandbox.sh for %s\n' "$repo" >"$(marker_of "$fresh_clone")"
    [ -z "$kept_pids" ] || printf '%s\n' "$kept_pids" >"$(pidfile_of "$fresh_clone")"
    [ -z "$kept_scratch" ] || printf '%s\n' "$kept_scratch" >"$(scratchfile_of "$fresh_clone")"
    rm -rf "$dest"
    mv "$fresh_clone" "$dest"
    fresh_clone=""

    if [ -n "$patch" ]; then
        git -C "$dest" apply --binary "$patch" || { rm -f "$patch"; die "the index did not apply to the clone"; }
        rm -f "$patch"
    fi

    git -C "$dest" rev-parse -q --verify "$base^{commit}" >/dev/null \
        || die "the clone does not reach $base: the reviewer cannot read the range"
    git -C "$dest" log --oneline "$base..HEAD" >/dev/null || die "the clone cannot walk $base..HEAD"

    local here there
    here=$(git -C "$repo" status --porcelain -uall | cut -c4- | sort)
    there=$(git -C "$dest" status --porcelain -uall | cut -c4- | sort)
    [ "$here" = "$there" ] || {
        printf 'in the working tree but not the clone:\n%s\n' \
            "$(comm -23 <(printf '%s\n' "$here") <(printf '%s\n' "$there"))" >&2
        die "the clone is not the change"
    }
    tree_of "$dest" >"$(treefile_of "$dest")" || die "could not record the clone's tree"
    printf 'checkout ok: %s (depth %s, reaches %s, %s re-cut at HEAD, whole change carried)\n' \
        "$dest" "$depth" "$base" "$branch"
}

# ---------------------------------------------------------------- ledger

# A clone has no core.hooksPath, so committing the ledger inside it would put verification evidence
# into a git object with no guard in front of it. And `dest` must be proven to be the review clone:
# handed the original checkout, an earlier version of this would have committed on main.
cmd_ledger() {
    local dest=$1 branch=$2 file=$3 message=$4
    [ -n "$branch" ] && [ -n "$file" ] && [ -n "$message" ] || die "ledger <dest> <branch> <file> <message>"
    require_review_clone "$dest"
    [ -r "$file" ] || die "no such ledger file: $file"
    case $(realpath "$file") in "$(realpath "$repo")"/*)
        die "the ledger must not live in $repo: the next checkout would stage it and then fail to \
apply it over the branch's tracked copy" ;;
    esac
    scan_quietly --files "$file"

    # The change under review may have made this path a symlink; `cp` would follow it and overwrite
    # whatever it points at, outside the sandbox, before anyone confirmed the change.
    [ ! -L "$dest/review-plan.md" ] \
        || die "$dest/review-plan.md is a symlink - refusing to write through it"
    rm -f -- "$dest/review-plan.md"
    cp -- "$file" "$dest/review-plan.md"
    git -C "$dest" add review-plan.md
    git -C "$dest" -c user.email="$(git -C "$repo" config user.email)" \
                   -c user.name="$(git -C "$repo" config user.name)" \
                   commit -q -m "$message" -- review-plan.md
    git -C "$dest" push -q origin "HEAD:$branch" \
        || die "ledger push refused - is $branch checked out somewhere? (git worktree list)"
    # The ledger is now part of the tree the reviewer is meant to read.
    tree_of "$dest" >"$(treefile_of "$dest")" || die "could not record the clone's tree"
    printf 'ledger ok: pushed to %s\n' "$branch"
}

# ---------------------------------------------------------------- run

cmd_run() {
    local dest=$1 lens=$2 effort=$3 brief=$4 out=$5
    [ -n "$lens" ] && [ -n "$effort" ] && [ -n "$out" ] || die "run <dest> <lens> <effort> <brief> <out>"
    require_review_clone "$dest"
    [ -r "$brief" ] || die "no such brief: $brief"
    command -v bwrap >/dev/null || die "bwrap is not installed: no read boundary can be raised, so \
no review runs while a real statement is readable on this machine (NFR-15d)"
    command -v python3 >/dev/null || die "python3 is not installed: the relay that is the boundary's \
one door cannot be raised, so no review runs while a real statement is readable on this machine (NFR-15d)"

    # The clone stays writable between `checkout` and here, so re-establish the property that made it
    # safe rather than trusting the earlier run: the tree the reviewer reads must be the one that
    # was scanned and verified at checkout (or committed by `ledger`), byte for byte - a file
    # edited, added or dropped in the interval, ignored or not, was never scanned.
    [ -z "$(git -C "$dest" status --porcelain --ignored=matching | grep '^!!' || true)" ] \
        || die "the review checkout now contains ignored files that were not there at checkout - \
re-run 'checkout' rather than review whatever was put in it (NFR-15d)"
    [ -f "$(treefile_of "$dest")" ] || die "no record of the checkout's tree - re-run 'checkout'"
    [ "$(tree_of "$dest")" = "$(cat "$(treefile_of "$dest")")" ] \
        || die "the review checkout has changed since it was scanned at checkout - re-run 'checkout' \
rather than review what was put in it (NFR-11)"

    # The ports this project's database and application serve on, resolved from its own
    # configuration; every one is checked from inside the boundary below, where none may answer.
    local ports=() p
    while read -r p; do [ -n "$p" ] && ports+=("$p"); done < <(
        collect_ports "$repo/src/main/resources/application.properties"
        collect_ports "$repo/.env.local"
        collect_ports "$repo/docker-compose.yml"
        printf '%s\n' "${DB_PORT:-}" "${DB_HOST_PORT:-}" "${SERVER_PORT:-}" 5432 8080
    )
    local ports_csv
    ports_csv=$(printf '%s\n' "${ports[@]}" | grep -E '^[0-9]+$' | awk '$1>0 && $1<65536' | sort -un | paste -sd,)

    # The lens directory is a `mktemp -d` under /tmp and the relay's socket lives in it: a unix
    # socket path is bounded at 108 bytes, and a path under a deeper scratch directory overran it.
    lensdir=$(mktemp -d) || die "no scratch directory"
    printf '%s\n' "$lensdir" >>"$(scratchfile_of "$dest")"
    mkdir -p "$lensdir/codex-home"

    # The relay runs on the host, outside the boundary, and is the boundary's one door: it is bound
    # in as a unix socket, which crosses the network namespace where no packet can, and admits a
    # tunnel to the model provider's hosts on 443 and nothing else (relay.py). It carries the
    # checkout path in its command line so that `close` counts it as this round's, and its pid is
    # recorded beside the boundary's for the round that ends with SIGKILL and runs no trap.
    local relay_py="$lensdir/relay.py" relay_sock="$lensdir/relay.sock"
    cp "$repo/.claude/skills/adversarial-review/relay.py" "$relay_py" || die "cannot stage relay.py"
    python3 "$relay_py" host "$relay_sock" "$hosts" --review "$dest" &
    relay_pid=$!
    printf '%s\n' "$relay_pid" >>"$(pidfile_of "$dest")"
    local waited=0
    while [ ! -S "$relay_sock" ]; do
        kill -0 "$relay_pid" 2>/dev/null || die "the relay exited before opening its socket"
        [ "$waited" -lt 50 ] || die "the relay did not open its socket in 5s"
        sleep 0.1; waited=$((waited + 1))
    done
    local src_home=${CODEX_HOME:-$HOME/.codex}
    [ -r "$src_home/auth.json" ] || die "no codex credential at $src_home/auth.json: log in with codex first"
    cp "$src_home/auth.json" "$lensdir/codex-home/auth.json"
    printf 'model = "%s"\n' "$model" >"$lensdir/codex-home/config.toml"
    [ -d "$src_home/packages" ] || die "cannot find the codex package tree at $src_home/packages"
    local codex_bin="$HOME/.codex/packages/standalone/current/bin/codex"

    # `env -i` in front of bwrap, not merely --clearenv behind it: bwrap stays PID 1 inside the new
    # PID namespace, keeping the environment it was launched with, and /proc/1/environ hands the
    # reviewer every token the launching shell held. Verified: with --clearenv alone the secret is
    # readable there; with `env -i` the file is empty.
    #
    # --die-with-parent is NFR-15(d)'s lifetime half: without it, killing this script leaves the
    # boundary and the reviewer inside it reparented to init, still on the network and still
    # billing. Measured both ways on this machine before it was added.
    local bwrap_args=(
        --ro-bind /usr /usr --ro-bind /etc /etc
        --symlink usr/bin /bin --symlink usr/lib /lib
        --symlink usr/lib64 /lib64 --symlink usr/sbin /sbin
        # NOT /run/systemd/resolve: the relay resolves names on the host, so the boundary needs no
        # resolver - and systemd-resolved's io.systemd.Resolve is a mode-666 unix socket, which
        # crosses the network namespace and would let a reviewer emit DNS queries carrying
        # whatever it chose to encode in a name. Reviewed and removed; checked below.
        --proc /proc --dev /dev --tmpfs /tmp --tmpfs "$HOME"
        --ro-bind "$dest" "$dest"
        --bind "$lensdir" "$lensdir"
        --bind "$lensdir/codex-home" "$HOME/.codex"
        --ro-bind "$src_home/packages" "$HOME/.codex/packages"
        --clearenv
        --setenv HOME "$HOME" --setenv PATH /usr/bin:/bin
        --setenv CODEX_HOME "$HOME/.codex" --setenv TERM dumb
        --setenv LANG "${LANG:-C.UTF-8}"
        # The reviewer's only route to a model: the bridge on the namespace's own loopback, which
        # forwards to the relay's socket. Nothing else in the namespace answers on any port.
        --setenv HTTPS_PROXY "http://127.0.0.1:$relay_port"
        --setenv HTTP_PROXY "http://127.0.0.1:$relay_port"
        --die-with-parent
        # --unshare-all and NOT --share-net: the network is a boundary, not an exception (NFR-15d).
        --unshare-all --chdir "$dest" --
    )
    boundary() { env -i "$(command -v bwrap)" "${bwrap_args[@]}" "$@"; }

    boundary sh -c "[ ! -e '$repo' ]" \
        || die "boundary NOT up: $repo is still readable from inside it"
    boundary sh -c "[ ! -e '$src_home/sessions' ] && [ ! -e '$src_home/history.jsonl' ]" \
        || die "boundary NOT up: the codex session store is still readable from inside it"
    boundary sh -c '[ ! -e "$HOME/.codex/sessions" ] && [ ! -e "$HOME/.codex/history.jsonl" ]' \
        || die "boundary NOT up: a session store is reachable at the reviewer's CODEX_HOME"
    boundary git -c safe.directory='*' log --oneline -1 >/dev/null \
        || die "boundary is up but git is not usable inside it: the reviewer cannot read the range"
    boundary sh -c '[ ! -e /run/systemd/resolve ] && [ ! -e /run/dbus ] && [ ! -S /var/run/docker.sock ]' \
        || die "boundary NOT closed: a host socket (resolver, bus or docker) is reachable from inside it"
    # The network boundary, proved from inside rather than trusted: none of the project's ports
    # answers there, the relay refuses a name off its list and a loopback literal, and the relay
    # establishes a tunnel to the provider - a boundary tight enough to break the last yields a
    # reviewer that writes transport errors into a non-empty file, which reads like a clean lens.
    boundary python3 "$relay_py" check "$relay_sock" "${hosts%%,*}" "$ports_csv" \
        || die "the network boundary is NOT as required (above): no reviewer is spent behind it (NFR-15d)"

    # Launched in the background and waited on, rather than in the foreground, for two reasons the
    # traps above need: the boundary's pid is what a signal handler kills, and `wait` is what a trap
    # can interrupt. `--die-with-parent` covers this process being killed outright; the pid recorded
    # beside the clone is what `close` uses when it was killed with SIGKILL and no trap ran.
    #
    # `exec`, and NOT the `boundary` function, is what makes that true. A background job is a FORKED
    # SUBSHELL that then runs bwrap as its own child, so bwrap's parent is the subshell rather than
    # this script - and a subshell is not killed when this script is, which leaves --die-with-parent
    # with a living parent and nothing to fire on. Measured: with the function, SIGKILL here left two
    # survivors; with `exec`, none. It is also what makes the recorded pid the boundary's rather than
    # a subshell's.
    local rc=0
    # Inside the boundary a shell starts the bridge and then `exec`s the reviewer over itself, so
    # bwrap's child is the reviewer and the bridge is the namespace's: it ends when the namespace
    # does, and the namespace ends when the reviewer exits.
    ( exec env -i "$(command -v bwrap)" "${bwrap_args[@]}" \
        sh -c 'python3 "$1" bridge "$2" "$3" >/dev/null 2>&1 & shift 3; exec "$@"' sh \
        "$relay_py" "$relay_sock" "$relay_port" \
        "$codex_bin" exec --cd "$dest" --sandbox read-only --ephemeral \
        -m "$model" -c model_reasoning_effort="$effort" - ) <"$brief" >"$out" 2>&1 &
    boundary_pid=$!
    printf '%s\n' "$boundary_pid" >>"$(pidfile_of "$dest")"

    # REVIEW_TIMEOUT=<seconds> bounds the round, two hours unless set; 0 removes the bound. A
    # reviewer that hangs on a transport otherwise holds this call open for as long as nobody
    # notices, and a round's likely ending is already an interrupt. The watchdog ends the boundary,
    # which ends everything in its pid namespace; `wait` then returns 143 and the round is reported
    # void. The watchdog is itself ended with the round, so it is never left to signal a pid long
    # after the process it named has gone. (A pid reused in the instant between the reap and that
    # kill would need the whole pid space to cycle first; it is not guarded against.)
    local timeout=${REVIEW_TIMEOUT:-7200}
    case $timeout in *[!0-9]*) die "REVIEW_TIMEOUT must be a number of seconds: $timeout" ;; esac
    if [ "$timeout" -gt 0 ]; then
        start_watchdog "$timeout" "$boundary_pid"
    fi
    wait "$boundary_pid" || rc=$?
    boundary_pid=""
    end_watchdog
    kill -TERM "$relay_pid" 2>/dev/null || true
    wait "$relay_pid" 2>/dev/null || true
    relay_pid=""
    [ "$rc" = 0 ] || die "$lens exited $rc: this round is void, not clean"
    [ -s "$out" ] || die "$lens produced no output: this round is void, not clean"
    printf 'reviewer ok: %s (%s, %s, %s lines)\n' "$lens" "$model" "$effort" "$(wc -l <"$out")"
}

# ---------------------------------------------------------------- close

# /proc/PID/stat's second field is the executable name in parentheses and may itself contain spaces
# and parentheses, so the fields cannot be counted from the left. Everything after the LAST ')' is
# fixed-width: state, then ppid.
ppid_of() {
    # `read` rather than `$(<file)`: a pid can vanish mid-walk, and this way the failure is a return
    # code instead of a "No such file" on stderr, which for an agent is tool output.
    local s
    # The 2>/dev/null goes FIRST: redirections apply left to right, and a failing input redirection
    # reports before a later one has silenced stderr.
    read -r s 2>/dev/null <"/proc/$1/stat" || return 1
    s=${s##*') '}
    # shellcheck disable=SC2086
    set -- $s
    printf '%s\n' "$2"
}

# This process and everything that launched it carry $dest in their own command lines - the close
# invocation itself, the shell that ran it, the agent session above that. Killing by a command-line
# match without subtracting them kills the caller.
self_and_ancestors() {
    local p=$$
    while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do
        printf '%s\n' "$p"
        p=$(ppid_of "$p") || break
    done
}

# Downwards as well as upwards, and this half is not optional: a forked subshell INHERITS the
# script's argv, so every command substitution below appears in /proc under a command line holding
# $dest with a pid of its own. Subtracting only the ancestors leaves the sweep reporting itself as
# a reviewer that would not die - which is exactly what it did before this walked both ways.
#
# Upwards means the ancestors themselves, and downwards means descendants of THIS process - never
# "anything sharing an ancestor with it". An earlier version walked a candidate's whole ancestry
# against the ancestor list, which made every process under the same terminal or agent session
# "ours": a reviewer launched from that session a round ago, still running, was skipped by `close`
# and reported as nothing still running. `mine` is the caller's, read here by bash's dynamic scope.
in_our_tree() {
    local p=$1 hops=0
    case " ${mine[*]} " in *" $p "*) return 0 ;; esac
    while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null && [ "$hops" -lt 64 ]; do
        p=$(ppid_of "$p") || return 1
        [ "$p" = "$$" ] && return 0
        hops=$((hops + 1))
    done
    return 1
}

# Identity is the review's own checkout path, never the program name. `checkout` made that path for
# this review and nothing else uses it, so it matches the boundary (which binds it), the reviewer
# inside (which is `--cd`'d to it) and any child of either - and cannot match a codex the maintainer
# is running in another terminal, which is not this review's to kill.
processes_holding() {
    local dest=$1 d pid cl
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        # 2>/dev/null before the input redirection, as in ppid_of: a pid that exits between the
        # glob and this read would otherwise print "No such file" into an agent's tool output.
        cl=$(tr '\0' ' ' 2>/dev/null <"$d/cmdline") || continue
        [ -n "$cl" ] || continue
        case $cl in *"$dest"*) printf '%s\n' "$pid" ;; esac
    done
}

cmd_close() {
    local dest=${1%/}
    [ -n "$dest" ] || die "close <dest>"
    case $dest in /*) : ;; *) die "destination must be an absolute path: $dest" ;; esac
    # A short or system path would match half the process table by substring; refuse rather than
    # sweep. The same list `guard_dest` refuses to clone into.
    case $(realpath -m "$dest") in
        /|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var)
            die "refusing to match processes by the system directory $dest" ;;
        "$(realpath "$HOME")"|"$(realpath "$repo")")
            die "refusing to match processes by $dest" ;;
    esac
    # A checkout still present must be provably ours; one already deleted is still swept for, because
    # the processes outliving it are the whole reason this subcommand exists.
    [ ! -e "$dest" ] || require_review_clone "$dest"

    local mine=() p
    while read -r p; do [ -n "$p" ] && mine+=("$p"); done < <(self_and_ancestors)

    local recorded=()
    if [ -f "$(pidfile_of "$dest")" ]; then
        while read -r p; do [ -n "$p" ] && recorded+=("$p"); done <"$(pidfile_of "$dest")"
    fi

    # Two rounds of TERM before KILL: the first ends the boundary, and killing bwrap ends everything
    # in its pid namespace, so the second usually finds nothing. What it is for is the process that
    # escaped the namespace's teardown, and the signal a shell would not deliver to a stopped one.
    local sig ended=() left=() attempt
    for attempt in TERM TERM KILL; do
        sig=$attempt
        left=()
        while read -r p; do
            [ -n "$p" ] || continue
            in_our_tree "$p" && continue
            left+=("$p")
        done < <({ processes_holding "$dest"; printf '%s\n' "${recorded[@]:-}"; } | sort -un)
        [ ${#left[@]} -gt 0 ] || break
        for p in "${left[@]}"; do
            # A recorded pid can have been reused by an unrelated process since the round died.
            # Kill it only where it still names this review's checkout.
            [ -r "/proc/$p/cmdline" ] || continue
            case $(tr '\0' ' ' 2>/dev/null <"/proc/$p/cmdline") in
                *"$dest"*)
                    kill -"$sig" "$p" 2>/dev/null || continue
                    # Counted once however many signals it took, so the number reported is
                    # processes that outlived the review rather than signals sent.
                    case " ${ended[*]:-} " in *" $p "*) : ;; *) ended+=("$p") ;; esac ;;
            esac
        done
        sleep 1
    done

    left=()
    while read -r p; do
        [ -n "$p" ] || continue
        in_our_tree "$p" && continue
        left+=("$p")
    done < <(processes_holding "$dest")
    [ ${#left[@]} -eq 0 ] \
        || die "close FAILED: ${#left[@]} process(es) still hold $dest (pids: ${left[*]}) - the
review is not closed, and deleting the checkout now would leave them running"

    # Only now that nothing is running: a lens directory removed under a live reviewer would take
    # its credential away without stopping it, which is the failure this whole subcommand is for.
    # Each is checked against the shape `run` makes rather than trusted from the file: the record is
    # an argument to `rm -rf` and a stale one must not become a recursive delete of something else.
    local scratched=0 sd
    if [ -f "$(scratchfile_of "$dest")" ]; then
        while read -r sd; do
            [ -n "$sd" ] && [ -d "$sd" ] || continue
            case $sd in /*) : ;; *) continue ;; esac
            [ -d "$sd/codex-home" ] || continue
            rm -rf "$sd" && scratched=$((scratched + 1))
        done <"$(scratchfile_of "$dest")"
        rm -f "$(scratchfile_of "$dest")"
    fi
    [ "$scratched" -eq 0 ] \
        || printf 'close: removed %s lens scratch director(ies) a killed round left behind\n' "$scratched"

    rm -f "$(pidfile_of "$dest")"
    if [ ${#ended[@]} -gt 0 ]; then
        printf 'close ok: ended %s process(es) that outlived the review; nothing holds %s now\n' \
            "${#ended[@]}" "$dest"
    else
        printf 'close ok: nothing was still running for %s\n' "$dest"
    fi
}

# ----------------------------------------------------------------

case "${1:-}" in
    checkout) shift; cmd_checkout "${1:-}" "${2:-}" "${3:-}" ;;
    ledger)   shift; cmd_ledger "${1:-}" "${2:-}" "${3:-}" "${4:-}" ;;
    run)      shift; cmd_run "${1:-}" "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    close)    shift; cmd_close "${1:-}" ;;
    *)        die "usage: review-sandbox.sh checkout <branch> <base-commit> <dest>
                  review-sandbox.sh ledger   <dest> <branch> <file> <message>
                  review-sandbox.sh run      <dest> <lens> <effort> <brief-file> <out-file>
                  review-sandbox.sh close    <dest>" ;;
esac
