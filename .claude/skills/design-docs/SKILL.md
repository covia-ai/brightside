---
name: design-docs
description: Brightside's design documents under docs/ — which one answers what, and the rules for writing or changing one. Use when reading, editing or adding documentation in this repository, or when a code change makes a doc wrong.
---

# Brightside design docs

The `docs/` folder explains *why* things are the way they are, for a person or
an agent orienting before they work. `AGENTS.md` at the root is the short
guide and points here.

## Which doc answers what

| Read | For |
|---|---|
| `docs/DESIGN.md` | product principles: a self-sovereign personal agent, no jargon, machinery hidden not removed |
| `docs/ARCHITECTURE.md` | the one-process shape, the reads-versus-actions rule, an orientation map of the source, the key decisions, packaging |
| `docs/SKILLS.md` | how the assistant gets abilities: namespaces, what it starts with, how a load works, the shape of the shipped library, growing and feedback |
| `docs/IDENTITY.md` | venue, user and agent principals; one key; switching user; known limits |
| `docs/ODIN.md` | the operator's administrative agent: the two bridges, asking the owner, his judgement |
| `docs/SECURITY.md` | the recovery invariant, key hierarchy, algorithms, file roles, recovery procedure, network exposure, limitations |
| `docs/ONBOARDING.md` | first run, unlock, recovery from the phrase, settings afterwards |
| `docs/LAUNCH.md` | startup order, taking over a running instance, tray, exit |
| `docs/CONFIGURATION.md` | the reference: files under `~/.brightside/`, `config.json`, API keys, Discord, Moltbook, identity, reaching the venue from other tools |
| `docs/NETWORK.md` | target state once venues meet venues; deliberately ahead of the code |

Covia's own design lives in `../covia/venue/docs/` (skills, agent context,
adapters, UCAN) and is not repeated here.

## Rules for a design doc

- **Overview first.** One or two sentences saying what the doc covers, then
  four to six key bullets a reader can stop after. Sections follow.
- **Orientation, not implementation.** Name the class or file a reader must
  go to, and no more. No per-file source maps, no method-by-method walkthroughs,
  no schemas or call shapes that the code and tests already state. If a fact
  will drift the next time the code moves, leave it to the code.
- **No history.** Not "previously", "no longer", "migrated from", issue
  numbers for things already done, or design intent that the code has since
  overtaken. An open limit may cite its issue once, in a "known limits" list.
- **One home per fact.** Say a thing in the doc that owns it and link from the
  others: files and their protection are CONFIGURATION's and SECURITY's, the
  key hierarchy is SECURITY's, principals are IDENTITY's, the skill library's
  shape is SKILLS's.
- **British English, plain prose.** The words a person would use; Covia's
  names for venue concepts; no marketing.

## When code changes

A change that moves a decision or a name has a doc to keep true:

- a new or renamed skill, or a change to what one carries → `SKILLS.md`
- a new screen, integration or configuration key → `CONFIGURATION.md`, and
  `ARCHITECTURE.md`'s orientation map if a new package or entry point appeared
- anything about keys, files or recovery → `SECURITY.md`, and `ONBOARDING.md`
  if the person's flow changed
- anything about principals or switching user → `IDENTITY.md`
- a new doc → add it to the table above and to the map in `AGENTS.md`

Fix the doc in the same commit as the code. Before committing a doc change,
read the top of the doc as a stranger: overview, bullets, then sections; no
stale claim, no duplicated fact, a link where another doc owns the detail.
