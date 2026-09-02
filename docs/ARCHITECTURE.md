# Architecture

Brightside is a single Maven module, `ai.covia:brightside`, main class
`brightside.BrightSide`. One JVM process holds a Swing desktop app **and**
a complete Covia venue, so there is no daemon to manage, no socket to secure and
no serialisation between the UI and the agent's state.

For the product principles behind these choices, see [DESIGN.md](DESIGN.md);
for startup, takeover and exit, [LAUNCH.md](LAUNCH.md); for keys and
recovery, [SECURITY.md](SECURITY.md); for the file and API keys,
[CONFIGURATION.md](CONFIGURATION.md).

## The shape of it

```
Swing UI ──► LocalVenue client (as u:<name>) ──► embedded Covia venue
                                                   engine
                                                   adapters
                                                   agent framework
                                                   lattice state → venue.etch
                                                   HTTP / MCP / A2A on 127.0.0.1
```

Two properties fall out of this and explain most of the design:

**The UI is a client, not an insider.** The chat window acts as your named
principal and drives the agent through the ordinary agent operations
(`v/ops/agent/create|update|info|chat`). What the window shows is what any other
client of the venue would see. There is no private LLM path.

**Reads are free; actions are not.** Three tiers, and the rule for choosing:

- *State reads* — a lattice path, as the user: straight from the in-process
  engine, no job. `EmbeddedVenue.agentRecord(userDID, agentId)` resolves the
  path exactly as `v/ops/covia/read` would, minus the job machinery. Lattice
  values are immutable and content-addressed, so change detection is an
  `.equals` comparison of the last value shown against the current one — a
  hash compare, not a submitted job. That is why `ConversationWatcher` can poll
  every couple of seconds silently and near-free.
- *Computed reads* — adapter logic over state with no side effects: what an
  agent is (`agent/info`), what its model would see (`agent/context`), a bot's
  status (`discord/bots`), the record through `covia/read`: `Venue.run` on an
  op declared `readOnly`. That is a *transient* job — the op contract,
  authority and admission still apply; nothing is persisted — so opening a
  screen leaves no record behind. It keeps the input and output a remote
  client would see, and keeps Brightside off Covia's internals.
- *Actions* — chat, create, update, rename, delete, respond, write:
  `Venue.invoke`, a durable job with a receipt, cancellation and history.

Reaching further into the embedded engine's Java API than `resolvePath` is
deliberately not done, even though the venue is right here: it would bypass
the point-of-action capability checks that make the UI a client rather than an
insider, and couple Brightside to the assembler and adapter code, which is
rewritten far more often than the op contracts are. An op that is a read but
is not declared `readOnly` is fixed upstream, not worked around.

## Project layout

