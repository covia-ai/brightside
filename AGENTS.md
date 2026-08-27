# AGENTS.md — Brightside

Instructions for AI coding agents working in this repository. The workspace
root (`../AGENTS.md`) sets the cross-repo rules (GitHub identities, British
English); this file adds what is specific to Brightside.

## Purpose

Brightside is a **powerful, self-sovereign personal agent** — a real product
that helps its owner get real things done, not a demo. It runs privately on the
person's own computer, under their own identity; it remembers what matters to
them, grows new abilities as they need them, and answers to them alone. Their
data and everything it remembers stay on their machine. That is the point.

It happens to be built on genuinely remarkable technology — the Covia Grid: an
in-process venue (engine, adapters, lattice-backed persistent state, an agent
framework, an MCP/A2A/HTTP surface) — and that is what makes a private,
extensible, and (in time) federatable personal agent possible on ordinary
hardware. But Covia is the means, not the message. Design for the owner's real
needs — usefulness, privacy, trust, control — and let the power underneath serve
those, kept out of the way until it's wanted: the everyday screens carry no
jargon (no "venue", "agent", "DID"), while the full technical surface stays
available under Settings for those who want it.

## What this is

Brightside is a Swing/FlatLaf desktop application that runs a Covia venue
embedded in its own process, minimises to a system-tray icon, and offers a chat
window that talks to an agent on that venue. Single Maven module,
`ai.covia:brightside`, main class `covia.brightside.BrightSide`.

## Build and test

- Requires Java 21+, Maven 3.7+ and the Covia version named by
  `covia.version` in `pom.xml` (currently `0.9.6-SNAPSHOT`, the develop line;
  releases resolve from Maven Central, a SNAPSHOT needs `../convex` then
  `../covia`, `mvn install`). If the build cannot resolve `ai.covia:venue`,
  that is why.
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
- **Take over from a running instance instead of fighting the store lock.** Two
  processes can't share `venue.etch`. On launch, `BrightSide.launchVenue` probes
  the port (`Takeover.isRunning`); if an instance answers it prompts *Take Over /
  Cancel*, and on Take Over asks the running one to stop cleanly over the venue's
  own HTTP surface — the `brightside:shutdown` op — then waits for the store to
  free before starting. **The control channel is the venue's embedded Javalin;
  the instruction is a plain shutdown; auth is venue-signed.** Both instances
  unlock the same encrypted identity seed, so the newcomer reads the running
  venue's DID from `/api/v1/status` and mints a venue-signed token (iss = sub = that DID) —
  the venue trusts JWTs it signed itself and authenticates the bearer as the
  operator. `BrightsideAdapter.handleShutdown` gates on
  `ctx.getCallerDID().equals(engine.getDIDString())` (the `auth:whoami`
  "internal" test) and runs the `onShutdown` callback wired by
  `EmbeddedVenue.launch(config, this::exit)` — a clean `exit()`, so the store
  flushes. This is *not* `venue/restart` (a successor-jar relaunch): the newcomer
  is already up and just needs the incumbent to step aside, and it must work from
  an IDE launch with no jar.
- **Configuration is data, not code.** `AppConfig` merges the user's
  `venue` map over Brightside's defaults key-for-key and passes it straight
  to `VenueServer.launch`, so new venue options need no Brightside change.
  Anything the file omits must have a default; an empty `{}` is valid.
- **The user is a named venue principal, not the venue.** The chat window
  acts as `<venueDID>:u:<slug>` (`Identity`) — the same suffix convention as
  Covia's `<venueDID>:public`. The name is chosen at a first-launch screen
  (`WelcomePanel`/onboarding) and stored with its slug and full Covia user DID in
  `~/.brightside/identity.json`, separate from the hand-edited `config.json`.
  The full DID is pinned as soon as the home venue first launches and must match
  on every later launch. **The slug is fixed once chosen; only the display name changes.** The agent
  (`g/<agentId>`), its memory (`n/memory`) and its skills (`w/skills`) all live
  under that principal, so *Change my name* goes through `Identity.withName`
  (same slug, new name) — deriving a fresh slug from the new name would
  silently bind the window to a different, empty agent. Older `identity.json`
  files without a `slug` derive it from the name, as before. **Settings →
  Identity must not conflate the principal layers:** it shows the human-facing
  *Your name*, the full Covia user DID, the home venue DID, the venue's Ed25519
  signing public key, and the passphrase-gated primary seed. The named user does
  not currently have a separate signing key; the owner-controlled venue key is
  its signing authority. DIDs, keys, seeds and other opaque credentials use a
  selectable monospaced field. The user DID, venue DID, public-key and primary-
  seed rows show Convex's standard 7x7 identicon, derived from that same venue
  public key; it is a visual comparison aid, never a substitute for checking
  the full value.
  Chatting as a distinct user (not the
  venue DID) makes Covia attribute turns as coming from the agent's *owner*,
  not from "the venue operator"; the read-only `brightside:context` load tells
  the agent to treat those venue notes as infrastructure. Because the user is not
  the venue principal, the API key must sit in the venue's `secrets.public`
  store (what `resolveSecret` falls back to), not `secrets.venue`.
