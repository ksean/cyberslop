---
name: adversarial-review
description: Put a change to independent read-only reviewers the way NFR-15 requires — a shallow clone inside an OS read boundary, one codex reviewer on gpt-5.6-sol at high effort by default (more reviewers, another model or another effort only where the directing prompt names them), every finding verified and dispositioned, up to three rounds. Use ONLY where the user has explicitly directed a review of the change in hand: asked to review it adversarially, to run NFR-15 over it, or to implement something "with adversarial review". Never on your own judgement that a change is large, complex or risky enough to warrant one, and never on a direction given for an earlier change — ask the user and wait for approval instead.
---

# Adversarial review (NFR-15)

The rule is [`specs/nonfunctional.md`](../../../specs/nonfunctional.md)'s **NFR-15** and states
itself in full; the reasoning is
[`docs/DEVELOPMENT.md`](../../../docs/DEVELOPMENT.md#review-before-you-commit-nfr15). This file is
the runnable form.

**`review-sandbox.sh`, beside this file, owns the checkout and the boundary.** Do not hand-roll
either. It exists because the first two attempts at this by hand were both wrong in ways nobody
noticed until a reviewer said so: a `--sandbox read-only` process reads the whole filesystem, and
the mask that fixed that broke `git` for the reviewer, costing a whole round.

## Do not run this unless you were told to

**A review runs only where the user directed one for the change in hand** (NFR-15). This file is what
you follow *after* that direction — never what you reach for to decide whether to seek it.

- **Directed** — "review this adversarially", "run NFR-15 over it", "implement X with adversarial
  review", said about *this* change. Go on to step 1.
- **Not directed, and you judge one warranted** — put it to the user: what it would cover, the
  default reviewer or the departure from it you would recommend, and what it will spend. Then wait. Silence is not approval,
  agreeing to the work is not agreeing to review it, and starting a round to find out whether it was
  wanted has already spent what it was asking about. Do not defer the question by reviewing anyway,
  and do not settle it by skipping quietly — raising it is the whole of your part.
- **Not directed, and you do not judge one warranted** — the change finishes without one and its
  commit message says nothing about review. That is the ordinary case here, not a lapse.

A direction covers **the change it was given for**. It does not carry to the next one, so a review
run last week authorizes nothing today.

## Before anything: say what the whole change will be

Asked to implement something *with adversarial review*, lay out every step end to end before
starting — the area and NFRs read, what is amended, the `tasks.md` entries, the spec-only commit and
the pause, the implementation and its tests, then these review rounds, then the validation gate and
the implementation commit. Beginning at "spawn the reviewers" hides most of the work and both points
the user is asked to approve.

## 1. Green first

A reviewer is for what green cannot see, so be green before spending a round on it. Green is
[`validate-project`](../validate-project/SKILL.md)'s gate, not a list restated here:

```bash
scripts/validate.sh                        # the whole gate; VALIDATE_SKIP_E2E=1 where no journey moved
```

## 2. Make the review checkout

```bash
S=$(mktemp -d); RC="$S/rc"; TOPIC=<topic>
BASE=<commit before the change's first commit>     # git log --oneline to find it; NOT HEAD~1
.claude/skills/adversarial-review/review-sandbox.sh checkout "review/$TOPIC" "$BASE" "$RC"
.claude/skills/adversarial-review/review-sandbox.sh \
    ledger "$RC" "review/$TOPIC" "$S/review-plan.md" "Rounds so far"   # from round two on
```

**The order is checkout, then ledger, then run.** `checkout` re-cuts the branch at `HEAD`, which
necessarily drops the previous round's ledger commit; `ledger` puts the accumulated file back, into
the clone the reviewers are about to read. The durable record is the **file** in `$S`, which you
append to each round — not the branch's commit history, which is rebuilt every time.

Depth is **derived from `$BASE`**, never passed: too shallow and the reviewer cannot read the range,
too deep and the clone imports the older commits `specs/plan.md` records as still carrying real
values. `checkout` **re-cuts the review branch at `HEAD` every time**, so a correction you
*committed* since the last round is in the next one — a branch created once and re-cloned leaves a
committed fix invisible to every check. It runs `scripts/pii-scan.sh --staged` before anything is
copied and refuses files the scanner cannot read as text, clones tracked content only, applies the
**index** on top (not the working tree, which may have moved since the scan), proves `$BASE..HEAD` is
walkable *in the clone*, and verifies the file list **at the destination**.

It carries the **whole dirty tree**, not a chosen subset — so run a review from a tree holding only
the change under review, and see `docs/SECURITY.md` for what that discloses.

## 3. Launch the reviewer

**The default is one reviewer: `codex` on `gpt-5.6-sol` at `high`** (NFR-15;
[`DEVELOPMENT.md`](../../../docs/DEVELOPMENT.md#one-reviewer-gpt-56-sol-high--unless-the-direction-says-otherwise)).
The number of reviewers, the model and the effort change **only where the prompt that directed the
review names them** — never because of what the change touches. Where you think the default too
light, say so when the review is directed and abide by the answer.

```bash
.claude/skills/adversarial-review/review-sandbox.sh \
    run "$RC" review high "$S/brief.txt" "$S/findings.txt"
```

`run` blocks until the reviewer returns, and it takes its own boundary down on an interrupt — but
only for a signal a trap can catch, which is why step 6 still checks rather than assumes.
A round is bounded at two hours — `REVIEW_TIMEOUT=<seconds>` changes that, `0` removes it — past
which the boundary is ended and the round reported void, which is what a reviewer hung on a
transport would otherwise never say.

**Where the direction names several reviewers**, launch them blind to each other, one lens each
(the split is in "The lenses" below), and collect the pids:

```bash
pids=()
trap 'kill -TERM "${pids[@]}" 2>/dev/null' INT TERM
for LENS in specification implementation absence; do      # the lenses the direction asked for
  .claude/skills/adversarial-review/review-sandbox.sh \
      run "$RC" "$LENS" <effort> "$S/brief-$LENS.txt" "$S/findings-$LENS.txt" & pids+=($!)
done
ok=1
for p in "${pids[@]}"; do
  wait "$p" || { ok=0; kill -TERM "${pids[@]}" 2>/dev/null; }   # one lens down voids the round
done
trap - INT TERM
[ "$ok" = 1 ] || echo "a reviewer failed - this round is VOID, not clean"
```

A bare `wait` returns 0 even when a child failed, which is how a round with no reviewers in it reads
as a round with no findings. Collect the pids and wait on each.

**The two `kill`s are NFR-15(d)'s lifetime half, and neither is optional.** One lens failing voids
the round, so the others are spending on a round that no longer counts; and an interrupt at the
`wait` would otherwise leave them all running.

`run` refuses to launch unless its self-checks pass: this repository is absent from inside the
boundary, the codex session store is absent both at its real path and at the reviewer's `CODEX_HOME`,
`git log` works, and the **network boundary holds**. The reviewer runs in a private network
namespace — `--unshare-all` with no `--share-net` — so this host's loopback, where the database and
the application serve really-imported rows, does not exist for it; its one route out is
`relay.py`, an HTTP CONNECT relay on a unix socket the script binds in, which admits the model
provider's hosts on 443 (`REVIEW_MODEL_HOSTS`, default `chatgpt.com,auth.openai.com,api.openai.com`)
and refuses every other name, address and port. Before a reviewer is spent, `relay.py check` runs
*inside* the boundary and proves three things: none of the project's configured ports answers
there, a tunnel to a name off the list and to a loopback literal is refused, and a tunnel to the
provider is established. **The compose stack stays up throughout** (NFR-16): nothing needs
stopping for a review, and the script no longer asks. Each reviewer gets its own scratch directory
and its own minimal `CODEX_HOME` under `--clearenv`, which is what makes a multi-reviewer first pass
actually blind.

**Read every line the script prints.** `reviewer ok:` per reviewer is the only thing that makes the
round a round; anything else means it is void, not clean. Never the `Agent` tool for a reviewer — a
subagent inherits this session's tools and this session's root.

A reviewer cannot run this project's own checks (`scripts/spec-citations.sh` needs a writable temp
dir). Run those yourself and put the output in the brief.

## The brief

Every brief carries the same head. The fourth block is the one that gets left out, and without it a
reviewer reports a deleted `tasks.md` entry as lost work every single time.

**"Verbatim" stops where NFR-11 does.** A request is one of the places a real value arrives — a
pasted row, a quoted figure, a named payee. Replace any of that with invented values or with the
finding it stands for *before* writing the brief, and say in the brief that you did.

```
You are reviewing a change to finmgr.net. You had no part in writing it.

THE REQUEST, in the words it was made in:
<paste the user's request — not a summary; with any real values replaced by invented ones>

THE CHANGE: commits <BASE>..HEAD, plus anything uncommitted (already applied here).
  git log -p --stat <BASE>..HEAD
  git status --porcelain -uall
Review the whole range. Do not review the last commit alone.

THE REQUIREMENTS the change is held to: <IDs it answers, plus the non-functional ones it must
satisfy whether or not it answers them — a migration is held to NFR-04, everything to NFR-09/10>

THE RULES this project holds every change to. Read these before the diff:
  specs/constitution.md       the principles
  specs/README.md             how the specification may be changed, and its checklist
  specs/nonfunctional.md      NFR-01..NFR-16 — code standards, PII, corpus, red run, review, tooling
  AGENTS.md                   how work is done in this repository
  docs/DEVELOPMENT.md         conventions and the established pattern
Under these rules NONE of the following is a defect, and reporting one wastes the round:
  - a deleted specs/tasks.md entry (entries are deleted when done, never marked done)
  - a requirement carrying no date, no history and no "amended by" note
  - a story or requirement carrying no priority, phase or MVP label
  - an existing requirement rewritten rather than a new one appended
  - a change committed straight to main with no branch and no pull request

YOUR LENS: <the default single reviewer: all three lens paragraphs below.
           One of several: that reviewer's paragraph only>

Do not open any file under docs/samples/, and do not open any file shaped like a
statement: a PDF, a spreadsheet, an OFX/QFX/QIF export, a screenshot. If you believe
one is relevant, name its path and say why.

Report only what you can point at: a file:line, what is wrong, and what goes wrong
because of it. Do not propose edits and do not write to any file. Say so plainly if
you find nothing in your lens — a manufactured finding costs a verification round.
Rank your findings most serious first.
```

### The lenses

The single default reviewer is briefed on all three. Where the direction names three reviewers, one
each; where it names two, **absence** stands alone and the other takes specification and
implementation — absence is the lens that finds what the other two structurally cannot, and is the
one a reviewer handed everything alongside others anchors away from.

**specification** — Does the amended requirement say what the request asked for, and is it in the
area that owns the behaviour? Walk the request's numbered asks one at a time and say for each
whether the committed text covers it, weakens it, or drops it. Does the change satisfy
`specs/README.md`'s checklist — amended rather than appended, no dates, no priorities, honestly
classified, every citation of a moved identifier updated? Is `specs/tasks.md` kept the way that file
says it is kept, its checkpoint counts re-measured rather than trusted? Does any rule appear in more
than one place where a citation would do (NFR-10j)?

**implementation** — Correctness, error paths, edge cases. Layering and the direction dependencies
flow. Security and per-user scoping on every query. The Flyway migration, including whether an
already-applied one was edited in place (NFR-04). NFR-10's code standards. Accessibility. Money as
`BigDecimal` under one rounding mode (NFR-05). Where the change *is* prose and commands, run every
command and check it does what the text claims.

**absence** — What is *not* there. The regression test for the defect this fixes. The migration for
the column that was added. The contract in `docs/API.md`. The behaviour the code has that no
requirement states. The corpus reading that should have moved and did not (NFR-12). Every route PII
could still take off this machine (NFR-11) — the brief, the transcript, anything persisted, the
clone, `git log`, the review branch. A loop state that neither closes nor escalates. You are the
only reviewer who can find these: a diff holds only what somebody wrote.

## 4. Findings: verify, then dispose

Collect them into `$S/review-plan.md` — **not into the repository's own working tree**, where the
next `checkout` would stage it and then fail to apply it over the branch's already-tracked copy — and
put it on the branch with the `ledger` subcommand, because the next round's checkout is cut from that
branch and an uncommitted ledger is one the next round does not carry:

```bash
.claude/skills/adversarial-review/review-sandbox.sh \
    ledger "$RC" "review/$TOPIC" "$S/review-plan.md" "Round N ledger: …"
```

`ledger` PII-scans the file before committing it. A clone has no `core.hooksPath`, so a plain
`git commit` there would put verification evidence into a git object with no guard in front of it.

**Nothing reaches `main` from there unverified** — a reviewer states defects it has not run, and a
plausible finding reads exactly like a real one until somebody runs it.

- **Fixed** — the correction lands on `main`. Touching the import, it goes through NFR-13's red run:
  the test is authored whole against the unfixed tree, run, and *seen* to fail first.
- **Deferred** — into `specs/plan.md`'s known follow-ups with the reason, **and only where the user
  chose that**. Deferring a verified finding yourself would close the loop on every finding, since
  any of them can be written down instead of answered.
- **Rejected** — with the evidence in `review-plan.md`: a command's output, or the `file:line`
  showing the reviewer was reading something else. "Checked, it is fine" is not a rejection, and the
  next round raises the same finding — the written evidence is what closes it the second time.

Evidence obeys NFR-11: output from a run over real data is masked before it is written down, and
`review-plan.md` is a git object.

Re-run what each correction touched as you make it. The whole suite at the end says something broke;
it does not say which correction broke it.

## 5. Rounds

Re-run `checkout` and the round is rebuilt from the corrected tree — there is no patching-up of the
previous one. Reviewers get the **complete updated change**, not the corrections.

Three rounds is the bound, and two things bound it honestly:

- **A round whose reviewers could not read what NFR-15(a) requires is void and is not counted.**
  That is not pedantry — it is what happened when a boundary broke `git` and two reviewers spent a
  round on the working tree alone. Void rounds have their own bound: **two in a row end the
  review**, and it goes to the user as unreviewed with what failed — a mechanism that keeps
  failing is the user's to spend more on, not the author's to retry until it works.
- **A correction made in the third round is never left unread**: the bound has by then spent the
  round that would have checked it, so the change goes to the user naming what changed after the
  last review.

A round returning nothing that survives verification closes the loop. A verified finding that is
neither fixed nor rejected goes to the user as a decision with the options that would settle it —
options to pick from, not an open question.

## 6. Close it out

**Closing is three things, and the first one is not the branch** (NFR-15d):

```bash
.claude/skills/adversarial-review/review-sandbox.sh close "$RC"   # BEFORE the deletions
git branch -D "review/$TOPIC"; rm -rf "$S"
```

`close` ends anything still holding the review checkout and says what it found — `nothing was still
running` is the answer to want, and is the point of running it even when you believe the round ended
cleanly. It must come **first**: `rm -rf "$S"` under a live reviewer deletes the checkout it is
reading and the credential copied beside it while leaving it running, on the network, still
spending. Read its line like `reviewer ok:` — a `close FAILED` means the review is not closed and
nothing should be deleted yet.

It identifies processes by **this review's checkout path**, which `checkout` made for this review
alone, never by a match on `codex` — so a codex you are running yourself in another terminal is never
a candidate. And it is the only thing that can clean up after a `kill -9`, which runs no trap: `run`
records the lens directories it made, and `close` removes them, because each holds a copy of the
credential.

The branch is scratch and is never merged: everything on it has either landed on `main` or been
rejected in writing. Then say it in the commit message — the rounds run, the findings confirmed, the
findings deferred and by whose decision, the findings rejected and on what evidence. That sentence is
NFR-15's observable check, and nothing mechanical enforces it: no hook and no CI job can tell a
reviewed change from one whose message says it was reviewed.

## Gotchas

- **`--sandbox read-only` is not a read boundary.** It stops writes. Reads reach the whole
  filesystem. The script's `bwrap` layer is what stops that, and it self-checks rather than trusts.
- **A filesystem boundary is not the whole boundary.** A reviewer sharing the host's network
  namespace reaches everything on loopback — including a database holding really-imported rows —
  which is why the namespace is private and the relay is the one door; and without `--clearenv`
  the launching shell's tokens and session identifiers come along, readable through `/proc` even
  if unset in the child.
- **A unix socket crosses the namespace — that is how the relay works, and how anything else
  bound in would too.** `/run/systemd/resolve` used to be bound in for DNS; its `io.systemd.Resolve`
  is mode 666 and answers `ResolveHostname`, a second door carrying whatever a reviewer encoded in
  a name. Nothing under `/run` is bound now, and `run` checks from inside.
- **The relay's socket path is bounded at 108 bytes.** A unix socket under a deep scratch path
  fails to bind with `AF_UNIX path too long`; it lives in the lens directory, a `mktemp -d` under
  `/tmp`. And `api.openai.com` does not resolve on this host at all — the relay answers 502 for
  it — while `chatgpt.com`, which this host's ChatGPT-authenticated codex talks to, does; a codex
  on API-key auth would need the first, which is why the list is configurable.
- **A `.git` is not proof of ownership, and neither is an origin.** An earlier guard deleted any
  destination that merely contained a `.git`, and a stray `/tmp/.git` on this machine made that
  `/tmp`. Asking `git -C "$dir"` for the origin is no better on its own: git searches *parent*
  directories, so every subdirectory of a review clone answered correctly and would have been
  deleted. The check is that the path is the clone's **root** and its origin is this repository, and
  `ledger` and `run` apply it too — handed the original checkout, `ledger` would have committed on
  `main`.
- **A port check must resolve the project's own placeholders.** `${DB_PORT:5432}` is not a number,
  and `DB_HOST_PORT` moves the published port; a probe of a hard-coded 5432 proves nothing about
  the port the database is really on. And "port" is a substring of "import", so a loose match
  probes thread counts. The in-boundary check probes every resolved one.
- **The ledger never lives in the repository's working tree.** `checkout` carries everything
  uncommitted, so a `review-plan.md` left at the root is applied over the branch's tracked copy and
  the next round fails with `already exists in working directory`.
- **`checkout` stages into a private index, never yours.** What it carries is the whole working
  tree, scanned and diffed through a scratch `GIT_INDEX_FILE`; your own staging is untouched
  before and after, and nothing is left staged for the next commit to sweep up.
- **Records survive a re-checkout.** The pids and lens directories a round wrote into the clone's
  `.git` are carried into the rebuilt clone, so one `close` at the end still finds what a round
  SIGKILLed before the next `checkout` left behind.
- **A worktree cannot be the review checkout.** Its `.git` points back into the checkout the
  boundary masks, so `git` dies inside — hence the shallow clone.
- **`git diff` is not the change.** It omits staged and untracked files; the script uses
  `git diff --cached --binary` — the index, which is what was scanned — and verifies at the
  destination. A binary file is refused rather than sent: the scanner reads text, so git's own
  "cannot diff as text" is the honest test, and an extension denylist missed AVIF.
- **A reviewer that failed still writes a non-empty file** — transport errors look like output.
  Trust `reviewer ok:`, not file size.
- **The ledger push is refused if `review/<topic>` is checked out anywhere** (`refusing to update
  checked out branch`). Nothing should have it checked out under this design; a leftover
  `git worktree` from an older one will, so `git worktree list` is the first thing to look at.
- **`<BASE>` is the commit *before* the change's first commit** — usually the spec commit's parent,
  not `HEAD~1`.
- **A `bwrap` boundary is not reaped when its launcher dies** — it is reparented to init and carries
  on, reviewer inside, on the network, billing. `--die-with-parent` is what closes that, and the
  round's likely ending is an interrupt or a timeout rather than a return, because a round outlasts
  the tool call that starts it. Hence `close`, and hence step 6's order.
- **A background job is a forked subshell, and that subshell defeats `--die-with-parent`.**
  `boundary … &` puts a shell between this script and `bwrap`, so bwrap's parent survives the
  script's death and the flag never fires — measured: two survivors. `run` therefore backgrounds
  `( exec env -i bwrap … )`, which leaves bwrap as the script's own child.
- **A subshell ended with TERM does not end its child.** The round's timeout watchdog was
  `( sleep N; kill … ) &`, and killing it left the `sleep` orphaned — holding the pipe the script was
  launched into, so the caller never saw the round end, and two hours later signalling a pid that was
  no longer the boundary's. `start_watchdog` detaches from every inherited stream and traps TERM to
  kill its own sleep; `end_watchdog` is what both the normal path and `cleanup` call.
- **Under `set -e` a failing step aborts a trap.** `wait` on a process you have just killed returns
  143, so a cleanup that chains on `&&` never reaches its `rm` — which is how interrupted rounds
  used to leave a lens directory, and the credential copy in it, behind.
- **Reviewer confidence carries no information** about whether the defect exists. Run it.
