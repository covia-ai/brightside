<div align="center">

# ☀️ Brightside

**Your own agent. On your own machine. Under your own identity.**

*And it writes its own skills.*

[![Licence: EPL 2.0](https://img.shields.io/badge/licence-EPL--2.0-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)
[![Built on Covia](https://img.shields.io/badge/built%20on-Covia%20Grid-7c4dff.svg)](https://covia.ai)
[![Status: early preview](https://img.shields.io/badge/status-early%20preview-yellow.svg)](#roadmap)

</div>

---

Brightside is a **self-sovereign personal agent**: a capable assistant that runs
privately on your own computer, under an identity that belongs to you. There is
no account to create, no server to trust, no telemetry. Your conversations, your
agent's memory and every skill it learns live in a folder on your disk that you
can read, back up or delete.

It is not a wrapper around a chat box. Brightside runs a full
[Covia](https://covia.ai) venue — engine, tools, lattice-backed state, an agent
framework and an MCP/A2A/HTTP surface — *inside its own process*, and puts a
quiet desktop app in front of it. That is what lets a personal agent do the
things personal agents are supposed to do: remember you, reach for real tools,
and **grow new abilities it writes for itself**.

<div align="center">

<!-- Drop a screenshot at docs/images/screenshot.png -->
<img src="docs/images/screenshot.png" alt="Brightside chat window" width="820">

</div>

## Why Brightside

**It's yours.** Your agent is a principal on your own venue — `u:<your name>` —
and it answers to that identity alone. State lives under `~/.brightside/`. The
venue binds to loopback only. The only thing that leaves your machine is the
model call you asked for, to the provider whose key you supplied.

**It grows.** Most assistants are frozen at the shape their vendor shipped.
Brightside's optional abilities are **Covia skills** — data, not code — and the agent can
create, refine and remove them in its own `w/skills` namespace. Ask it to learn how you like
your weekly report written, and it can capture that as a skill it loads next
time. Its configured identity stays separate, and no shipped skill is pinned by
default: descriptions determine what loads for the task. You can also hand it skills
as **files**: drop an [agentskills.io](https://agentskills.io) `SKILL.md` into
`~/.brightside/skills/` and it's imported on the next start — the same open,
portable format Claude Code and a dozen other agents use.

**Power stays gated, not absent.** Only read-only tools and memory are always
on. Writing, HTTP, files, other agents — each arrives by loading the skill that
grants it, so capability is deliberate rather than ambient. The skill that
grants the write tool is itself gated behind the skill that explains growing.

**It remembers.** A private memory (`n/memory`) persists across conversations,
so it feels like *your* assistant instead of a stranger every morning. Every
past conversation stays switchable, searchable by the agent itself, and
inspectable down to the exact bytes the model saw.

**No jargon in the way.** The everyday screens say "your assistant", "your
name", "memory". The venue, the DID, the dashboard, the API docs and the raw
model context are all still there — one click away, under **Advanced**.

## Quick start

You need **Java 21+** and **Maven 3.7+**.

```bash
git clone https://github.com/covia-ai/brightside.git
cd brightside
export ANTHROPIC_API_KEY=sk-ant-...     # or store it in the venue's secret store
mvn package
java -jar target/brightside.jar
```

On first launch Brightside asks *"What should I call you?"* — that is the whole
of onboarding. Type, press **Enter**, and you are talking to your agent.

No API key to hand? Set `"llmOperation": "v/test/ops/llm"` in
`~/.brightside/config.json` for an offline echo bot, enough to see the app work
end to end.

<details>
<summary><b>Building against a local Covia checkout</b></summary>

Brightside depends on the Covia `0.9.5` release, which resolves from Maven
Central. To build against local checkouts instead — Covia depends on Convex, so
Convex first:

```bash
cd ../convex && mvn clean install -DskipTests
cd ../covia  && mvn clean install -DskipTests
```

If the build cannot resolve `ai.covia:venue`, that is why.
</details>

## Highlights

- **Runs entirely in one process** — embedded Covia engine, every built-in
  adapter, and an MCP endpoint at `http://127.0.0.1:8085/mcp` that other tools
  can talk to
- **Self-authoring skills** — a gated `skill-authoring` ability lets the agent
  create, refine and remove skills in `w/skills`, where they become discoverable
  to itself; removal uses a path-constrained Brightside operation rather than
  general workspace deletion
- **Useful work skills** — writing, planning, research and coding guidance loads
  only when relevant; a separate greeting skill no longer occupies every turn
- **A feedback loop** — concrete skill-loading and instruction misses append to
  a private `w/skill-feedback` backlog through a path-constrained operation
- **Filesystem skills** — drop an [agentskills.io](https://agentskills.io)
  `SKILL.md` folder (or a single `.md`) into `~/.brightside/skills/` and it's
  imported into your agent's `w/skills` on start; portable, editable, shareable
- **Persistent memory** — `n/memory`, kept quietly, across every conversation
- **Every conversation, switchable** — a sidebar of past sessions with rename,
  delete and copy-transcript; the agent can read its own history when you ask
  what you discussed last week
- **Tool use in the open, but out of the way** — the final reply is what you
  see; one click expands each tool call with its input and result
- **"What the assistant sees"** — a context inspector showing the exact model
  input for a session: assembled messages, pinned memory, loaded skill bodies,
  the tool palette, raw within-cycle turns, token accounting
- **Live state, no polling jobs** — the app compares immutable lattice values
  in-process to notice changes, so refresh is near-free and silent
- **A proper desktop app** — [FlatLaf](https://www.formdev.com/flatlaf/)
  macOS-style themes, bundled Lato, rounded chat bubbles, dark by default; it
  can hide to the system tray and keep running
- **Configuration is data** — an empty `{}` config is valid; every venue option
  Covia understands can be set without a Brightside change

## How it works

```
┌──────────────────────────────────────────────┐
│  Brightside (one JVM process)                │
│                                              │
│   Swing UI  ──►  LocalVenue client           │
│                     │  as u:<your name>      │
│                     ▼                        │
│   ┌────────────────────────────────────────┐ │
│   │  Embedded Covia venue                  │ │
│   │  engine · adapters · agent framework   │ │
│   │  lattice state → ~/.brightside/…etch   │ │
│   │  HTTP/MCP/A2A on 127.0.0.1             │ │
│   └────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
                     │
                     ▼  only your model calls
              your LLM provider
```

The chat window is a *client* of the venue, not a privileged insider: it acts as
your named principal and goes through the same agent operations any other client
would. Anything the window can do, an MCP client on your machine can do too.

Namespaces do the separating: `v/skills/brightside/…` holds the skills
Brightside ships, `w/skills` is where your agent writes its own, and `n/` is
private scratch including memory. Only the venue writes `v/`.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the project layout and the
design rules behind it, [docs/DESIGN.md](docs/DESIGN.md) for the product
principles, and [docs/NETWORK.md](docs/NETWORK.md) for where this is going once
venues meet other venues.

## Using it

| | |
|---|---|
| **Enter** / **Shift+Enter** | send / newline |
| **File → New chat** | start a fresh conversation |
| **File → Change my name…** | change how the assistant addresses you |
| **File → Refresh** | force an immediate state compare |
| **Advanced → Open dashboard in browser** | the venue's web UI, `/swagger`, MCP endpoint |
| **Advanced → Open settings file / logs folder** | `config.json`, `~/.brightside/logs/` |
| **Help → About** | local address and your technical identity |
| Right-click a message | copy the message or the whole conversation |
| Right-click a conversation | open, rename, copy transcript, delete, inspect context |

Minimising goes to the taskbar like any window. **Hide to tray** (Settings →
General, or the hide shortcut) keeps the agent running out of sight; Settings →
General can also send the window to the tray on close or on minimise if you
prefer. By default closing quits, and **Quit** always stops it and flushes state.
Without a system tray (or with `BRIGHTSIDE_NO_TRAY=1`) there is nowhere to hide
to, so closing the window quits.

## Configuration

On first launch Brightside writes `~/.brightside/config.json` — JSON5, with
comments — and **every key in it is optional**:

```json5
{
  "theme": "dark",                      // or "light"
  "venue": { "name": "Brightside Venue", "port": 8085 },
  "chat": {
    "agentId": "brightside",
    "llmOperation": "v/models/anthropic/claude-sonnet-5",
    "timeout": 120
  }
}
```

Edit it and restart. The full reference — every venue key, the secret store, the
agent config, where state and keys live and how to reset them — is in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## FAQ

**Which models can I use?** Any model operation the embedded venue exposes.
The default is Anthropic's Claude via `v/models/anthropic/claude-sonnet-5`;
change `chat.llmOperation` to point at another. The venue's adapters are what
determine the menu, not Brightside.

**Does it phone home?** No. Brightside has no telemetry, no accounts and no
Covia-hosted dependency. The venue binds to `127.0.0.1`. The only outbound
traffic is the model call you configured, to the provider whose key you gave it.

**Where is my data?** `~/.brightside/` — `venue.etch` (the encrypted lattice
store, which holds conversations, memory and skills), `identity.enc` (the
encrypted venue identity), `keys.enc` (encrypted provider credentials),
`identity.json` (your name), `config.json` and `logs/`. Back the whole folder up;
delete the whole folder to start over completely. Brightside never creates a
plaintext `venue.key`.

**Can it run fully offline?** The app, the venue, your memory and your skills
are all local and work with no network. The *model* is the exception: point
`chat.llmOperation` at a local model operation to close that last gap, or at
`v/test/ops/llm` for an echo bot.

**Is my agent reachable from other tools?** Yes — that is rather the point. The
venue exposes HTTP, MCP and A2A on loopback. See *Reaching the venue from other
tools* in [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for how to mint a token
and act as your own user.

**Is this production-ready?** Not yet — see the status badge. It is an early
preview: usable every day, still moving quickly, and pre-1.0 in the way that
implies.

## Roadmap

- **Federation** — the point of a venue is that it can meet other venues.
  Sharing a skill, delegating to a friend's agent, or reaching a remote tool
  without giving up ownership of your own state. The target state is written up
  in [docs/NETWORK.md](docs/NETWORK.md).
- **Module adapters in-process** — Telegram, Discord and the rest, so your agent
  can meet you where you already are ([Covia #410](https://github.com/covia-ai/covia/issues/410))
- **Native packaging** — signed installers per platform instead of `java -jar`
- **Richer message kinds** — images, cards and structured tool output as
  first-class rows in the transcript
- **Local model paths** — a genuinely offline default
- **Skill sharing** — import, export and review skills your agent wrote

Ideas and disagreement are welcome — open an issue.

## Contributing

Contributions are very welcome, especially at this stage.

```bash
mvn test      # unit tests; they boot real venue engines, headless
mvn package   # → target/brightside.jar
mvn exec:java # run from the build
```

- `master` is the integration branch; `develop` is the day-to-day working
  branch. Branch from `develop` and PR into it.
- Tests run headless and must never put a window or a tray icon on anyone's
  desktop. The chat tests use `v/test/ops/llm`, so they need no API key.
- British English in comments, UI text and docs. Tabs for indentation in Java
  and XML, matching Covia.
- [AGENTS.md](AGENTS.md) is the short guide for anyone — human or AI — working
  in this repository; read it and [docs/DESIGN.md](docs/DESIGN.md) before a
  first PR. The `docs/` folder explains *why* things are the way they are.

## Licence

Eclipse Public License 2.0 — see [LICENSE](LICENSE).

Built on the [Covia Grid](https://covia.ai) and [Convex](https://convex.world).
