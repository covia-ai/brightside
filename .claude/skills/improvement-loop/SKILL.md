---
name: improvement-loop
description: Run one pass of Tau's daily self-improvement loop - Covia upgrade when due, then feature work chosen with Tau's improvement agent, relaunch, machine checks, merge into develop, short report. Use when the scheduled loop fires or the owner asks for a loop run.
---

# Tau's improvement loop: one run

The design and its reasons are in `docs/SELF_IMPROVEMENT.md`; this is the
procedure. Be quick and frugal: elapsed time and tokens are the measure, and
the owner wants tokens left at the end of the week. Read diffs and logs, not
whole files; no wide exploration; a subagent only for a read across many
files. The run's work is improving Tau. An upgrade is maintenance: when one
is due it comes first as a chunk of its own, and the run then goes on to
improve. Work in chunks: an upgrade, or one substantial item with its trivial
companions, each tested, committed and relaunched before the next. After the
first improvement chunk, start another only if the run is still under an
hour and the last chunk went smoothly; otherwise wrap up. Two hours is a soft ceiling: past
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
  `$S/relaunch.sh "Tau was down (say why, from its log); started from the
  last good build, relaunched so Tau owns its process" file://code/last-good.jar`:
  the process this session starts sits in the session's Windows job and ends
  with it, while the one Tau relaunches into leaves the job and runs on. Any
  other failed check: report it; fixing it is this run's work.

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

To upgrade: note the installed commit `status` printed, then
`$S/covia.sh install`; set `covia.version` in `pom.xml` if
Covia's version changed; `mvn -q -o clean test` in `$W`; fix what broke,
adopting new Covia features where that is more idiomatic; commit. Relaunch
and check it (step 5) before improving, so a failure is the upgrade's alone;
then go on to step 4. Compile errors in files nobody touched mean
another session installed Covia jars over ours: `$S/covia.sh install <the
installed commit>` and build again.

### When the upgrade fails

Tests that stay red, or a relaunch or smoke check that fails, make debugging
the upgrade this run's work, before any feature work. Roll Tau back first if
it was relaunched (step 5), then find the cause: Tau's tests and log, and the
Covia commits between the old and new installed ones (`git -C ../covia log`
and `diff`, read only). Narrow it to the change that broke Tau.

- **Tau's to fix** (Covia changed on purpose, or Tau relied on something it
  never promised): fix it in Tau, test, commit, and relaunch and check again.
- **Covia's fault** (a regression or bug): report it on `covia-ai/covia` at
  once, as step 4 says, with the commit that introduced it and the smallest
  reproduction. Work around it in Tau only if that is small and clean, naming
  the issue. Otherwise roll the upgrade back: revert its commits,
  `$S/covia.sh install <the old commit>`, build, and go on to step 4 on the
  old Covia; a later run upgrades once the issue is fixed.
- **Unresolved within the bound:** roll back the same way and report what is
  known, with the evidence, at the top of the report.

## 4. Improve

- Candidates, in this order:
  1. Open issues on `covia-ai/brightside`
     (`gh issue list --repo covia-ai/brightside --state open`). Take one that
     fits a chunk, or a slice of a big one. A `question` issue is the owner's
     to answer: once his comment settles it, act on the answer and close it.
  2. The backlog: `$S/mcp.sh covia_read '{"path":"w/skill-feedback"}'`,
     entries without a `status`.
  3. Improvements of your own: anything that would make Tau more useful, or
     more idiomatic Covia and Lattice. Bring two or three to the consultation.
- It is a collaboration: put the candidates, yours included, to Improver and
  build what you agree on. Conclude there is nothing worth doing only after
  weighing all three kinds.
- Consult once per run: `agent_chat` with `Improver`, new session, a brief of
  today's state and the top candidates. Ask what hurts most, with the failing
  action, and the smallest fix. A later chunk continues that session in a
  line or two.
- A chunk is one substantial item and any number of trivial ones, each its
  own commit that one `git revert` undoes. No drive-by edits.
- Bug fixes and minor tweaks: just do them; they need no issue. A major
  enhancement may have an issue as its record, opened when the work starts
  and closed by its commit.
- An open design question (how something should work, where the evidence
  does not settle it; anything touching authority, startup or the loop
  itself) goes to the owner: an issue labelled `question` that tags `@mikera`,
  with the problem, the evidence, the options and your recommendation. Build
  around it, not through it, until he answers. A question about Covia's
  design is asked the same way, saying so in the title. A Covia bug: report it at once on
  `covia-ai/covia` (search for an existing issue first and extend it), with
  the smallest reproduction and no exploit detail; work around it in Tau only
  if the fix is not Tau's to make, and name the issue.
- Test as AGENTS.md says (`mvn -q -o test`); commit by file name; the message
  says what changed and names the issue (`Fixes #N`).
- Close a backlog entry by re-running its `failedAction` once relaunched,
  then `covia_write` `w/skill-feedback/<id>/status` = `resolved` and
  `.../resolution` = what was done (commit or issue).

## 5. Relaunch and check (when main code or resources changed)

```bash
cd "$W" && mvn -q package -DskipTests
$S/relaunch.sh "<what changed, and how it was tested>" && $S/smoke.sh
```

Odin judges every relaunch, so the reason says what this one is for: what
changed since the build Tau runs, and the tests that passed ("Fixes #40:
the inbox notifies from the tray; tests: 463 green"); a rollback says what
failed. The script sends the build's commit and the rollback with it. When it
fails because Odin held, it prints what he asked: answer that in the next
request, or report it for the owner. Never send the same relaunch again
without the reasons he asked for.

On success: `cp "$W/target/brightside.jar" ~/.brightside/code/last-good.jar`,
then re-run the failing actions of the entries this chunk fixed, and go to
step 4: after an upgrade always, after an improvement if the bound at the top
allows. On failure:
`$S/relaunch.sh "<what failed>; rolling back" file://code/last-good.jar` and
`$S/smoke.sh`. For an
upgrade, then debug it as step 3 says. For an improvement, revert this
chunk's commits, stop taking chunks, and merge what passed.

## 6. Merge

`git -C "$M" merge --ff-only improve`. If the owner's uncommitted work in `$M`
blocks it, leave it and report. Never push.

## 7. Report

The final message, under 15 lines, plain words: first what needs the owner
(questions opened, failures, anything stopped), then what
was reported to Covia, then what
changed (commits, Covia, checks).
