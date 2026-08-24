# AGENTS.md — BrightSide

Instructions for AI coding agents working in this repository. The workspace
root (`../AGENTS.md`) sets the cross-repo rules (GitHub identities, British
English); this file adds what is specific to BrightSide.

## Purpose

BrightSide exists to **demonstrate the power of the Covia Grid and lattice
technology as a personal agent**. It is a showcase: a single desktop app that
puts a full Covia venue — engine, adapters, lattice-backed persistent state,
agent framework, MCP/A2A/HTTP surface — on someone's own machine, under their
own identity, and lets them talk to an agent running on it. Design choices
should favour showing that platform off (real venue, real agents, real lattice
state, real federation potential) over hiding it behind a generic chat UI.

## What this is

BrightSide is a Swing/FlatLaf desktop application that runs a Covia venue
embedded in its own process, minimises to a system-tray icon, and offers a chat
window that talks to an agent on that venue. Single Maven module,
`ai.covia:brightside`, main class `covia.brightside.BrightSide`.

## Build and test

- Requires Java 21+, Maven 3.7+ and a locally installed Covia
  `0.10.0-SNAPSHOT` (`../convex` then `../covia`, `mvn install`). Covia
  snapshots are not on Maven Central; if the build cannot resolve
  `ai.covia:venue`, that is the reason.
- `mvn package` → `target/brightside.jar` (shaded, runnable). `mvn test` for
  the tests alone. `mvn exec:java` runs the app from the build.
- Tests boot real venue engines (`Engine.createTemp`, `VenueServer.launch`
  on a free port) under `-Djava.awt.headless=true`; keep them that way — a
  test must never put a window or a tray icon on the developer's desktop.
- The chat tests use `v/test/ops/llm`, the venue's echo test LLM, so they
  need no API key.

## Design rules

- **Threading.** Swing on the event thread only. Venue launch/close, agent
  calls (`ChatSession.send`) and desktop integration (browser, editor) run
  on background threads — `ChatPanel` uses a `SwingWorker`.
- **The tray is best-effort.** `TrayManager.install` returns null on
  headless/unsupported desktops or `BRIGHTSIDE_NO_TRAY=1`; the window must
  behave sensibly without it (close exits, minimise minimises). A tray
  failure never takes the app down. Same contract as Covia's own
  `covia.venue.Tray`.
- **Shutdown flushes state.** Exit closes the `VenueServer` before
  `System.exit`; a Convex `Shutdown` hook at `SERVER - 10` covers Ctrl-C and
  SIGTERM, mirroring `MainVenue`. Keep `EmbeddedVenue.close()` idempotent.
- **Configuration is data, not code.** `AppConfig` merges the user's
  `venue` map over BrightSide's defaults key-for-key and passes it straight
  to `VenueServer.launch`, so new venue options need no BrightSide change.
  Anything the file omits must have a default; an empty `{}` is valid.
- **The user is a named venue principal, not the venue.** The chat window
  acts as `<venueDID>:u:<name>` (`Identity`) — the same suffix convention as
  Covia's `<venueDID>:public`. The name is chosen at a first-launch screen
  (`IdentityDialog`) and stored in `~/.brightside/identity.json`, separate
  from the hand-edited `config.json`. Chatting as a distinct user (not the
  venue DID) makes Covia attribute turns as coming from the agent's *owner*,
  not from "the venue operator"; `ChatSession.ATTRIBUTION_GUIDANCE` tells the
  agent to treat those venue notes as infrastructure. Because the user is not
  the venue principal, the API key must sit in the venue's `secrets.public`
  store (what `resolveSecret` falls back to), not `secrets.venue`.
- **Chat goes through the agent framework** (`v/ops/agent/create|update|
  info|chat` via `LocalVenue`), not through a private LLM call, so what the
  window shows is what any other client of the venue would see.
- **Skills and memory, by namespace.** BrightSide's shipped skills (e.g.
  `introduction`, `DefaultSkills`) are seeded into `v/skills/brightside/…` as
  the venue principal (only the venue may write `v/`) and pinned into the agent
  via `config.loads`. The user's own skills live in `w/skills` (a declared
  skillset). The assistant's memory and scratch live in `n/` (`n/memory`,
  pinned via a `v/ops/memory` recall context entry, with the `v/ops/memory`
  tool so it can write). Introductory persona content is a **skill**, not
  system-prompt prose — the prompt stays small. See `docs/DESIGN.md`.
- **Packaging.** The runnable jar is built by `maven-shade-plugin` with the
  services transformer (Jetty/Javalin/LangChain4j rely on
  `META-INF/services`) and drops `openapi-plugin/**` from
  `convex-restapi` so the venue's own OpenAPI document is served — the same
  fix Covia applies in `venue/src/assembly/covia-jar.xml`. Logging is
  configured programmatically from `brightside/logback.xml` because the
  venue jar ships a root `logback.xml` of its own.

## Conventions

- British English in code comments, UI text and docs.
- Tabs for indentation in Java and XML, as in Covia.
- Match Covia's naming for venue concepts (venue, engine, adapter, operation,
  agent, session, job) — see `../covia/AGENTS.md`.

## Git

- Remote: `https://github.com/covia-ai/brightside` (the covia-ai org). Push as
  `mikera` through the per-repo credential-helper override described in the
  workspace `AGENTS.md`.
- Commit as the repo's local `user.name`/`user.email` (check
  `git config --local`); use `gh` as `brittleboye` for issues and PRs.