- **Chat goes through the agent framework** (`v/ops/agent/create|update|
  info|chat` via `LocalVenue`), not through a private LLM call, so what the
  window shows is what any other client of the venue would see. Four
  framework behaviours the session code accounts for: `agent:update` is a
  recursive *merge* (vectors replace, maps merge — a dropped `loads` pin would
  linger) and is rejected while the agent is RUNNING (so `ensureAgent` returns
  false and retries on the next send rather than failing the chat); **a failed
  transition (bad model op, missing API key) leaves the agent SUSPENDED and the
  venue then refuses every chat until `agent:resume`** — so `ensureAgent`
  resumes a suspended agent after re-applying its config, and `send` resumes
  and retries once on an "is suspended" failure (`isSuspended`), otherwise one
  bad reply would brick the chat for good;
  `ChatSession.send` only falls back to a fresh session on the venue's
  "Unknown session" error (`isUnknownSession`) — any other failure keeps the
  session id, so a model or key problem never mints orphan conversations; and a
  turn's tool steps are only persisted when the cycle completes
  (`AgentState`). The in-flight `ThinkingBubble` therefore shows only real
  client lifecycle state (preparing / accepted and running) plus elapsed time;
  it must not invent activity from timing or poll partially persisted turns.
  Covia issue #394 tracks the ordered in-process/SSE agent-event tap needed to
  stream explicit narration and tool calls. When available, feed those events
  into the same `ExpandableActivity`: add a pending tool row at call start,
  fill its expandable result and tick/cross at completion, then reconcile the
  live component with the committed `SessionHistory.Activity` without a
  duplicate. Hidden reasoning is never an event. Hence the generous `timeout`
  default (300 s), since a timeout cancels the job and loses the turn.
- **Look and feel.** `LAF` installs FlatLaf's macOS-style themes
  (`FlatMacDarkLaf`/`FlatMacLightLaf`) with a purple accent and rounded
  geometry — keep it modern. The default font is **Lato**, bundled as OFL TTFs
  under `resources/fonts/lato/` and registered at startup (`LAF.registerFonts`
  → `GraphicsEnvironment.registerFont`, then `setPreferredFontFamily`), so it's
  identical on every platform with no system install. Base size is a comfortable
  15pt (`BASE_FONT_SIZE`) via `defaultFont`; components derive their sizes from
  that. The window opens at 1040×760 (min 720×520). To swap the UI font, drop
  new faces in that folder and update `FONT_FAMILY`/`FONT_RESOURCES` in `LAF`. The chat UI lives in its own
  `covia.brightside.ui.chat` package, one component per file: `ChatPanel` (the
  container, which owns send/copy), `Bubble` (a rounded, selectable message),
  `MessageColumn` (the scrolling, width-tracking column), `EmptyChatState` (the
  centred branded welcome before an uncommitted chat has any turns),
  `ThinkingBubble` (brief real progress + elapsed time), `TypingIndicator`
  (its animated dots), `ExpandableActivity` (the tool-steps chip),
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
- **The window has no application menu bar.** Everyday actions, account actions,
  tray preferences, desktop integration, About and Quit live under *Settings →
  General*; model, identity, vault and authentication retain their dedicated
  Settings pages. Platform shortcuts remain available without adding visual
  menu clutter. The tray popup and local context menus remain appropriate where
  their actions are contextual or must be reachable while the window is hidden.
