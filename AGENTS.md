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
- **Look and feel.** `LAF` installs FlatLaf's macOS-style themes
  (`FlatMacDarkLaf`/`FlatMacLightLaf`) with a purple accent and rounded
  geometry — keep it modern. The chat UI lives in its own
  `covia.brightside.ui.chat` package, one component per file: `ChatPanel` (the
  container, which owns send/copy), `Bubble` (a rounded, selectable message),
  `MessageColumn` (the scrolling, width-tracking column), `TypingIndicator`
  (the "typing…" dots), `ExpandableActivity` (the tool-steps chip),
  `ConversationList` (the left-hand switcher — a *New conversation* button over a
  list of past sessions), `SelectableText` (the read-only, selectable text used
  for an activity's narration and tool results, so they can be copied) and
  `ChatStyle` (shared theme-derived colours + a text helper). `ChatPanel`
  renders each message as its **own rounded `Bubble` component** in the
  `MessageColumn` (user right/accent, assistant left/surface) — separate
  components on purpose, so new message kinds (images, cards, tool output) can
  be added as their own files/row types. `Bubble` is a dumb display component;
  selection tracking and the context menu are wired onto its `textArea()` by
  `ChatPanel`. Text in a bubble is selectable but the read-only caret is hidden
  (no insert cursor); right-click offers *Copy message* / *Copy conversation*
  (`conversationText()`) to get text out across messages.
- **Brightside runs off the venue's live session state — no local transcript
  copy.** On start `startChat` reads the most recently active conversation
  straight from the agent's session store (`SessionHistory.loadLatest`:
  `covia:read g/<agentId>` → newest `sessions[sid].frames[0].conversation`,
  projecting user + completed-assistant turns), renders it, and
  `ChatSession.resume(sessionId)` continues that same session (falling back to
  a new one if the id is stale, so it never blocks chatting). The UI just
  reflects turns as they happen; the venue records them, and the next launch
  re-reads live state. This reads the `AgentState` schema directly (public field
  names) because the purpose-built `agent:sessionRead` projection is restricted
  to an agent's own execution context, so it isn't callable by the owner.
- **Every past conversation is switchable.** The agent record holds many
  `sessions`; `SessionHistory.listSessions` enumerates them (newest first,
  titled by each one's first user message) for the `ConversationList` switcher,
  and `SessionHistory.load(agentId, sessionId)` / `snapshotOf(record, sessionId)`
  reopen a specific one. *New conversation* (the switcher button, or *File → New
  chat*) resets the session so the next message mints a fresh one — it joins the
  switcher once its first message lands. `BrightSide.openSession` resumes a
  chosen past session and continues it. Right-clicking a conversation offers
  *Open*, *Rename…*, *Copy transcript* and *Delete*: rename/delete go through the
  owner-callable `v/ops/agent/rename-session` / `v/ops/agent/delete-session` (the
  acting user has `AGENT_WRITE` over their own agent), a set title lives at the
  session's `meta.title` and shows in place of the first-message label, and
  *Copy transcript* is pure client-side (`SessionHistory.plainText`); deleting
  the on-screen conversation drops back to a fresh chat. Crucially the watcher is **viewed-session
  aware**: it hands `BrightSide.onAgentChanged` the changed agent record and the
  controller re-renders `viewedSessionId` (the session on screen), not always the
  latest — so a background update to another session never yanks you off the one
  you opened.
- **Transcript items and tool activity.** `SessionHistory` projects the
  conversation into a list of `Item`s: `Message` (user / final-assistant text)
  and `Activity` (the intermediate "let me try…" narration and tool
  calls/results of a turn, grouped between question and answer). `ChatPanel`
  renders a `Message` as a `Bubble` and an `Activity` as an `ExpandableActivity`
  — a collapsed "N tool steps" chip that expands to show the steps (tool
  name + ✓/✕ + result). So the final reply is what shows by default, with the
  tool use available to dig into. New item kinds go here.
- **Change detection is a lattice value compare.** `ConversationWatcher` polls
  the agent value (`SessionHistory.loadLatest`) every few seconds while the chat
  is showing and compares it with `.equals` to the last one shown — lattice
  values are immutable and content-addressed, so an unchanged conversation is an
  equal value and any change (a new turn, an edit from another client, an
  out-of-band agent update) is a different one. Only then does it refresh, and
  `ChatPanel.refreshTo` re-renders only if the projected turns actually differ
  from what's on screen (so the app's own turns don't cause a redundant
  re-render). *File → Refresh* forces an immediate compare.
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
- **Tool-granting skills are loaded on demand, never pinned.** A `skill_load`
  is what denormalises a skill's `skill.tools` into the palette; a hand-written
  `config.loads` pin loads the skill's *body* but not its tools. So a skill that
  grants tools (`conversations` → the read-only past-session tools
  `agent:sessions`/`agent:session-read`; `skill-authoring` → `covia:write`) is
  made **discoverable** and left for the agent to load when its description
  matches, not pinned. `conversations` is revealed by the pinned `introduction`
  (its `skill.skills`), so `skill_load conversations` resolves; that's how the
  agent answers "what did we discuss before?" — it loads the skill, gets the
  tools, and reads its own past sessions rather than claiming it can't. Skill
  **descriptions are the trigger**: pack the words the user actually says
  ("past conversation sessions", "what we discussed", "history") into them.
- **Discovery is broad, authority stays deliberate.** The agent's
  `config.skillsets` are `["w/skills", "v/skills/root"]` — its own skills plus
  the venue's shipped library. `v/skills/root` is the *usable* skillset level
  (the per-family entry routers — `covia`, `grid`, `agents`, `discovery`,
  `lattice`, `venue`, …, and adapter integrations once their modules are
  loaded); pointing at bare `v/skills` would be silently useless, as it holds
  skillsets, not skills (Covia issue #409). The agent sees the routers in its
  skills index and loads one to reveal and use that family's tools. Only
  `defaultTools` (read-only `covia read`/`list`) and the memory tool are
  always-on; every broader capability — writes, HTTP, files, agents — arrives
  by loading the skill that grants it, so the gated `skill-authoring` model
  (write stays out of context until deliberately loaded) still holds. Enabling
  the optional module adapters (Telegram, Discord, …) in an embedded venue is
  an upstream ask — Covia issue #410.
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