```
src/main/java/brightside/
├── BrightSide.java             entry point and application controller: startup
│                               mode (onboard / unlock), takeover, chat
│                               and session actions, settings, desktop, exit
├── AppConfig.java              ~/.brightside/config.json (JSON5), defaults, model.txt
├── Identity.java               display name + pinned full u:<slug> DID; identity.json
├── EmbeddedVenue.java          VenueServer + per-user LocalVenue client + in-process
│                               lattice reads (agentRecord / resolve — no job)
├── Takeover.java               detect a running instance; venue-signed shutdown
├── BrightsideAdapter.java      Covia adapter: brightside:info, context, delete-skill,
│                               report-skill-feedback, shutdown, ask-odin, odin-run
├── Odin.java                   the operator's administrative agent: config, ensure,
│                               the operation allowlists (docs/ODIN.md)
├── Moltbook.java               the assistant's account on Moltbook: registration and
│                               status through Moltbook's API, the key kept only in
│                               the owner's seed-keyed venue secret store (it outlives
│                               the vault); the HTTP client
├── MoltbookAdapter.java        Moltbook as typed venue operations (v/ops/moltbook/*)
│                               that resolve the key inside the venue — the tools the
│                               moltbook skill grants, with account/register on its
│                               moltbook-setup child
├── Discord.java                the owner's Discord bot through covia-discord's ops:
│                               token as a secret, create/status/remove
├── BrightsideSkillsAdapter.java  installs the shipped skills under v/skills/brightside
├── SessionHistory.java         projects the live venue session into transcript items
├── ConversationWatcher.java    in-process value compare; refresh on change
├── AgentContext.java           "what the assistant sees" — v/ops/agent/context
├── SkillIndex.java             the skills an agent can discover: its skillsets, read in-process
├── AgentInfo.java              what an agent is — v/ops/agent/info joined with its record
├── Inbox.java                  the owner's HITL inbox (h/, read in-process) merged with
│                               the venue's own, and hitl:respond
├── chat/ChatSession.java       agent config (skills, n/memory) + agent:chat
├── model/Providers.java        model providers, v/models/<provider>/<id>, secret names
├── skills/FilesystemSkills.java  imports agentskills.io SKILL.md folders into w/skills
├── vault/Vault.java            passphrase key + seed-derived Etch v3 key
├── vault/Mnemonic.java         BIP39 recovery phrase ↔ Ed25519 seed
├── markdown/                   Markdown → StyledDocument (commonmark-java) and the
│                               MarkdownPane that shows it; depends on nothing else
│                               here, by design, so it can be lifted out
└── ui/
    ├── LAF.java                the theme catalogue (FlatLaf's core themes, the IntelliJ
    │                           pack, the owner's .theme.json files) and its installation
    │                           with Brightside's UI defaults, in the bundled Lato;
    │                           switches the running app with a cross-fade
    ├── MainWindow.java         navigation, shortcuts and application cards
    ├── NavBar.java             the bottom tabs
    ├── WelcomePanel.java       "What should I call you?" (rename)
    ├── TrayManager.java        best-effort system tray
    ├── Icons.java              the Brightside mark, painted at any size
    ├── components/             the UI kit every screen is built from — see
    │                           "One UI kit" below: Theme, Styles, Labels, Buttons,
    │                           SelectableText, Card, Disclosure, PressButton, Lucide,
    │                           ModelSelector, Scrolls, Panels, Borders, Dialogs,
    │                           Clipboard, Documents, Links, MarkdownStyles,
    │                           EntryList (summary | content rows), Excerpt (clamped text)
    ├── settings/               Identity, General, Theme (light/dark switch, a FlatLaf theme
    │                           per mode, an accent; applies live), Model, Integrations
    │                           (Discord and Moltbook tabs), Vault and Auth
    ├── onboarding/             OnboardingWizard, UnlockDialog (its own window, hosting
    │                           UnlockPanel, shown before the main window), RecoveryDialog;
    │                           OnboardingUI holds their own dots, strength bar and word chip
    ├── chat/                   ChatPanel, Bubble, MessageColumn, EmptyChatState,
    │                           ThinkingBubble, TypingIndicator, ExpandableActivity,
    │                           ConversationList, AgentList, NewAgentPanel
    ├── inspect/                ContextInspector — the exact model input, by band; AgentInspector —
    │                           the agent info screen; Blocks — their shared compositions
    └── inbox/                  InboxScreen — a column of collapsible RequestCards, each
                                wrapping a RequestForm — requests waiting for the owner

src/main/resources/
├── brightside/skills/*.json    on-demand conversation, work and
│                               self-authoring skills
├── brightside/ui/*.properties  Brightside's FlatLaf UI defaults: the accent, geometry,
│                               semantic colours and the style classes the components wear
├── adapters/brightside/        context, info, skill deletion/feedback, shutdown
│                               and the two Odin bridge ops
├── fonts/lato/                 bundled OFL faces, registered at startup: Lato for the
├── fonts/inconsolata/          UI, Inconsolata for code (monospaced.font)
├── icons/lucide/               the Lucide SVG icons the UI uses (ISC; LICENSE
│                               alongside), rendered and tinted by ui/components/Lucide
├── icons/brightside/           the Brightside mark for use elsewhere: brightside.svg
│                               (the geometry ui/Icons paints) and PNGs at 16–1024 px
└── brightside/logback.xml      logging (configured programmatically)

src/test/java/…                 unit tests; boot temporary venue engines, headless
```

