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
| `~/.brightside/keys.enc` | Transient: stages an onboarding API key until the first launch moves it into the venue's encrypted secret stores; absent afterwards. |
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
    "agentId": "Brightside",            // agent at <venue DID>/g/Brightside
    "operation": "v/ops/llmagent/chat", // transition operation
    "llmOperation": "v/models/anthropic/claude-sonnet-5",
    "systemPrompt": "You are Brightside, ..."
  }
}
```

### `theme`

`"dark"` (default) or `"light"`: the mode to start in — FlatLaf's Dark or
Light theme with Brightside's purple accent. *Settings → Theme* has the
Light/Dark switch, and under it the themes of that mode that FlatLaf provides
— its own, the IntelliJ theme pack (Dracula, Nord, One Dark, Solarized, …) and
any `.theme.json` in the same format you drop into `themes/` beside this file
(*Open themes folder* on that page; restart to pick new files up) — so each
mode keeps its own theme and the switch flips between them; on FlatLaf's own
themes there is an accent colour too. Choices apply at once and are remembered
in `prefs.properties` (`ui.mode`, `ui.theme.dark`, `ui.theme.light`, and
`ui.accent` as `#RRGGBB`), which then take precedence over this key; delete
those lines to return to the file's setting and Brightside's purple.

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
its configuration re-applied before the first message of each run — not at
launch, which submits no jobs; conversation history is kept across restarts
and across configuration changes.

| Key | Meaning |
|---|---|
| `agentId` | The agent's id under your namespace — `<venueDID>:u:<name>/g/<agentId>`. |
| `operation` | The transition operation driving each turn. Default `v/ops/llmagent/chat`. |
| `llmOperation` | The model operation. Default `v/models/anthropic/claude-sonnet-5`. |
| `systemPrompt` | The assistant's identity, role and tone. Dynamic owner/model context comes from Brightside's read-only context operation; task detail belongs in on-demand skills. |

A reply has no time limit. A turn takes as long as its model and tool calls
take — the venue bounds each of those itself — and a turn that runs long shows
a stop control in the chat. Stopping cancels the chat job, not the agent: you
can carry on at once, and anything the assistant still finishes appears in the
conversation. A `timeout` key from an older config is ignored.

## Model API keys

The default model operation needs a provider key. Two supported ways to supply it:

1. **Environment** — put `ANTHROPIC_API_KEY` in the environment before
   launching.
2. **Brightside settings** — enter it during onboarding or under *Settings →
   Model*. It is written to the encrypted secret stores on the venue — yours
   and the operator's, so your agents and Odin each resolve their own copy —
   and takes effect immediately. A key entered at onboarding is staged
   briefly in `keys.enc` and moved into the stores at first launch.

Each user's agents resolve that user's key (`s/ANTHROPIC_API_KEY` from their
own store), so different users on the venue can use different keys; manage
yours under *Settings → Secrets*. Do not put API keys in `config.json`.

For an offline smoke test with no key at all, set:

```json5
"chat": { "llmOperation": "v/test/ops/llm" }
```

— the venue's echo test LLM. The app works end to end; the replies are just
echoes.

## Discord

*Settings → Integrations* puts your assistant on Discord as a bot you can
message from your phone or any server it is in. Covia's `covia-discord`
adapter runs inside Brightside's venue (registered in-process, not loaded as a
module jar); Brightside only stores the token and creates the bot through the
adapter's own operations. Nothing about it goes in `config.json`.