- **The chat composer grows with the message.** It starts at one row, with the
  *Send* button the same height, and expands to at most six rows before scrolling.
  Unmodified Enter sends; Ctrl+Enter and Shift+Enter insert a newline. It remains
  enabled while the agent is working. The leading turn uses `agent:chat`; once
  the venue accepts it and returns the session id (before the model reply), any
  follow-ups submitted while that chat is in flight go immediately through
  `agent:message` to the same session. Brightside serialises only these fast
  intake calls to preserve click order — it never waits locally for the previous
  model cycle. Covia owns the durable pending queue and decides how the inputs
  are presented across cycles; a second concurrent `agent:chat` would violate
  its one-in-flight-chat-per-session contract and fail fast.
- **Brightside runs off the venue's live session state — no local transcript
  copy.** The venue is in-process, so agent-record reads go straight to the
  lattice with **no job**: `EmbeddedVenue.agentRecord(userDID, agentId)` calls
  `Engine.resolvePath("g/<agentId>", RequestContext.of(userDID))` — exactly what
  `v/ops/covia/read` does internally, minus the job machinery. On start,
  `startChat` reads the record that way to populate the saved-conversation list,
  but **Home is always a clean new chat**: it does not project or resume the
  newest session, and `ChatSession.sessionId` stays null until the first message
  is sent. Opening a saved conversation explicitly projects it with
  `SessionHistory.snapshotOf(record, sessionId)` and calls
  `ChatSession.resume(sessionId)`. The UI just reflects turns as they happen and
  the venue records them. An optimistic follow-up must not disappear when a
  watcher observes the necessarily incomplete stored conversation: a session is
  still active while its record has a non-empty `pending` vector or an `inCycle`
  claim, and `ChatPanel.refreshTo` defers replacement until both clear.
  It reads the `AgentState` schema directly (public field names) rather than the
  `agent:sessions` / `agent:session-read` projections: those are owner-callable
  since Covia 2026-08-24, but they are deliberately *safe* projections — they
  omit the caller's current/unfinished session, tool scratch and diagnostics,
  and bound the transcript — whereas the window needs the live session in full
  (the activity chips and the Cycle-detail tab are built from exactly what they
  leave out). `SessionHistory` still offers `Venue`-based reads
  (`loadLatest`/`load`/`listSessions`/`rawTurns`, via `covia:read`) for tests and
  any out-of-process client; the app prefers the in-process path. Actual
  *operations* (chat, rename/delete-session, `agent:context`) still go through
  the op/job path — only lattice *reads* are done directly.