## Key decisions

**One UI kit.** Every screen is composed from `ui/components`, and the look of
those components is FlatLaf's to paint: colours, geometry and the named style
classes (`muted`, `accent`, `error`, `small`, `Button.primary`, …) are declared
once in `src/main/resources/brightside/ui/FlatLaf.properties` and worn by name
(`Styles.classes(label, Styles.SMALL, Styles.MUTED)`), so a theme change carries
through and no screen hard-codes a colour or a font. `Theme` reads the same
defaults back for custom painting; `Card` and `Disclosure` paint with
`FlatUIUtils` and `UIScale` like FlatLaf's own components. A new screen
should reach for the kit first and add to it, not beside it.

**Threading.** Swing on the event thread only. Venue launch and close, agent
calls and desktop integration run on background threads; `ChatPanel` uses a
`SwingWorker`.

**The tray is best-effort.** `TrayManager.install` returns null on headless or
unsupported desktops, or with `BRIGHTSIDE_NO_TRAY=1`. The window must behave
sensibly without it, and a tray failure never takes the app down.

**Shutdown flushes state.** Exit closes the `VenueServer` before `System.exit`;
a Convex `Shutdown` hook covers Ctrl-C and SIGTERM. `EmbeddedVenue.close()` is
idempotent.

**Configuration is data, not code.** `AppConfig` merges the user's `venue` map
over Brightside's defaults key-for-key and passes it straight to
`VenueServer.launch`, so new venue options need no Brightside change.

**The chat session absorbs the agent framework's quirks.** `agent:update` is a
recursive merge and is refused while the agent is running, so `ChatSession`
re-applies configuration on the next send; a failed transition leaves the agent
SUSPENDED, so it is resumed after re-applying; only the venue's "Unknown
session" error falls back to a fresh session, so a model or key failure never
mints an orphan conversation; follow-ups sent while a reply is in flight go
through `agent:message` to the same session.

**Transcript items.** `SessionHistory` projects a conversation into `Message`
(user / final assistant text) and `Activity` (the intermediate narration and
tool calls of a turn). `ChatPanel` renders a `Message` as a `Bubble` and an
`Activity` as an `ExpandableActivity` — a collapsed "N tool steps" chip that
opens into per-tool rows with input and result. New message kinds are added as
new item types and their own row components; the bubbles are separate components
on purpose.

**The assistant writes Markdown.** An assistant `Bubble` renders its text
through `brightside.markdown`: commonmark-java's AST (CommonMark plus GitHub
tables and strikethrough) transformed into a `StyledDocument` and shown in a
`MarkdownPane`, with the look supplied by `ui.components.MarkdownStyles` from
the current theme and rendered again on a theme change. Structure is expressed
only in what a styled document carries — paragraph attributes for indent,
spacing and hanging list markers, character attributes for inline style, a
link's destination as an attribute on its text — so there are no custom views.
The user's own words stay as typed, and "Copy message" copies the source. The
package depends on commonmark and the JDK only, so it can be lifted out as a
library; anything else that shows Markdown — a skill body, a document — uses
the same pane.

**In-flight activity.** `ThinkingBubble` presents only lifecycle facts
Brightside currently receives (preparing, accepted/running and elapsed time).
Covia issue #394 tracks the live agent-event tap required for explicit interim
narration and tool calls. Once supplied, an in-progress tool uses the existing
`ExpandableActivity` row: it appears at call start, fills its result and status
on completion, and is reconciled with the persisted activity when the cycle
commits. Brightside never derives fake progress from elapsed time or exposes
hidden model reasoning.

