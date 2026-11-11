---
name: improvement-loop
description: Run one pass of Tau's daily self-improvement loop - Covia upgrade when worth it, one or two improvements chosen with Tau's improvement agent, relaunch, machine checks, merge into develop, short report. Use when the scheduled loop fires or the owner asks for a loop run.
---

# Tau's improvement loop: one run

The design and its reasons are in `docs/SELF_IMPROVEMENT.md`; this is the
procedure. Be quick and frugal: elapsed time and tokens are the measure, and
the owner wants tokens left at the end of the week. Read diffs and logs, not
whole files; no wide exploration; a subagent only for a read across many
files. Work in chunks: an upgrade, or one substantial item with its trivial
companions, each tested, committed and relaunched before the next. After a
chunk, start another only if the run is still under an hour and the last
chunk went smoothly; otherwise wrap up. Two hours is a soft ceiling: past
it, finish what is in hand, leave the rest to the backlog and report.

Names used below (Git Bash):

```bash
S=<this skill's directory>/scripts      # mcp.sh covia.sh relaunch.sh smoke.sh
W=~/.brightside/code/brightside          # the loop's worktree, branch improve
M=$(dirname "$(git -C "$W" rev-parse --path-format=absolute --git-common-dir)")  # main checkout, on develop
```

Work in `$W`. Follow the repository's AGENTS.md there. Never edit `../covia`,
never push, never release.

## 1. Preflight

- `git -C "$M" fetch -q`. `$M` must be on `develop`.
- `git -C "$W" status --short` must be empty. Leftovers are an earlier run's
  unfinished work: finish it if it is small and clear, otherwise
  `git -C "$W" stash` it and say so in the report.
- `git -C "$W" rebase develop`. A conflict: abort, report, stop.
- `$S/smoke.sh`. If Tau is not running (exit 2 from `mcp.sh`), start it
  from a copy of `~/.brightside/code/last-good.jar` with
  `javaw -jar <copy> --unlock --takeover` (PowerShell `Start-Process`), then
  `$S/relaunch.sh file://code/last-good.jar` so that Tau, not this session,
  owns the process. Any other failed check: report it; fixing it is this
  run's work.

## 2. The improvement agent

`$S/mcp.sh agent_info '{"agentId":"Improver"}'`. Missing: create it as a fork
of Tau's main agent with `agent_fork` (`sourceId` `Brightside`, `agentId`
`Improver`, `config` the contents of `../agent.json`). Present: apply
`agent.json` with `agent_update` (`agentId`, `config`). Suspended:
`agent_resume`.

## 3. Covia

`$S/covia.sh status`. Upgrade when develop's main code has changed in ways Tau
uses, when Covia's version moved, or when the last upgrade is a week old.
Skip when the change is only tests, docs or build plumbing. If the store
formats changed, do not upgrade: report it for the owner and carry on.

To upgrade: `$S/covia.sh install`; set `covia.version` in `pom.xml` if
Covia's version changed; `mvn -q -o clean test` in `$W`; fix what broke,
adopting new Covia features where that is more idiomatic; commit. An upgrade
run makes no other improvements. Compile errors in files nobody touched mean
another session installed Covia jars over ours: `$S/covia.sh install <the
installed commit>` and build again.

## 4. Improve (not on an upgrade run)

- Candidates: approved issues first
  (`gh issue list --repo covia-ai/brightside --state open --label approved`),
  then the backlog: `$S/mcp.sh covia_read '{"path":"w/skill-feedback"}'`,
  entries without a `status`, then improvements of your own: anything you
  noticed that would make Tau more useful or more idiomatic Covia. It is a
  collaboration: put your own ideas to Improver in the consultation and build
  the ones you agree on.
- Consult once per run: `agent_chat` with `Improver`, new session, a brief of
  today's state and the top candidates. Ask what hurts most, with the failing
  action, and the smallest fix. A later chunk continues that session in a
  line or two.
- A chunk is one substantial item and any number of trivial ones, each its
  own commit that one `git revert` undoes. No drive-by edits.
- Substantive design (a new interface, skill family, operation or agent; a
  change to authority, startup or the loop) needs the owner: open an issue
  labelled `proposal` with the problem, the evidence and the design, and do
  not build it until it is labelled `approved`. Small text edits and minor
  fixes need no issue. A change to Covia's design: an issue here labelled
  `upstream` and `proposal`. A Covia bug: report it at once on
  `covia-ai/covia` (search for an existing issue first and extend it), with
  the smallest reproduction and no exploit detail; work around it in Tau only
  if the fix is not Tau's to make, and name the issue.
- Test as AGENTS.md says (`mvn -q -o test`); commit by file name; the message
  says what changed and names the issue (`Fixes #N`).
- Close a backlog entry by re-running its `failedAction` once relaunched,
  then `covia_write` `w/skill-feedback/<id>/status` = `resolved` and
  `.../resolution` = what was done (commit or issue).
- Nothing worth doing is a valid outcome: then close stale entries.

## 5. Relaunch and check (when main code or resources changed)

```bash
cd "$W" && mvn -q package -DskipTests
$S/relaunch.sh && $S/smoke.sh
```

On success: `cp "$W/target/brightside.jar" ~/.brightside/code/last-good.jar`,
then re-run the failing actions of the entries this chunk fixed, and go back
to step 4 for another chunk if the bound at the top allows. On failure:
`$S/relaunch.sh file://code/last-good.jar`, `$S/smoke.sh`, revert this
chunk's commits, stop taking chunks, and merge what passed.

## 6. Merge

`git -C "$M" merge --ff-only improve`. If the owner's uncommitted work in `$M`
blocks it, leave it and report. Never push.

## 7. Report

The final message, under 15 lines, plain words: first what needs the owner
(proposals opened, upstream requests, failures, anything stopped), then what
was reported to Covia, then what
changed (commits, Covia, checks).
