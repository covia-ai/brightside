# Configuration reference

Everything Brightside keeps lives under `~/.brightside/`. Every key in the
configuration file is optional; an empty `{}` is valid and Brightside supplies a
working default for anything you omit.

## Where things live

| Path | What it is |
|---|---|
| `~/.brightside/config.json` | Settings (JSON5 — comments allowed). Hand-edited. |
| `~/.brightside/identity.json` | Your name, stable slug and full Covia user DID, kept apart so identity changes never rewrite settings. |
| `~/.brightside/venue.etch` | The encrypted lattice store: conversations, memory, skills, agent state. |
| `~/.brightside/vault.salt` | The non-secret salt for passphrase hardening. |
| `~/.brightside/identity.enc` | The AES-GCM-encrypted Ed25519 identity seed. |
| `~/.brightside/keys.enc` | Provider API keys encrypted under the passphrase-derived key. |
| `~/.brightside/unlock.passphrase` | Optional remembered passphrase, stored as plaintext after explicit opt-in; exclude it from ordinary vault backups. |
| `~/.brightside/files/` | Brightside-managed local files; exposed to the assistant as the confined writable `files` root. |
| `~/.brightside/logs/` | Plaintext rolling logs; exposed to the assistant as the server-enforced read-only `logs` root. |

Back up the whole directory as one unit. To reset Brightside completely, remove
the whole data directory while Brightside is stopped. There is no supported
plaintext or unencrypted legacy-install mode.

If remembered unlock is enabled, omit `unlock.passphrase` from a normal backup:
putting the plaintext passphrase beside the encrypted vault defeats the backup's
passphrase protection. Include it only when the backup itself has equivalent
access controls and that trade-off is intentional.

*Settings → General → Open settings file* opens `config.json` in your editor;
*Open logs folder* on the same page opens the log directory. Changes to the file
take effect on restart.

Running `java -jar target/brightside.jar path/to/config.json` uses a specific
configuration file instead, and that file's directory becomes the data
directory — including its `files/` and `logs/` roots — handy for keeping several
independent agents side by side.

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
- a persistent encrypted store at `~/.brightside/venue.etch`, with its identity
  and encryption material injected only into the in-memory venue configuration
- anonymous HTTP and MCP access disabled; local tools must authenticate
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
| `systemPrompt` | The assistant's identity, role and tone. Dynamic owner/model context comes from Brightside's read-only context operation; task detail belongs in on-demand skills. |
| `timeout` | Seconds to wait for a reply before giving up. |

## Model API keys

The default model operation needs a provider key. Two supported ways to supply it:

1. **Environment** — put `ANTHROPIC_API_KEY` in the environment before
   launching.
2. **Brightside settings** — enter it during onboarding or under *Settings →
   Model*. Brightside stores it in encrypted `keys.enc` and provisions the
   running venue's public secret scope in memory.

The runtime scope must be public, not venue-only. You chat as `u:<your name>`, a
local principal that is deliberately *not* the venue principal, and the public
secret scope is what a local user resolves from. Do not put API keys in
`config.json`.

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

Here `<name>` is the stable, DID-safe slug chosen on first setup, not the
editable display spelling. `identity.json` records both forms and pins the full
user DID once the home venue first launches:

```json
{
  "name": "Mike Anderson",
  "slug": "mike-anderson",
  "did": "did:key:z…:u:mike-anderson"
}
```

Chatting as a distinct user rather than as the venue itself is what makes the
venue attribute turns to the agent's *owner* — you — instead of to "the venue
operator". The everyday UI uses your name; the full user DID, home venue DID and
Ed25519 signing public key are available under **Settings → Identity**. General
runtime details are under **Settings → General → About**. Change the name from
**Settings → General → Change name…**; the saved slug keeps the DID stable
across renames.

## Reaching the venue from other tools

The venue speaks HTTP, MCP and A2A on loopback. Anonymous access is disabled, so
you need a token. *Settings → Auth* mints a short-lived token either for the
current named user (the default) or the venue operator (advanced), without
writing it to disk. Tools that need a different subject can use the
passphrase-gated identity-seed export under *Settings → Identity* and the
Covia/Convex SDK. Set the token's `sub` to the venue DID to act as the operator,
or to `<venueDID>:u:<name>` to act as your local user — local principals have no
key of their own, so a venue-signed token is how they authenticate off-process.

Then use any standard client: `Authorization: Bearer <token>` against
`/api/v1/…`, the Covia SDK, or the MCP endpoint. See the *Debugging / accessing
the venue* section of [AGENTS.md](../AGENTS.md) for the exact call.

## Environment variables

| Variable | Effect |
|---|---|
| `ANTHROPIC_API_KEY` | Key for the default model operation. |
| `BRIGHTSIDE_NO_TRAY=1` | Skip the system tray; closing the window quits. |
