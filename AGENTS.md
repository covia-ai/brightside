# AGENTS.md — Brightside

Brightside is a self-sovereign personal agent: a Swing/FlatLaf desktop app that
runs a complete Covia venue in its own process and chats, as the owner's own
named principal, with an agent on that venue. It is a product, not a demo
(`docs/DESIGN.md`). One Maven module, `ai.covia:brightside`; launcher
`brightside.Main`, desktop app `brightside.BrightSide`.

## Build and test

- Java 25+, Maven 3.9+. `covia.version` in `pom.xml` names the Covia line. A
  SNAPSHOT needs every Covia module the pom names (`venue`, `workbench` and the
  in-process `covia-*` modules) installed from `../covia`: `-pl venue -am`
  alone leaves the others stale.
- `mvn package` → `target/brightside.jar`; `mvn test`; `mvn exec:java`.
- Tests boot real venues headless and never put a window or tray icon on a
  desktop. Chat tests use the echo LLM `v/test/ops/llm`, so no API key. Test
  mechanism (wiring, state, behaviour), never UI or prose content.
- Classes run in parallel in one JVM. A class that only needs a venue takes
  one from `SharedVenue` as a user principal of its own; persistent Etch tests
  use `StoreTestDirectory`; an expected failure hides its logs with
  `ExpectedLogs`. A negative check follows the asynchronous step's future or
  latch, never a pause. Keep the run under ten seconds.

## Where the detail lives

Design docs are in `docs/`; the `design-docs` skill
(`.claude/skills/design-docs/SKILL.md`) maps them and holds the rules for
changing them. Keep this file short: detail goes to the doc or class that
owns it.

| Topic | See |
|---|---|
| Product principles | `DESIGN.md` |
| Process shape, reads versus actions, source map, packaging | `ARCHITECTURE.md` |
| Skills, tools and memory | `SKILLS.md` |
| Startup, takeover, restart and relaunch, store repair, tray, exit | `LAUNCH.md` |
| Venue, user and agent principals | `IDENTITY.md` |
| The gods; Odin, Thor, Njord, Eir; the treasury | `GODS.md`, `ODIN.md`, `THOR.md`, `NJORD.md`, `EIR.md`, `TREASURY.md`, `PAYMENTS.md` |
| Vault, keys, recovery, network exposure | `SECURITY.md` |
| First run, unlock, recovery, settings | `ONBOARDING.md` |
| `config.json`, files, integrations, reaching the venue from other tools | `CONFIGURATION.md` |
| Brightside's own state in the venue | `APP_STATE.md` |
| Views following the lattice; the Jobs page; the Inbox | `WATCHER.md`, `JOBS.md`, `INBOX.md` |
| The daily self-improvement loop (procedure: the `improvement-loop` skill) | `SELF_IMPROVEMENT.md` |
| Ahead of the code: federation, the Peers map, memory proposals, trust in routes that speak for the owner | `NETWORK.md`, `PEERS.md`, `ADVANCED_MEMORY.md`, `TRUST.md` |
| The shipped skills and Brightside's operations | `BrightsideSkillsAdapter`, `BrightsideAdapter` |
| Covia's conventions, vocabulary and design | `../covia/AGENTS.md`, `../covia/venue/docs/` |

When a change moves a decision or a name, fix the doc that owns it in the same
commit.

## Conventions

- British English in code and UI text as well as docs. Tabs in Java and XML.
- Covia's names for venue concepts: venue, engine, adapter, operation, agent,
  session, job.
- Screens are composed from `ui/components`; a new look is a style class in
  `brightside/ui/FlatLaf.properties` worn through `Styles`, and colours come
  from `Theme`.

## Git

Remote `https://github.com/covia-ai/brightside`. `master` is the default
branch; `develop` is the working branch — commit there and merge to `master`.

## When Brightside runs you

If `COVIA_PROJECT` is set, Brightside's own assistant is driving you, headless,
in its checkout at `~/.brightside/code/brightside`: the app improving itself.
Editing, building, testing, git and looking around are allowed outright and
nobody sees a permission prompt, so none will be waited on; your own safety
checks judge anything else, and a blocked action is reported, never worked
around. To ask something, end the turn with the question: the assistant
answers on the next turn of the same session, taking it to the owner when it
is theirs to decide. Only the final message comes back, so make it a faithful
report of what was done, which tests ran and what remains. Never launch
Brightside from the checkout (`mvn exec:java`, `java -jar`): the instance
driving you is running on this machine, and a second launch would take it
over. Build with `mvn package` and say that Brightside must be relaunched into
that build, which the owner, or Odin on their behalf, does.
Commit on `develop` after each meaningful, tested chunk of work; pushing,
merging to `master` and releasing need the owner's explicit word, relayed in
the prompt.
