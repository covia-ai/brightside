# AGENTS.md — BrightSide

Instructions for AI coding agents working in this repository. The workspace
root (`../AGENTS.md`) sets the cross-repo rules (GitHub identities, British
English); this file adds what is specific to BrightSide.

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
- **Chat goes through the agent framework** (`v/ops/agent/create|update|
  info|chat` via `LocalVenue`), not through a private LLM call, so what the
  window shows is what any other client of the venue would see.
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

- Remote: `https://github.com/Covia-Dev/brightside`. `Covia-Dev` is a
  GitHub *user* account, not an org, so pushes need either that account's
  credentials or a collaborator account; see the workspace `AGENTS.md` for
  the per-repo credential-helper setup.
- Commit as the repo's local `user.name`/`user.email` (check
  `git config --local`); use `gh` as `brittleboye` for issues and PRs.
