# AGENTS.md — Brightside

Instructions for AI coding agents working in this repository. The workspace
root (`../AGENTS.md`) sets the cross-repo rules (GitHub identities, British
English); this file adds what is specific to Brightside.

## Purpose

Brightside exists to **demonstrate the power of the Covia Grid and lattice
technology as a personal agent**. It is a showcase: a single desktop app that
puts a full Covia venue — engine, adapters, lattice-backed persistent state,
agent framework, MCP/A2A/HTTP surface — on someone's own machine, under their
own identity, and lets them talk to an agent running on it. Design choices
should favour showing that platform off (real venue, real agents, real lattice
state, real federation potential) over hiding it behind a generic chat UI.

## What this is

Brightside is a Swing/FlatLaf desktop application that runs a Covia venue
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
  `venue` map over Brightside's defaults key-for-key and passes it straight
  to `VenueServer.launch`, so new venue options need no Brightside change.
  Anything the file omits must have a default; an empty `{}` is valid.
- **The user is a named venue principal, not the venue.** The chat window
  acts as `<venueDID>:u:<name>` (`Identity`) — the same suffix convention as
  Covia's `<venueDID>:public`. The name is chosen at a first-launch screen
  (`WelcomePanel`) and stored in `~/.brightside/identity.json`, separate
  from the hand-edited `config.json`. Chatting as a distinct user (not the
  venue DID) makes Covia attribute turns as coming from the agent's *owner*,
  not from "the venue operator"; `ChatSession.ATTRIBUTION_GUIDANCE` tells the
  agent to treat those venue notes as infrastructure. Because the user is not
  the venue principal, the API key must sit in the venue's `secrets.public`
  store (what `resolveSecret` falls back to), not `secrets.venue`.
- **Chat goes through the agent framework** (`v/ops/agent/create|update|
  info|chat` via `LocalVenue`), not through a private LLM call, so what the
  window shows is what any other client of the venue would see.
- **`BrightsideAdapter` is the venue extension.** A real Covia `AAdapter`
  (`covia.brightside.BrightsideAdapter`), registered on the embedded engine in
  `EmbeddedVenue.launch`. Its `installAssets()` installs the shipped skills
  under `v/skills/brightside/…` (via `installSkill` from the JSON resources in
  `src/main/resources/brightside/skills/`) and the `brightside:info` op — its
  assets live and die with the adapter, the idiomatic way (cf. `AuthAdapter`).
  Add Brightside-specific operations here (a `brightside/<op>.json` resource +
  a case in `invokeFuture`); ship a new default skill by adding a skill JSON
  resource + an `installSkill(...)` line.
- **Skills and memory, by namespace.** Two shipped skills are pinned into the
  agent via `config.loads`: `introduction` (persona) and `skills` (how it
  grows). Persona content is a **skill**, not system-prompt prose — the prompt
  stays small.
- **Self-authoring, gated hierarchically.** The pinned `skills` meta-skill
  gates `skill-authoring` as a sub-skill (its `skill.skills` facet), and
  `skill-authoring` is the only skill whose facet grants the `covia:write`
  tool. So the assistant can extend itself — it loads `skill-authoring` and
  writes a new skill into its own `w/skills` (a declared skillset, so authored
  skills become discoverable) — but the write capability is not in context
  until it deliberately loads that sub-skill.
- **Memory and scratch** live in `n/`: `n/memory` (recall pinned as a
  `v/ops/memory` context entry, with the `v/ops/memory` tool so it can write)
  and other scratch. See `docs/DESIGN.md`.
- **Packaging.** The runnable jar is built by `maven-shade-plugin` with the
  services transformer (Jetty/Javalin/LangChain4j rely on
  `META-INF/services`) and drops `openapi-plugin/**` from
  `convex-restapi` so the venue's own OpenAPI document is served — the same
  fix Covia applies in `venue/src/assembly/covia-jar.xml`. Logging is
  configured programmatically from `brightside/logback.xml` because the
  venue jar ships a root `logback.xml` of its own.

## Debugging / accessing the venue

Use the venue's **standard interfaces** — the HTTP API, the Covia SDK, or MCP —
not bespoke app code. Access is capability-based, so you need a token:

- **The venue trusts JWTs it signed itself.** `VenueAuthenticator.tryVerifyVenueSigned`
  accepts a JWT with `iss == venueDID` verified against the venue key, and
  authenticates the bearer as the token's `sub`. This is the same mechanism the
  venue's OAuth login uses (`LoginProviders`).
- **Mint an admin/user token** by signing with `~/.brightside/venue.key` (a
  32-byte Ed25519 seed) using the Covia/Convex SDK:
  `JWT.signPublic({sub, iss:venueDID, aud:venueDID, iat, exp}, AKeyPair.create(Blob.fromHex(seed)))`.
  Set `sub` to `<venueDID>` for the operator, or `<venueDID>:u:<name>` to act as
  a local user (the local `u:<name>` principals have no key of their own, so a
  venue-signed token is the only way to authenticate as them off-process).
- **Then use any standard client**: `Authorization: Bearer <token>` against
  `/api/v1/...` (e.g. `GET /api/v1/values/list?path=w/skills`,
  `POST /api/v1/run`), the Covia SDK with a bearer/keypair auth strategy, or the
  MCP endpoint. A `u:<name>` token reads that user's own `w/`/`n/` as their own
  namespace — no cross-user proof needed.
- **There is no operator backdoor into user data**: the venue principal reading
  another user's namespace still needs a proof. Authenticate *as* the user (via
  a venue-signed `sub`) rather than trying to read across users.

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
