# AGENTS.md — Brightside

Instructions for AI coding agents in this repository. The workspace rules in
`../AGENTS.md` (GitHub identities, British English) still apply.

## What this is

Brightside is a self-sovereign personal agent: a Swing/FlatLaf desktop app that
runs a complete Covia venue inside its own process and chats, as the owner's own
named principal, with an agent on that venue. It is a product, not a demo. The
owner's needs — usefulness, privacy, trust, control — come first; Covia is the
means, not the message. Everyday screens carry no venue/agent/DID jargon; the
full technical surface lives under Settings. One Maven module,
`ai.covia:brightside`, main class `brightside.BrightSide`.

## Build and test

- Java 21+, Maven 3.7+. `covia.version` in `pom.xml` names the Covia line; a
  SNAPSHOT needs `../convex` then `../covia` installed with `mvn install`.
- `mvn package` → `target/brightside.jar`; `mvn test`; `mvn exec:java`.
- Tests boot real venues headless (`-Djava.awt.headless=true`) and must never
  put a window or a tray icon on a desktop. Chat tests use the echo LLM
  `v/test/ops/llm`, so no API key. Test mechanism (wiring, state, behaviour).
  No tests for the UI or for prose content — those are obvious in use and a
  test would only say the words twice.

## Where the detail lives

Start with `docs/DESIGN.md` for what Brightside is, `docs/ARCHITECTURE.md`
for how it is put together, and `docs/SKILLS.md` for how the assistant gets
its abilities. Each doc opens with an overview and key bullets; the
`design-docs` skill (`.claude/skills/design-docs/SKILL.md`) is the map and the
rules for changing them.

| Topic | See |
|---|---|
| Product principles, how the assistant is shaped | `docs/DESIGN.md` |
| The one-process shape, reads versus actions, orientation map, key decisions, packaging | `docs/ARCHITECTURE.md` |
| Skills, tools, memory: namespaces, what loads when, the shipped library | `docs/SKILLS.md` |
| Startup, takeover, tray, exit | `docs/LAUNCH.md` |
| Venue, user and agent identity | `docs/IDENTITY.md` |
| Odin, the operator's administrative agent | `docs/ODIN.md` |
| Vault, keys, recovery, authentication, network exposure | `docs/SECURITY.md` |
| First run, unlock, recovery, settings | `docs/ONBOARDING.md` |
| `config.json`, files, API keys, integrations, reaching the venue from other tools | `docs/CONFIGURATION.md` |
| Federation roadmap | `docs/NETWORK.md` |
| The shipped skills and Brightside's operations, in code | `BrightsideSkillsAdapter`, `BrightsideAdapter` |
| Covia's own conventions, vocabulary and design | `../covia/AGENTS.md`, `../covia/venue/docs/` |

When a change moves a decision or a name, fix the doc that owns it in the same
commit.

## Conventions

British English in code, UI text and docs. Tabs in Java and XML. Covia's names
for venue concepts: venue, engine, adapter, operation, agent, session, job.

## Git

Remote `https://github.com/covia-ai/brightside`. `master` is the default
branch; `develop` is the working branch — commit there and merge to `master`.
Commit as the repo's local `user.name` / `user.email`; use `gh` as
`brittleboye` for issues and PRs.