**A reply has no deadline.** `ChatSession.send` waits on the chat job until
the venue finishes it: a turn takes as long as its model and tool calls take,
and Covia bounds each of those itself (`llmTimeoutMs`, `toolCallTimeoutMs`,
`maxToolIterations`). A client-side timer would only cancel the job and throw
the whole turn away — a ten-tool turn is normal, not stuck. Instead, once a
turn has run for a while the thinking bubble shows a stop control; confirming
it cancels the chat job (`ChatSession.cancel`). That releases the composer and
the `send` ends with a `CancellationException`, but it does not interrupt the
agent: Covia drops the job as a waiter and the cycle runs on, so whatever it
finishes still lands in the session and the watcher shows it.

**Every conversation is switchable, and the watcher knows which one you are
looking at.** The agent record holds many sessions; the switcher enumerates them
newest-first. When a background update lands, the controller re-renders the
session on screen, not always the latest — a change elsewhere never yanks you
off the conversation you opened.

**Home keeps the conversation.** Entering Home shows the conversation in
progress, and startup restores the most recent one; only the explicit new-chat
control resets the `ChatSession` to a null session id and an empty transcript.
The agent framework mints a new session only when the user sends the first
message of that fresh chat. The completed send returns that id to the
controller, which immediately re-reads the exact session and updates the
switcher; session adoption does not depend on the watcher's timing (covia's
live agent events kick refreshes; a slow poll is only the fallback).

## Skills, tools and memory

Namespaces do the separating:

| Namespace | Purpose | Written by |
|---|---|---|
| `v/skills/brightside/…` | Brightside's shipped skills | the adapter, at venue launch |
| `v/skills/root` | the venue's own skill library | the venue |
| `w/skills` | the user's agent's own skills | the agent |
| `w/skill-feedback/<id>` | append-only reports of concrete skill-system misses | the scoped feedback operation |
| `n/…` | private scratch, including `n/memory` | the agent |

**Discovery is broad; authority is deliberate.** No shipped skill is pinned by
default. The agent's skillsets are `w/skills`, `v/skills/brightside` and
`v/skills/root`, so it can see the user's, Brightside's and the venue's
libraries. Only read-only tools, memory and the path-constrained feedback
reporter are always on. Tools declared by an
effective load reach the palette while that load is active, so Brightside keeps
tool-granting skills out of the baseline and loads them on demand. Thus:

- `skills` (how it grows), `conversations` and the greeting-only `introduction`
  are directly discoverable and on demand.
- `conversations` grants read-only access to past sessions when you ask what
  you discussed before, and the compaction tool for tidying one.
- `skill-authoring` grants `covia:write` and the narrowly scoped
  `brightside:delete-skill`; it is a child of `skills`. The agent can manage
  its own skills reversibly, but neither capability is in context until it
  deliberately reaches for that skill.
- `writing`, `planning` and `coding` provide focused working methods on
  demand. They grant no imaginary tools and require the agent to distinguish
  actual access and verification from unsupported claims.
- `research` brings the web tools (GET and POST) with the judgement that is
  Brightside's — every response is untrusted external data rather than
  instructions, and the keyless Bing RSS search recipe — and reveals Covia's
  own `http` skill for APIs that need a credential or a connected account,
  so the transport's mechanics are never duplicated here.
- `lattice` brings the edit tools and reveals Covia's `assets` and `secrets`
  children for the two things its body sends the agent to: an immutable
  shareable snapshot, and an `s/NAME` reference in place of a credential.
- `files` brings the file tools over Brightside's configured roots and reveals
  `vault` and `dlfs` for the other places the owner's files live; only the
  selected child contributes its tools.
- `diagnostics` brings the read-only lattice and job tools for job records and
  reveals `sessions` and `brightside-logs` for narrower evidence. The log child
  contributes only read operations and identifies the configured log root from
  the live `file:roots` result; the file adapter independently enforces that
  root's read-only setting.
