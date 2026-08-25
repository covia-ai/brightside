# Configuration reference

Everything Brightside keeps lives under `~/.brightside/`. Every key in the
configuration file is optional; an empty `{}` is valid and Brightside supplies a
working default for anything you omit.

## Where things live

| Path | What it is |
|---|---|
| `~/.brightside/config.json` | Settings (JSON5 — comments allowed). Hand-edited. |
| `~/.brightside/identity.json` | Your name, kept apart so choosing a name never rewrites your settings. |
| `~/.brightside/venue.etch` | The lattice store: conversations, memory, skills, agent state. |
| `~/.brightside/venue.key` | The venue's Ed25519 identity seed (32 bytes, hex). |
| `~/.brightside/logs/` | Logs. |

To reset the venue completely, delete `venue.etch` and `venue.key` **together** —
the store and the identity that signs for it belong to each other.

*Advanced → Open settings file* opens `config.json` in your editor; *Advanced →
Open logs folder* opens the log directory. Changes to the file take effect on
restart.

Running `java -jar target/brightside.jar path/to/config.json` uses a specific
configuration file instead, and that file's directory becomes the data
directory — handy for keeping several independent agents side by side.

## The file

```json5
{
  "theme": "dark",                      // or "light"

  "venue": {                            // a Covia venue config map
    "name": "Brightside Venue",
    "port": 8085
  },

  "chat": {
    "agentId": "brightside",            // agent at <venue DID>/g/brightside
    "operation": "v/ops/llmagent/chat", // transition operation
    "llmOperation": "v/models/anthropic/claude-sonnet-5",
    "systemPrompt": "You are Brightside, ...",
    "timeout": 120                      // seconds to wait for a reply
  }
}
```

### `theme`

`"dark"` (default) or `"light"`. FlatLaf's macOS-style themes with a purple
accent.

### `venue`

Accepts **any key the Covia venue runtime understands** — `mcp`, `a2a`,
`adapters`, `modules`, `auth`, `store`, `secrets`, and so on. The map is merged
over Brightside's defaults key-for-key: each key you set replaces Brightside's
default for that key, and the rest are left alone. New venue options therefore
need no Brightside change.

Brightside's defaults:

- bind to `127.0.0.1` (loopback only — nothing on your network can reach it)
- a persistent store at `~/.brightside/venue.etch`, with the identity seed in
  `venue.key` beside it
- auto-create users
- the MCP endpoint enabled at `http://127.0.0.1:8085/mcp`

### `chat`

Describes the agent the window talks to. The agent is created on first use and
its configuration re-applied on every start; conversation history is kept across
restarts and across configuration changes.

| Key | Meaning |
|---|---|
| `agentId` | The agent's id under your namespace — `<venueDID>:u:<name>/g/<agentId>`. |
| `operation` | The transition operation driving each turn. Default `v/ops/llmagent/chat`. |
| `llmOperation` | The model operation. Default `v/models/anthropic/claude-sonnet-5`. |
| `systemPrompt` | Deliberately small: identity, tone, pointers. Detail belongs in skills. |
| `timeout` | Seconds to wait for a reply before giving up. |

## Model API keys

The default model operation needs a provider key. Two ways to supply it:

1. **Environment** — put `ANTHROPIC_API_KEY` in the environment before
   launching.
2. **The venue's secret store** — put it in the `secrets.public` block of the
   `venue` config.

It must be `secrets.public`, not `secrets.venue`. You chat as `u:<your name>`, a
local principal that is deliberately *not* the venue principal, and
`secrets.public` is what a local user resolves from. This is the same separation
that stops the venue operator having a backdoor into user data.

For an offline smoke test with no key at all, set:

```json5
"chat": { "llmOperation": "v/test/ops/llm" }
```

— the venue's echo test LLM. The app works end to end; the replies are just
echoes.

## Your identity

At first launch Brightside asks *"What should I call you?"*. That name is all
you give it. Behind the scenes it makes you a principal on your own venue:

```
u:<name>                                  the principal
<venueDID>:u:<name>                       your DID
<venueDID>:u:<name>/g/brightside          your agent
```

Chatting as a distinct user rather than as the venue itself is what makes the
venue attribute turns to the agent's *owner* — you — instead of to "the venue
operator". You only ever see the name; the technical identity is in **Help →
About**. Change it any time with **File → Change my name…**.

## Reaching the venue from other tools

The venue speaks HTTP, MCP and A2A on loopback. Access is capability-based, so
you need a token: the venue trusts JWTs it signed itself, and you can mint one
by signing with `~/.brightside/venue.key`. Set the token's `sub` to the venue
DID to act as the operator, or to `<venueDID>:u:<name>` to act as your local
user — the local principals have no key of their own, so a venue-signed token is
the only way to authenticate as them off-process.

Then use any standard client: `Authorization: Bearer <token>` against
`/api/v1/…`, the Covia SDK, or the MCP endpoint. See the *Debugging / accessing
the venue* section of [AGENTS.md](../AGENTS.md) for the exact call.

## Environment variables

| Variable | Effect |
|---|---|
| `ANTHROPIC_API_KEY` | Key for the default model operation. |
| `BRIGHTSIDE_NO_TRAY=1` | Skip the system tray; closing the window quits. |