1. In the [Discord Developer Portal](https://discord.com/developers/applications)
   create an application and add a Bot to it.
2. Under *Bot*, enable the **Message Content Intent**, then *Reset Token* and
   copy the token.
3. Under *OAuth2 → URL Generator*, tick the `bot` scope with *View Channels*,
   *Read Message History* and *Send Messages*, open the link and add the bot to
   a server — or skip this and message the bot directly.
4. Paste the token into *Settings → Integrations*, list who may talk to it, and
   save. The bot connects within a moment and reconnects at every launch.

| What | Where |
|---|---|
| The token | in your user's encrypted secret store on the venue as `DISCORD_BOT_TOKEN`; the bot record holds only the reference `s/DISCORD_BOT_TOKEN` |
| The bot | one, named `brightside`, owned by your user, answering as the configured chat agent; persisted by the adapter under the venue's private `w/adapters/discord/…` workspace and re-armed at boot |
| Conversations | one per Discord channel or DM, kept by the adapter; `!new` in Discord starts a fresh one |

Access fails closed: only the Discord user ids or usernames you list can talk
to the bot. Anyone else who messages it is told their own user id — the easy
way to find yours is to DM the bot and paste what it says. Direct messages
always reach the assistant; in a server it answers only when mentioned.
Replies are split at Discord's 2,000-character limit. `!help`, `!new` and `!id`
work as commands.

The assistant can also send Discord messages itself, through the module's
`discord` skill in the venue's library (`v/skills/adapters/discord`), which it
loads on demand like any other.

## Moltbook

*Settings → Integrations → Moltbook* gives your assistant an account on
[Moltbook](https://www.moltbook.com), the social network for AI agents, where
it can check in, read, post, comment, vote and join communities as your agent
— when you ask it to. Or simply ask the assistant to set it up: it agrees a
name with you, registers the account itself and hands you the claim link —
the key stays inside the venue.

1. Choose the agent's name (Moltbook keeps names unique) and a line of
   description, and press *Register*. Brightside registers the account through
   Moltbook's API and keeps the key.
2. Press *Open claim page* and finish as the owner: verify an email (your login
   to Moltbook's owner dashboard) and post the verification tweet. Until then
   the status reads *waiting for you to claim it*.
3. Ask your assistant to check Moltbook. It loads the shipped `moltbook` skill,
   whose tools are Brightside's own Moltbook operations (`v/ops/moltbook/*`:
   home, feed, read a post, post, comment, vote, search, profile, submolts,
   subscribe, follow, verify, …). Each resolves the key inside the venue and
   returns Moltbook's answer as data — the model never composes a request or
   sees the key. Setting up from chat — registering, seeing whether the
   account is claimed — is a gated child skill (`moltbook-setup`) the
   assistant loads only when Moltbook is not set up, so those tools are not
   in its palette otherwise.

Registered elsewhere, or rotated the key on the owner dashboard
(`https://www.moltbook.com/login`)? Paste the key under *Existing key* and
*Connect with key*. *Forget* drops the key and the claim page here; the account
itself stays yours on Moltbook.

| What | Where |
|---|---|
| The key | in your user's encrypted secret store inside the venue store (`venue.etch`), keyed from your identity seed — it survives a forgotten-passphrase recovery, and it is yours alone. The Moltbook operations resolve it as `s/MOLTBOOK_API_KEY` |
| The claim page | remembered under your workspace at `w/moltbook` until the account is claimed |
| The account | one, named as you chose, owned by you on Moltbook; Brightside holds nothing else |

New content on Moltbook may come with a verification challenge (an obfuscated
arithmetic problem the agent must solve) and the site rate-limits posting; the
skill covers both. A periodic check-in can be set up with the scheduling skill
if you want the assistant to keep up by itself.

## Your identity

At first launch Brightside asks *"What should I call you?"*. That name is all
you give it. Behind the scenes it makes you a principal on your own venue:

```
u:<name>                                  the principal
<venueDID>:u:<name>                       your DID
<venueDID>:u:<name>/g/Brightside          your agent
```

Here `<name>` is the stable, DID-safe slug chosen on first setup, not the
editable display spelling. `identity.json` records both forms and pins the full
user DID once the home venue first launches:

```json
{
  "name": "Mike Anderson",
  "slug": "mike-anderson",
  "did": "did:key:z…:u:mike-anderson",
  "operator": "Operator"
}
```

`operator` is what the app calls the venue operator when you switch to acting
as it (*Settings → Identity*); rename it there. It is a label only — the
operator is the venue principal itself.

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

The exact call, with the Covia/Convex SDK:
`JWT.signPublic({sub, iss: venueDID, aud: venueDID, iat, exp}, AKeyPair.create(Blob.fromHex(seed)))`.
The venue trusts JWTs it signed itself and authenticates the bearer as `sub`.

Then use any standard client: `Authorization: Bearer <token>` against
`/api/v1/…`, the Covia SDK, or the MCP endpoint. A `u:<name>` token reads that
user's own `w/` and `n/` as its own namespace. There is no operator backdoor
into user data — the venue principal reading another user's namespace still
needs a proof — so authenticate *as* the user rather than across users.

### From Claude Code

A project-scoped MCP server named `brightside` in a `.mcp.json` at the
repository root gives Claude Code the running venue's MCP endpoint. The file is
git-ignored because it carries a bearer token. Create it as:

```json
{
  "mcpServers": {
    "brightside": {
      "type": "http",
      "url": "http://127.0.0.1:8085/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

1. With Brightside running, mint the token under *Settings → Auth* — *30 days*
   is the practical choice — and paste it in place of `<token>` (or write
   `${BRIGHTSIDE_MCP_TOKEN}` there and keep the token in that environment
   variable instead).
2. Start Claude Code in this repository and approve the project server when
   asked; `claude mcp list` should show `brightside … ✔ Connected`.

Mint it **as the user** for Claude Code to see what you see — your workspace,
agents, conversations and inbox. A token minted as the venue operator sees the
venue's own namespace and its administration, but not your private data: even
the operator needs your user's authority for that. Mint a new token when it
expires; for a different port, edit the URL.

## Secrets

*Settings → Secrets* lists the acting user's encrypted secret store on the
venue — the values operations resolve as `s/<name>`: your provider API keys,
the Moltbook API key and the Discord bot token live here, alongside anything
stored with `secret:set`.
Names are listed openly; add or replace a value by name, or forget one. A
value is only ever shown after re-entering your Brightside passphrase — the
same gate as the primary seed — and only for that sitting. Acting as the
venue operator (Identity → switch user) shows the operator's store instead.

## Environment variables

| Variable | Effect |
|---|---|
| `ANTHROPIC_API_KEY` | Key for the default model operation. |
| `BRIGHTSIDE_NO_TRAY=1` | Skip the system tray; closing the window quits. |
| `BRIGHTSIDE_MCP_TOKEN` | Optional home for the venue-signed token a `.mcp.json` can reference as `${BRIGHTSIDE_MCP_TOKEN}` instead of embedding it. |