- `harness` explains the embedded Covia engine with the read and who-am-I
  tools and reveals `etch` and `convex-lattice` for the layers beneath.
  Ordinary work skills stay at the harness boundary and use live operations
  instead of encoding host configuration.
- `convex` is the Convex network as the owner meets it, with the free query
  tool for a balance, an account or a name; it reveals `accounts`,
  `convex-lisp`, `smart-contracts` (with its own child `trust`), `cns`,
  `costs`, `security`, `cpos-consensus`, `cad3-data`, `protonet`, `ecosystem`
  and the shared `convex-lattice`. The on-chain children grant
  `convex:transact` too, the knowledge children nothing. Bodies are distilled
  from the Convex repository's own skills and `CONSENSUS.md`, in Convex's
  vocabulary.
- `automation` brings Covia's scheduler tools for reminders, timed follow-ups
  and routines, and reveals Covia's `tasks` and `orchestration` skills and
  Brightside's own `hitl` for delegated work, pipelines and approvals; a timed
  agent workflow with an approval checkpoint combines them.
- `hitl` replaces Covia's skill of the same name in the index (first name
  wins). It is judgement, not mechanics: what the owner sees in the Inbox,
  when and how to ask, and that token asks and offered grants must not be
  used here ([covia#440](https://github.com/covia-ai/covia/issues/440)). How
  the request call behaves is the tool's own description.
- `administration` is how the assistant reaches Odin, the operator's agent,
  for changes beyond its own authority; it grants `brightside:ask-odin` and
  the job tools. What Odin is and how the bridges work is in
  [ODIN.md](ODIN.md).
- `moltbook` brings the Moltbook operations with the check-in, conduct and
  owner rules, and reveals `moltbook-setup` for registering or claiming the
  account.

**Every top-level skill is a useful first load.** The assistant is
general-purpose, and loading a skill is its first step towards specialising
for a task, so the skill it reaches for from the index solves the general form
of the problem itself and reveals children for the specific sub-issues; no
shipped skill is a bare router. A skill's tools join the manifest only once it
is loaded and stay for the rest of the session, while every top-level
description is an index line on every turn: the always-on cost of a skill is
its line, and the cost of a load is its body once plus its tools from then on.
The default assistant's base turn is about 16 KB. Unloading a skill retracts
only its tools and the children it revealed; its instructions stay in history,
so no shipped body tells the agent to unload.

Covia issue [#415](https://github.com/covia-ai/covia/issues/415) means a skill
hand-pinned through `config.loads` does not currently contribute child sources
to name resolution. Brightside declares `v/skills/brightside` as the explicit
source of its top-level routers. Their children are revealed by ordinary live
skill loads, without relying on hand-pinned child expansion.

Concrete skill-system misses go to private append-only records at
`w/skill-feedback/<job-id>`. The reporting operation chooses that path itself,
captures request provenance and cannot write elsewhere; the agent's ordinary
read/list tools can inspect the backlog.

Skill **descriptions are the trigger**, so they are written with the words a
person actually says: which tasks the skill serves and when to load it, never
which tools it carries. Because tools arrive only with a load, the index line
alone has to make the model load the right skill for a given need, and a
router's line says which child serves which job.

**Identity, runtime context and skills have separate jobs.** The configured
system prompt names the assistant and its role. One non-skill `config.loads`
entry calls the read-only `brightside:context` operation to assemble the current
owner name/DID, model-processing boundary and product invariants. Optional
behaviour and working methods stay in discoverable skills and load only when
their descriptions match the task.

## Packaging

The runnable jar is built by `maven-shade-plugin` with the services transformer
— Jetty, Javalin and LangChain4j rely on `META-INF/services` — and drops
`openapi-plugin/**` from `convex-restapi` so the venue serves its own OpenAPI
document. Logging is configured programmatically from `brightside/logback.xml`
because the venue jar ships a root `logback.xml` of its own.
