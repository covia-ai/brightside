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
`ai.covia:brightside`, main class `covia.brightside.BrightSide`.

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

| Topic | See |
|---|---|
| Product principles, namespaces, how the assistant is shaped | `docs/DESIGN.md` |
| Structure, threading, chat session, transcript, watcher, skills, packaging | `docs/ARCHITECTURE.md` |
| Startup, takeover, tray, exit | `docs/LAUNCH.md` |
| Venue, user and agent identity | `docs/IDENTITY.md` |
| Odin, the operator's administrative agent | `docs/ODIN.md` |
| Vault, keys, recovery, authentication, network exposure | `docs/SECURITY.md` |
| Onboarding flows and identity | `docs/ONBOARDING.md` |
| `config.json`, API keys, reaching the venue from other tools | `docs/CONFIGURATION.md` |
| Federation roadmap | `docs/NETWORK.md` |
| Shipped skills and Brightside operations | `BrightsideSkillsAdapter`, `BrightsideAdapter` |
| Covia's own conventions and vocabulary | `../covia/AGENTS.md` |

## Conventions

British English in code, UI text and docs. Tabs in Java and XML. Covia's names
for venue concepts: venue, engine, adapter, operation, agent, session, job.

## Git

Remote `https://github.com/covia-ai/brightside`. `master` is the default
branch; `develop` is the working branch — commit there and merge to `master`.
Commit as the repo's local `user.name` / `user.email`; use `gh` as
`brittleboye` for issues and PRs.
