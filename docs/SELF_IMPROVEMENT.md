# Self-improvement

Tau improves itself in a daily loop: Claude Code brings it up to date with
Covia when that is worth it, makes small improvements chosen with Tau's own
improvement agent, relaunches Tau into the new build, checks it, and reports
to the owner. This is the loop's design; the procedure for one run is the
`improvement-loop` Claude Code skill (`.claude/skills/improvement-loop/`).

- **Rapid and frugal.** One run a day, measured by elapsed time and tokens.
  A run works in chunks and starts another only while it is under an hour
  and going well, with two hours as a soft ceiling. Small changes go straight in; a run may conclude there is nothing
  worth doing.
- **Claude Code drives; Tau chooses.** The improvement agent says what hurts
  most, with evidence from the backlog; Claude Code makes the change in Tau's
  own checkout.
- **The owner decides design.** A substantive design change, or one Covia
  should make, is a GitHub issue that waits for the owner's approval.
- **Machines check, not opinions.** Health is a scripted smoke check, and a
  backlog item closes when its failing action works again.
- **Every relaunch can be undone.** The last good build is kept, and a failed
  check relaunches it.

## Who does what

| Who | Part |
|---|---|
| Claude Code, from a scheduled task on the owner's computer | runs the loop: upgrades, implements, tests, relaunches, merges, reports |
| Improver, the improvement agent | keeps the backlog honest, picks what hurts most, tries things, reviews proposals |
| Tau's assistant and gods | report misses and ideas to the backlog as they meet them; Odin relaunches Tau |
| The owner | approves proposals and upstream requests, pushes, releases |

Improver is an agent in the owner's namespace, forked from Tau's main agent
so it has the same tools and skills, with caps unrestricted and the
`self-improvement` skill pinned ([SKILLS.md](SKILLS.md)). It has its own
sessions, and may create child agents to test behaviour. Its definition is
the skill's `agent.json`; each run applies it.

## A run

1. **Preflight.** Fetch; bring the loop branch up to date with `develop`;
   run the smoke check.
2. **Covia.** Claude Code judges whether Covia's `develop` has moved enough
   to upgrade: main code Tau uses, a new version, or a week since the last.
   A run that upgrades makes no other improvements. A change to Covia's store
   formats stops the upgrade for the owner, since a migrated store cannot go
   back without a backup.
3. **Improve.** Open issues first (the owner's are approved work; the loop's
   own proposals wait for `approved`), then the backlog, then Claude Code's
   own ideas, which it agrees with Improver before building. One substantial
   item and any trivial ones per chunk, each a commit one revert undoes.
4. **Relaunch and check.** Build, ask Odin to relaunch into it, run the smoke
   check. On failure, relaunch the last good build and stop.
5. **Merge and report.** Fast-forward `develop` to the loop branch; report
   what needs the owner, then what changed.

## What needs the owner

- A substantive design change: a new interface, skill family, operation or
  agent, or a change to authority, startup or the loop itself. Opened as an
  issue labelled `proposal`; built once labelled `approved`.
- A change to Covia's design: labelled `upstream` and `proposal`. The loop
  never edits Covia. A Covia bug is different: the loop reports it straight
  away as an issue on `covia-ai/covia`, with the smallest reproduction and no
  exploit detail.
- Pushing and releasing.

The loop and the agents also leave alone, without the owner's word: any
agent's caps and the startup pass that repairs them, the security skill and
secret handling, what memory holds, which provider receives the owner's data,
and history ([GODS.md](GODS.md), [LAUNCH.md](LAUNCH.md#the-startup-pass)).

## Where things are

| What | Where |
|---|---|
| The loop's checkout | `~/.brightside/code/brightside`, Tau's own Claude Code project: on the owner's machine a worktree on branch `improve` |
| The backlog | `w/skill-feedback`, written by the skill-feedback operation: misses under their category, ideas under `improvement`; the loop marks entries `resolved` |
| Proposals and approved work | GitHub issues on `covia-ai/brightside` |
| The last good build | `~/.brightside/code/last-good.jar` (`file://code/last-good.jar` to Odin) |
| The Covia commit last installed | `~/.brightside/code/.covia-installed`, built from `../covia`'s committed `develop`, never its working tree |
| The schedule | a local scheduled task, `brightside-improve`, daily at 21:00 |

Relaunching through Odin means Tau starts its successor itself, so the
running app does not depend on the Claude session that asked. Odin keeps the
owner's standing decision that the loop's relaunches are approved.

Reports and issues quote the backlog and logs as they are. An owner whose
data is not test data says so in the repository's `CLAUDE.local.md`, and the
loop redacts.

## Known limits

- The schedule runs only while the Claude desktop app is open; a missed run
  happens at its next start.
- There is no store backup: a store-format change waits for the owner, and
  the last good build cannot read a store a newer Covia has migrated.
- Covia snapshot jars in `~/.m2` are shared with other work on the machine,
  so the loop reinstalls its commit when a build breaks in untouched code.