- **Every past conversation is switchable.** The agent record holds many
  `sessions`; `SessionHistory.listSessions` enumerates them (newest first,
  titled by each one's first user message) for the `ConversationList` switcher,
  and `SessionHistory.load(agentId, sessionId)` / `snapshotOf(record, sessionId)`
  reopen a specific one. Entering *Home*, *New conversation* (the switcher
  button), *Settings → General → New chat*, and the platform New-chat shortcut
  reset the session and show a blank transcript;
  the next message mints a fresh session, which joins the switcher once that
  first message lands. `BrightSide.openSession` resumes a
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
- **Agents are rows; creation is a separate action.** The `AgentList` scroll area
  contains only agent rows. Its full-width *New agent* button is fixed beneath
  the list and opens `NewAgentPanel`, where the owner chooses a name, starting
  template and model. Provider/model selection is the shared `ModelSelector`
  component also used by onboarding and Settings — do not fork another picker.
  Row order is deterministic: the default agent stays first and every other
  agent is ordered by its immutable id; selection and activity never promote or
  otherwise move a row.
  The shared introduction skill respects the name/role supplied by the agent's
  system prompt; it must not force every named agent to call itself Brightside.
  Reopening a named agent preserves the configuration in its agent record.
- **Transcript items and tool activity.** `SessionHistory` projects the
  conversation into a list of `Item`s: `Message` (user / final-assistant text)
  and `Activity` (the intermediate "let me try…" narration and tool calls/
  results of a turn, grouped between question and answer). A tool `Step` carries
  both the call's arguments and its result — the arguments are captured from the
  assistant turn's `toolCalls` and matched to the result turn by `id`; the result
  reads `content` or, when structured, `structuredContent`. `ChatPanel` renders a
  `Message` as a `Bubble` and an `Activity` as an `ExpandableActivity` — a
  collapsed "N tool steps" chip; expanding it lists each tool as its **own**
  expandable row (tick/cross + name) that opens to show its Input and Result. So
  the final reply is what shows by default, with the tool use one or two clicks
  away; narration and results are selectable (`SelectableText`), and
  right-clicking a tool row offers *Copy input* / *Copy result*. Disclosure
  chevrons and the tick/cross are **painted** (`ChatIcons`), not glyphs, because
  the UI font (Lato) has no ▸/▾/✓/✕. New item kinds go here.
- **The context inspector — "what the assistant sees".** Right-clicking a
  conversation offers an inspector (`covia.brightside.ui.inspect.ContextInspector`,
  its own package to grow into) showing the *exact* model input for that session,
  read via `AgentContext` → `v/ops/agent/context` (owner-callable, `CRUD_READ`;
  it assembles the same `Spec` a live turn would, without calling the model).
  Tabs: Overview (model, byte budget, session token usage), Context (every
  assembled message — identity, the `[Skills]` index, pinned memory, each loaded
  skill body, the conversation), **Cycle detail** (`SessionHistory.rawTurns` —
  the raw, unprojected conversation the chat elides: interim assistant content,
  every tool call with its arguments and id, tool results, and per-turn metadata
  — source, finish reason, tokens, job), Tools (the offered palette with
  provenance and the unavailable ones), Skills (the loaded entries + accounting),
  and Raw. (This model config has no separate thinking field; "interim thinking"
  is the assistant's `content` on tool-calling turns.) Note
  session ids are bare hex (`toHexString()`, no `0x`) — the agent ops reject a
  `0x` prefix.
- **Change detection is a lattice value compare — in-process, no job.**
  `ConversationWatcher` takes a `Supplier<ACell>` (`() -> venue.agentRecord(…)`,
  an in-process lattice read) and every couple of seconds, while the chat is
  showing, compares it with `.equals` to the last one shown — lattice values are
  immutable and content-addressed, so an unchanged conversation is an equal value
  (a cheap hash compare, and crucially **not** a submitted job) and any change (a
  new turn, an edit from another client, an out-of-band agent update) is a
  different one. Polling this way is silent and near-free; do **not** reintroduce
  a `covia:read` job to poll a value the process already holds. Only then does it
  refresh, and
  `ChatPanel.refreshTo` re-renders only if the projected turns actually differ
  from what's on screen (so the app's own turns don't cause a redundant
  re-render). A successful local send also calls
  `BrightSide.conversationCommitted(sessionId)` directly and re-reads that exact
  session in-process. This deterministically adopts a newly minted session and
  reconciles tool activity; it must not depend on whether the watcher happened
  to poll before or after `ChatSession` received the id. *Settings → General →
  Refresh* and the platform Refresh shortcut force an immediate compare.
- **`BrightsideAdapter` is the venue extension.** A real Covia `AAdapter`
  (`covia.brightside.BrightsideAdapter`), registered on the embedded engine in
  `EmbeddedVenue.launch`. It owns `brightside:info`, the read-only dynamic
  `brightside:context` assembler, the caller-scoped
  `brightside:delete-skill` operation, the append-only
  `brightside:report-skill-feedback` operation and the operator-only shutdown
  operation.
  `BrightsideSkillsAdapter` separately installs the shipped skills under
  `v/skills/brightside/…`; keeping operations and skill assets in their owning
  adapters makes both lifecycles explicit. Add a Brightside operation as an
  `adapters/brightside/<op>.json` resource plus an `invokeFuture` case; add a
  shipped skill resource plus its path in `BrightsideSkillsAdapter.SHIPPED`.
- **Skills and memory, by namespace.** No shipped skill is pinned by default.
  `introduction`, `skills`, `conversations`, `lattice`, the work skills and the
  `vault-drives-files` / `diagnostics-audit-logs` / `harness` /
  `tasks-scheduler-automation` / `convex` routers are all selected on demand from
  precise descriptions; `introduction` tells the agent to unload
  itself after a one-shot greeting. The configured `systemPrompt` remains the
  assistant's identity and role. Dynamic owner/product facts (display name,
  authenticated user DID, actual model route, local-storage boundary and the
  skill-feedback rule) come from the read-only `brightside:context` operation,
  declared as a **non-skill** `config.loads` entry. Existing agents with legacy
  `identity`/`introduction`/`skills` pins are migrated by clearing and rebuilding
  `config.loads`; `agent:update` recursively merges nested maps, so merely
  omitting old keys would leave them behind. The migration preserves other
  owner-configured pins and installs the context-operation entry.
