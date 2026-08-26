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
├── Identity.java               the u:<slug> user + display name; identity.json
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
    ├── MainWindow.java         menus, cards (onboarding / unlock / name / chat)
    ├── WelcomePanel.java       "What should I call you?" (rename)
    ├── settings/               Model, Profile, Vault and Auth pages
    ├── TrayManager.java        best-effort system tray
    ├── Icons.java
    ├── onboarding/             OnboardingWizard, UnlockPanel, OnboardingUI
    ├── chat/                   ChatPanel, Bubble, MessageColumn, TypingIndicator,
    │                           ExpandableActivity, ConversationList,
    │                           SelectableText, ChatIcons, ChatStyle
    └── inspect/                ContextInspector — the exact model input

src/main/resources/
├── brightside/skills/*.json    shipped skills: introduction, skills,
│                               skill-authoring, conversations
├── adapters/brightside/        info.json, shutdown.json — the brightside:* operations
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

**Every conversation is switchable, and the watcher knows which one you are
looking at.** The agent record holds many sessions; the switcher enumerates them
newest-first. When a background update lands, the controller re-renders the
session on screen, not always the latest — a change elsewhere never yanks you
off the conversation you opened.

## Skills, tools and memory

Namespaces do the separating:

| Namespace | Purpose | Written by |
|---|---|---|
| `v/skills/brightside/…` | Brightside's shipped skills | the adapter, at venue launch |
| `v/skills/root` | the venue's own skill library | the venue |
| `w/skills` | the user's agent's own skills | the agent |
| `n/…` | private scratch, including `n/memory` | the agent |

**Discovery is broad; authority is deliberate.** The agent's skillsets are
`w/skills` and `v/skills/root`, so it can see the whole shipped library. But
only read-only tools and the memory tool are always on. A skill's tools reach
the palette only when the agent *loads* that skill — pinning a skill's body in
configuration does not grant its tools. So:

- `introduction` (persona) and `skills` (how it grows) are **pinned**; both are
  prose, neither grants tools.
- `conversations` grants read-only access to past sessions and is **revealed**
  by `introduction`, loaded on demand when you ask what you discussed before.
- `skill-authoring` is the only skill that grants `covia:write`, and it is
  gated as a sub-skill of `skills`. The agent can extend itself — but the write
  capability is not in context until it deliberately reaches for it.

Skill **descriptions are the trigger**, so they are written with the words a
person actually says.

**The persona is a skill, not a system prompt.** The prompt stays small;
behaviour lives in editable data.

## Packaging

The runnable jar is built by `maven-shade-plugin` with the services transformer
— Jetty, Javalin and LangChain4j rely on `META-INF/services` — and drops
`openapi-plugin/**` from `convex-restapi` so the venue serves its own OpenAPI
document. Logging is configured programmatically from `brightside/logback.xml`
because the venue jar ships a root `logback.xml` of its own.
