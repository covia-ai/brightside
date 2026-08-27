# Architecture

Brightside is a single Maven module, `ai.covia:brightside`, main class
`covia.brightside.BrightSide`. One JVM process holds a Swing desktop app **and**
a complete Covia venue, so there is no daemon to manage, no socket to secure and
no serialisation between the UI and the agent's state.

For the product principles behind these choices, see [DESIGN.md](DESIGN.md).
For the full working rules — the ones a contributor or a coding agent needs —
see [AGENTS.md](../AGENTS.md).

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

**Reads are free; operations are not.** Because the venue is in-process, reading
the agent record goes straight to the lattice with no job:
`EmbeddedVenue.agentRecord(userDID, agentId)` resolves the path exactly as
`v/ops/covia/read` would, minus the job machinery. Lattice values are immutable
and content-addressed, so change detection is an `.equals` comparison of the
last value shown against the current one — a hash compare, not a submitted job.
That is why `ConversationWatcher` can poll every couple of seconds silently and
near-free. Actual *operations* — chat, rename, delete, context assembly — still
go through the op/job path.

## Project layout

```
src/main/java/covia/brightside/
├── BrightSide.java             entry point and application controller: startup
│                               mode (onboard / unlock), takeover, chat
│                               and session actions, settings, desktop, exit
├── AppConfig.java              ~/.brightside/config.json (JSON5), defaults, model.txt
├── Identity.java               display name + pinned full u:<slug> DID; identity.json
├── EmbeddedVenue.java          VenueServer + per-user LocalVenue client + in-process
│                               lattice reads (agentRecord / resolve — no job)
├── Takeover.java               detect a running instance; venue-signed shutdown
├── BrightsideAdapter.java      Covia adapter: brightside:info, brightside:shutdown
├── BrightsideSkillsAdapter.java  installs the shipped skills under v/skills/brightside
├── SessionHistory.java         projects the live venue session into transcript items
├── ConversationWatcher.java    in-process value compare; refresh on change
├── AgentContext.java           "what the assistant sees" — v/ops/agent/context
├── chat/ChatSession.java       agent config (skills, n/memory) + agent:chat
├── model/Providers.java        model providers, v/models/<provider>/<id>, secret names
├── skills/FilesystemSkills.java  imports agentskills.io SKILL.md folders into w/skills
├── vault/Vault.java            passphrase key + seed-derived Etch v3 key
├── vault/Mnemonic.java         BIP39 recovery phrase ↔ Ed25519 seed
└── ui/
    ├── LAF.java                FlatLaf themes, purple accent, bundled Lato
    ├── MainWindow.java         navigation, shortcuts and application cards
    ├── WelcomePanel.java       "What should I call you?" (rename)
    ├── ModelSelector.java      shared provider/model picker
    ├── settings/               General, Model, Identity, Vault and Auth pages
    ├── TrayManager.java        best-effort system tray
    ├── Icons.java
    ├── onboarding/             OnboardingWizard, UnlockPanel, OnboardingUI
    ├── chat/                   ChatPanel, Bubble, MessageColumn, EmptyChatState,
    │                           ThinkingBubble, TypingIndicator, ExpandableActivity, ConversationList,
    │                           SelectableText, ChatIcons, ChatStyle
    └── inspect/                ContextInspector — the exact model input

src/main/resources/
├── brightside/skills/*.json    on-demand conversation, work and
│                               self-authoring skills
├── adapters/brightside/        context, info, skill deletion/feedback and
│                               shutdown ops
├── fonts/lato/                 bundled OFL faces, registered at startup
└── brightside/logback.xml      logging (configured programmatically)

src/test/java/…                 unit tests; boot temporary venue engines, headless
```

## Key decisions

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

**Transcript items.** `SessionHistory` projects a conversation into `Message`
(user / final assistant text) and `Activity` (the intermediate narration and
tool calls of a turn). `ChatPanel` renders a `Message` as a `Bubble` and an
`Activity` as an `ExpandableActivity` — a collapsed "N tool steps" chip that
opens into per-tool rows with input and result. New message kinds are added as
new item types and their own row components; the bubbles are separate components
on purpose.

**In-flight activity.** `ThinkingBubble` presents only lifecycle facts
Brightside currently receives (preparing, accepted/running and elapsed time).
Covia issue #394 tracks the live agent-event tap required for explicit interim
narration and tool calls. Once supplied, an in-progress tool uses the existing
`ExpandableActivity` row: it appears at call start, fills its result and status
on completion, and is reconciled with the persisted activity when the cycle
commits. Brightside never derives fake progress from elapsed time or exposes
hidden model reasoning.

**Every conversation is switchable, and the watcher knows which one you are
looking at.** The agent record holds many sessions; the switcher enumerates them
newest-first. When a background update lands, the controller re-renders the
session on screen, not always the latest — a change elsewhere never yanks you
off the conversation you opened.

**Home is an uncommitted new chat.** Startup and entering Home reset the
`ChatSession` to a null session id and show an empty transcript. Existing
sessions are still listed under Sessions, but none is resumed implicitly. The
agent framework mints the new session only when the user sends the first message.
The completed send returns that id to the controller, which immediately re-reads
the exact session and updates the switcher; session adoption does not depend on
the polling watcher's timing.

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
default. The agent's skillsets are
`w/skills`, `v/skills/brightside` and `v/skills/root`, so it can see the user's,
Brightside's and the venue's shipped libraries. Only read-only tools, memory and
the path-constrained feedback reporter are always on. Tools declared by an
effective load reach the palette while that load is active, so Brightside keeps
tool-granting skills out of the baseline and loads them on demand. Thus:

- `skills` (how it grows), `conversations` and the greeting-only `introduction`
  are directly discoverable and on demand; `introduction` explicitly unloads
  itself after a one-shot greeting.
- `conversations` grants read-only access to past sessions when you ask what
  you discussed before.
- `skill-authoring` grants `covia:write` and the narrowly scoped
  `brightside:delete-skill`; it is gated as a sub-skill of `skills`. The agent
  can manage its own skills reversibly, but neither capability is in context
  until it deliberately reaches for that skill.
- `writing`, `planning`, `research` and `coding` provide focused working methods
  on demand. They grant no imaginary tools and require the agent to distinguish
  actual access and verification from unsupported claims.
- `research` reveals an `http` child for web searches, page retrieval and API
  queries. Its GET/POST tools arrive with instructions that treat every response
  as untrusted external data rather than model instructions. A compact Bing RSS
  query provides keyless keyword discovery; the agent then fetches and cites the
  original result pages.
- `vault-drives-files` reveals separate `vault`, `dlfs` and `files` children;
  only the selected storage child contributes its management tools.
- `diagnostics-audit-logs` reveals read-only job, session and Brightside-log
  children. The log child contributes only read operations and identifies the
  configured log root from the live `file:roots` result; the file adapter
  independently enforces that root's read-only setting.
- `harness` reveals `covia-engine`, `etch` and `convex-lattice` children for
  internal architecture questions. Ordinary work skills stay at the harness
  boundary and use live operations instead of encoding host configuration.
- `tasks-scheduler-automation` is a tool-free router over Covia's existing
  `tasks`, `scheduling`, `orchestration` and `hitl` skills. It loads only the
  parts a request needs: for example, a reminder needs scheduling alone, while
  a timed agent workflow with an approval checkpoint combines all four.

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
person actually says.

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