- **Tool-granting skills are loaded on demand, never pinned.** An effective
  skill load contributes its declared tools; Brightside therefore makes skills
  that grant tools (`conversations` → the read-only past-session tools
  `agent:sessions`/`agent:session-read`; `skill-authoring` → `covia:write` and
  the path-constrained `brightside:delete-skill`; the file and diagnostic
  children → their focused operation sets) **discoverable**, then leaves
  them for the agent to load when their descriptions match, rather than pinning
  them. The file router reveals separate `vault`, `dlfs` and `files` children;
  the diagnostic router reveals read-only `jobs`, `sessions` and
  `brightside-logs` children; the harness router reveals explanatory
  `covia-engine`, `etch` and `convex-lattice` children; `research` reveals an
  `http` child which grants outbound HTTP only with explicit untrusted-content
  guidance in context. That child includes a tested keyless keyword-search
  route using compact Bing RSS results; result links remain discovery leads,
  not sources in themselves. The `tasks-scheduler-automation` router contributes
  no tools itself and reveals Covia's existing `tasks`, `scheduling`,
  `orchestration` and `hitl` skills; load only the children required by the
  current request. The `convex` router is the owner's view of the Convex
  network and reveals topic children with their tools: `accounts`,
  `smart-contracts` and `cns` grant `convex:query` + `convex:transact`,
  `protonet` grants `convex:query`, `key-security` grants nothing (it is the
  rulebook loaded before anything signs — keys by secret reference, never a
  seed in a call or in chat), and `convex-lattice` is the **same resource** as
  the harness child installed at a second path, so content-identity dedup shows
  it once in the index. Their bodies are distilled from the Convex repository's
  own `.claude/skills` (account, transfer, transact, query, convex-lisp, deploy,
  token, trust, cns, juice, memory, protocol-versions) and use Convex's
  canonical terms — never "gas", "fees", "blockchain", "block" or "mainnet".
  Brightside does not yet create or link a Convex account for the owner; the
  wallet-grade adapter work is Covia issue #433. When the user asks "what did we
  discuss before?", the agent loads `conversations`, gets the session tools and
  reads its own history rather than claiming it cannot. Skill
  **descriptions are the trigger**: pack the words the user actually says
  ("past conversation sessions", "what we discussed", "history") into them.
- **Pinned-child upstream issue.** Covia issue #415 tracks that hand-written
  `config.loads` pins do not yet contribute their live `skill.skills` or
  `skill.skillsets` declarations to name resolution. Brightside declares
  `v/skills/brightside` as an explicit source for its top-level skills; child
  skills are revealed by parents loaded through the live skill mechanism, not
  hand-pinned loads. Bodies and tools remain unloaded until selected.
- **Discovery is broad, authority stays deliberate.** The agent's
  `config.skillsets` are `["w/skills", "v/skills/brightside",
  "v/skills/root"]` — its own skills, Brightside's everyday skills and the
  venue's shipped library. `v/skills/root` is the *usable* skillset level
  (the per-family entry routers — `covia`, `grid`, `agents`, `discovery`,
  `lattice`, `venue`, …, and adapter integrations once their modules are
  loaded); pointing at bare `v/skills` would be silently useless, as it holds
  skillsets, not skills (Covia issue #409). The agent sees the routers in its
  skills index and loads one to reveal and use that family's tools. Only
  `defaultTools` (read-only `covia read`/`list`), the memory tool and the narrow
  feedback reporter are always-on; every broader capability — writes, HTTP,
  files, agents — arrives
  by loading the skill that grants it, so the gated `skill-authoring` model
  (write stays out of context until deliberately loaded) still holds. Enabling
  the optional module adapters (Telegram, Discord, …) in an embedded venue is
  an upstream ask — Covia issue #410.
- **Self-authoring, gated hierarchically and reversible.** The on-demand `skills`
  meta-skill reveals `skill-authoring` as a sub-skill (its `skill.skills` facet), and
  `skill-authoring` is the only skill whose facet grants the `covia:write`
  tool. So the assistant can extend itself — it loads `skill-authoring` and
  writes a new skill into its own `w/skills` (a declared skillset, so authored
  skills become discoverable), refines it with another write, or removes it
  with `brightside:delete-skill`. That delete operation accepts only a validated
  skill name and hard-codes the target to the acting user's `w/skills/<name>`;
  it does not grant generic deletion over memory or other workspace data. These
  mutation capabilities are not in context until the agent deliberately loads
  the sub-skill.
- **Misses become a private backlog.** The dynamic `brightside:context` load
  tells the agent to call `brightside:report-skill-feedback` when a load fails,
  an expected skill is absent, or instructions contradict the live system. The operation
  accepts facts but no path or identity, derives provenance from the request,
  and writes one immutable entry at `w/skill-feedback/<job-id>`. It cannot write
  elsewhere. Ordinary user errors and expected task failures are not reports;
  the feedback operation must never report its own failure recursively.
- **Filesystem skills (agentskills.io).** `FilesystemSkills.sync` imports skills
  from `~/.brightside/skills/` (`AppConfig.skillsDir`) into the user's own
  `w/skills` on start — a folder with a `SKILL.md`, or a single `<name>.md`, in
  the open [agentskills.io](https://agentskills.io) format (YAML frontmatter with
  `name`/`description`, then a markdown body). No lossy translation: the raw
  `SKILL.md` is stored verbatim as the skill asset's `content.inline`, with
  `name`/`description` lifted to metadata — Covia's own resolver already strips
  the frontmatter at load and reads `tools`/`skills`/`skillsets` frontmatter into
  the skill facet (so an agentskills.io skill loads as instructions, and one that
  lists Covia op paths under `tools:` grants them). Written as the acting user via
  `covia:write` to `w/skills/<name>` (a non-destructive upsert, so it never
  clobbers agent-authored skills), so no config change is needed — the agent's
  existing `w/skills` skillset discovers them. Covia has no directory-bundle
  facet yet, so `scripts/`/`references/`/`assets/` are not imported; only the
  `SKILL.md` instructions are.
- **Memory and scratch** live in `n/`: `n/memory` (recall pinned as a
  `v/ops/memory` context entry, with the `v/ops/memory` tool so it can write)
  and other scratch. See `docs/DESIGN.md`.
- **File access is rooted and capability-gated.** Brightside's default file
  configuration exposes only `<home>/files` as the writable `files` root and
  `<home>/logs` as the server-enforced read-only `logs` root. File skills must
  call `file:roots` rather than assume other host paths exist. The personal
  vault and DLFS children use their own lattice-backed operations; the personal
  file vault is distinct from `identity.enc`, `keys.enc` and `vault.salt`.
- **Encrypted vault & identity.** One passphrase, hardened with **Argon2id**
  (`covia.brightside.vault.Vault`, BouncyCastle) over a per-vault `vault.salt`,
  yields a 32-byte passphrase key that AES-GCM-encrypts the Ed25519 seed in
  `identity.enc` and provider credentials in `keys.enc`. A domain-separated key
  derived from the identity seed encrypts the store (Etch v3, ChaCha20 — injected
  as `{seed, etch:{version:3,cipher,key}}` into the in-memory venue config, never
  persisted). There is no plaintext `venue.key`; the sole opt-in exception is
  "remember me", which openly stores the passphrase as `unlock.passphrase` and
  relies on the trusted OS account's filesystem permissions. The identity is a
  BIP39 recovery phrase (`Mnemonic`, Convex
  `BIP39`/`SLIP10`) → the same seed, independent of the passphrase. Model
  providers and their API-key secret names are `covia.brightside.model.Providers`
  (`v/models/<provider>/<id>`; `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY` for Gemini,
  …). The reviewer-facing security model, exact algorithms, key/file inventory
  and recovery runbook are in **[docs/SECURITY.md](docs/SECURITY.md)**; the full
  UI flow, screen mockups and threat model are in **`docs/ONBOARDING.md`**.
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
- **Mint a token** in *Settings → Auth*, choosing *User* (the default) or *Venue
  operator* (advanced). Both are signed by the venue; the choice controls the
  token subject. The user token lets standard clients see the same private data
  as the desktop chat. To mint a token with another subject or use another
  SDK/tool directly, reveal and copy the 32-byte
  Ed25519 seed from the passphrase-gated *Settings → Identity → Primary seed*
  control, then sign with the Covia/Convex SDK:
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
- **Branches:** `master` is the default/integration branch; `develop` is the
  day-to-day working branch. Commit dev work on `develop` and merge to `master`.
- Commit as the repo's local `user.name`/`user.email` (check
  `git config --local`); use `gh` as `brittleboye` for issues and PRs.
