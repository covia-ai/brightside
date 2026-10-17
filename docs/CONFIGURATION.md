# Configuration reference

Everything Brightside keeps lives under `~/.brightside/`, and everything it
can be told is in one hand-edited file there.

- **Every key is optional.** An empty `{}` is valid; Brightside supplies a
  working default for anything omitted. Changes take effect on restart.
- **The `venue` map is passed through.** Any key the Covia venue understands
  can be set, merged over Brightside's private-by-default settings.
- **No secrets in the file.** Model keys, bot tokens and the Moltbook key
  live in the venue's encrypted secret stores, entered through Settings.
- **Back up the whole directory as one unit**; delete it to start over. The
  protection and recovery role of each file is in
  [SECURITY.md](SECURITY.md).
- **Other tools reach the venue** over loopback with a token minted under
  *Settings → Auth*, as the user rather than across users.

## Where things live

| Path | What it is |
|---|---|
| `~/.brightside/config.json` | Settings (JSON5 — comments allowed). Hand-edited. |
| `~/.brightside/identity.json` | Your name, stable slug and full Covia user DID, kept apart so identity changes never rewrite settings. |
| `~/.brightside/venue.etch` | The encrypted lattice store: conversations, memory, skills, agent state. |
| `~/.brightside/venue.etch.unclean-<stamp>` | The store as it was before a launch-time repair ([LAUNCH.md](LAUNCH.md)); delete once the repaired store has proved itself. |
| `~/.brightside/vault.salt` | The non-secret salt for passphrase hardening. |
| `~/.brightside/identity.enc` | The AES-GCM-encrypted Ed25519 identity seed. |
| `~/.brightside/keys.enc` | Transient: stages an onboarding API key until the first launch moves it into the venue's encrypted secret stores; absent afterwards. |
| `~/.brightside/unlock.passphrase` | Optional remembered passphrase, stored as plaintext after explicit opt-in; exclude it from ordinary vault backups. |
| `~/.brightside/tool-token.json` | Optional: while Brightside runs, a user token for local tools, renewed hourly, after explicit opt-in ([From Claude Code](#from-claude-code)). |
| `~/.brightside/files/` | Brightside-managed local files; exposed to the assistant as the confined writable `files` root. |
| `~/.brightside/code/` | Source checkouts the assistant works on, as the writable `code` root; `code/brightside` is Brightside's own source, a [Claude Code](#claude-code) project by default. |
| `~/.brightside/logs/` | Plaintext rolling logs; exposed to the assistant as the server-enforced read-only `logs` root. |

To reset Brightside completely, remove the whole data directory while
Brightside is stopped.

If remembered unlock is enabled, omit `unlock.passphrase` from a normal backup:
putting the plaintext passphrase beside the encrypted vault defeats the backup's
passphrase protection. Include it only when the backup itself has equivalent
access controls and that trade-off is intentional.

*Settings → General → Open settings file* opens `config.json` in your editor;
*Open logs folder* on the same page opens the log directory. Changes to the file
take effect on restart.

Running `java -jar target/brightside.jar path/to/config.json` uses a specific
configuration file instead, and that file's directory becomes the data
directory — including its `files/`, `code/` and `logs/` roots — handy for keeping several
independent agents side by side.

Adding `--unlock` unlocks at once with the remembered passphrase instead of
waiting at the unlock dialog, for starting Brightside from a script or from
Claude Code while you are away, and comes up in the tray rather than as a
window. Without a remembered passphrase it exits with
an error; if Brightside is already running it leaves it be, unless
`--takeover` is given too, which takes over from it without asking (on its
own, `--takeover` just skips that question) ([LAUNCH.md](LAUNCH.md#startup)).

## The file

```json5
{
  "theme": "dark",                      // or "light"
  "repairStore": true,                  // rebuild the store on launch after a crash

  "venue": {                            // a Covia venue config map
    "name": "Brightside Venue",
    "port": 8085
  },

  "chat": {
    "agentId": "Brightside",            // agent at <venue DID>/g/Brightside
    "operation": "v/ops/llmagent/chat", // transition operation
    "llmOperation": "v/models/anthropic/claude-sonnet-5-5",
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

### `repairStore`

`true` (default): a store that was not closed cleanly — a crash, a power cut,
a launch stopped dead — is rebuilt into a fresh file on the next launch, with
the old file kept beside it as `venue.etch.unclean-<stamp>`; the chat opens
with a note saying so. `false` stops the launch instead, for an owner who
would rather restore a backup by hand. How the repair works is in
[LAUNCH.md](LAUNCH.md).

### `venue`

Accepts **any key the Covia venue runtime understands** — `mcp`, `a2a`,
`adapters`, `modules`, `auth`, `store`, `secrets`, and so on. The map is merged
over Brightside's defaults recursively: nested maps keep omitted defaults,
while explicit scalar values and lists replace them. For example, changing
`auth.tokenExpiry` preserves `auth.public.enabled: false`; enabling anonymous
access requires setting `auth.public.enabled: true`. New venue options need
no Brightside change.

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
| `llmOperation` | The model operation. Default `v/models/anthropic/claude-sonnet-5-5`. |
| `systemPrompt` | The assistant's identity, role and tone. Dynamic owner/model context comes from Brightside's read-only context operation; task detail belongs in on-demand skills. |

A reply has no time limit. A turn takes as long as its model and tool calls
take — the venue bounds each of those itself — and a turn that runs long shows
a stop control in the chat. Stopping cancels the chat job, not the agent: you
can carry on at once, and anything the assistant still finishes appears in the
conversation.

## Model API keys

The default model operation needs a provider key. Two supported ways to supply it:

1. **Environment** — put `ANTHROPIC_API_KEY` in the environment before
   launching.
2. **Brightside settings** — enter it during onboarding or under *Settings →
   Model*. It is written to the encrypted secret stores on the venue — yours
   and the operator's, so your agents and the gods each resolve their own copy —
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
   whose tools are Brightside's own Moltbook operations. Each resolves the key
   inside the venue and returns Moltbook's answer as data — the model never
   composes a request or sees the key. Setting up from chat is a child skill
   it loads only when Moltbook is not set up ([SKILLS.md](SKILLS.md)).

Registered elsewhere, or rotated the key on the owner dashboard
(`https://www.moltbook.com/login`)? Paste the key under *Existing key* and
*Connect with key*. *Forget* drops the key and the claim page here; the account
itself stays yours on Moltbook.

| What | Where |
|---|---|
| The key | in your user's encrypted secret store inside the venue store (`venue.etch`), keyed from your identity seed — it survives a forgotten-passphrase recovery, and it is yours alone. The Moltbook operations resolve it as `s/MOLTBOOK_API_KEY` |
| The claim page | remembered under your workspace at `w/moltbook` until the account is claimed |
| The account | one, named as you chose, owned by you on Moltbook; Brightside holds nothing else |

A periodic check-in can be set up with the `automation` skill if you want the
assistant to keep up by itself.

## Claude Code

Your assistant can hand coding work to [Claude Code](https://claude.com/claude-code),
Anthropic's coding agent, in project directories you name — one task per turn,
or a conversation it continues across turns. Covia's `covia-claude-code`
adapter runs inside Brightside's venue (registered in-process, like Discord)
and starts the `claude` command installed on this computer as your own OS
user, so it uses whatever sign-in Claude Code already has here and needs no key
from Brightside.

One project exists without any configuration: **`brightside`**, Brightside's
own source, at `~/.brightside/code/brightside`. Brightside creates the
directory empty; the assistant clones the repository into it when you first
ask it to work on Brightside, and from then on can fix and extend the app —
building needs Java 21 and Maven on your computer, and the change takes
effect when you run the new build. In that project Claude Code works in its
`auto` permission mode with the everyday developer tools allowed outright —
its own file, search and sub-agent tools, and the shell commands of
building, testing, package managers, git and looking around, across the
common languages; the list is `ClaudeCode.DEVELOPER_TOOLS`. Headless, nobody
can answer a prompt, so those go through without one, and Claude Code's own
safety checks judge anything else: moving data out, deleting, the unusual.
The rules address Claude Code's Bash tool; its PowerShell tool on Windows
takes no per-command rules in the current CLI, so PowerShell commands are
judged the same way (Claude Code reaches for Bash first). Git in that project
runs with terminal and credential-manager prompts disabled, so an operation
that needs a credential fails at once rather than hanging the turn: sign in
once at a terminal of your own and the credential manager keeps it. The `code/`
directory is also a file root, so the assistant can read a checkout
directly.

Naming any other directory Claude Code may work in is your decision, made in
`config.json` under the venue's adapter block:

```json5
"venue": {
	"adapters": {
		"claudecode": {
			"projects": {
				// a name → the directory, or {path, description, options}
				"notes": "C:/Users/me/notes",
				"site": {
					"path": "C:/Users/me/site",
					"description": "My website",
					"options": {
						"permissionMode": "acceptEdits",
						"allowedTools": ["Bash(npm:*)", "Bash(git:*)"]
					}
				}
			}
		}
	}
}
```

A project you declare as `brightside` replaces the default — the way to point
the assistant at a developer's own checkout. Restart, and the assistant's
`coding` skill lists the projects and reveals its children for working in
them — `claude-code`, `git`, and `self-improvement` for Brightside's own
source ([SKILLS.md](SKILLS.md)).

Claude Code runs headless here, so it cannot ask for permission the way it
does in a terminal: whatever a project's options do not allow is refused and
reported back as a denial. With no options it can read and answer but not
edit or run anything that changes the directory. `acceptEdits` lets it edit
files; `allowedTools` names the commands it may run (`Bash(npm:*)` is npm,
`Bash(git:*)` is git); `auto` puts Claude Code's own safety checks in
charge of whatever the allow list does not cover — headless, that means a
refusal, not a prompt, so pair it with an allow list as Brightside's own
project does; `bypassPermissions` removes every check, and is available only
to a project that declares it. A question it cannot settle itself it asks by
ending its turn with, and the assistant answers on the next turn of the same
session.

| What | Where |
|---|---|
| Whose a project is | yours, unless it names another `user` (a DID). The adapter's own default is the venue operator, whose agents are not yours; Brightside makes every project without a `user` the owner's — and adds the `brightside` project — when your identity binds to the venue at launch |
| The conversations | Claude Code's own transcripts, so a session outlives its process and a restart; the adapter keeps a small pool of live processes and continues a session with `--resume` |
| Everything else the adapter accepts | `command` (when `claude` is not on the PATH, or an argv array), `defaults` and per-project `options` (model, permission mode, allowed tools, turn and budget limits…), `maxSessions`, `idleSeconds` — as the module documents them in the Covia repository |

A mistake in the block — a directory that does not exist, an unknown setting —
leaves Claude Code out for that launch and says why in the log; the rest of
Brightside is unaffected. The other direction, Claude Code using your venue as
a tool, is under [Reaching the venue from other tools](#reaching-the-venue-from-other-tools).

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

(This is Claude Code reaching *your venue*; the assistant handing work *to*
Claude Code is [above](#claude-code).)

Claude Code reaches the running venue's MCP endpoint through an MCP server
named `brightside`. The private way, kept in your own `~/.claude.json` and
never in the repository, is one command with the token:

```
claude mcp add --transport http --scope local brightside http://127.0.0.1:8085/mcp   --header "Authorization: Bearer <token>"
```

The shareable shape is a project-scoped `.mcp.json` at the repository root,
git-ignored here because it carries the token:

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

**Without pasting tokens.** Tick *Settings → Auth → Keep a token for local
tools* and Brightside keeps a user token, valid for a day and renewed every
hour while it runs, in `~/.brightside/tool-token.json`, already in the shape
Claude Code's `headersHelper` must print. Point the server at it instead of a
pasted header, and Claude Code reads it afresh at every connect, and again
after a `401`:

```json
{
  "mcpServers": {
    "brightside": {
      "type": "http",
      "url": "http://127.0.0.1:8085/mcp",
      "headersHelper": "cat ~/.brightside/tool-token.json"
    }
  }
}
```

With that, and a remembered passphrase, Claude Code can start Brightside
itself with `java -jar target/brightside.jar --unlock --takeover` and connect
once it is up, with nobody at the computer. On Windows, if the helper's shell
does not expand `~`, give the absolute path.

Mint it **as the user** for Claude Code to see what you see — your workspace,
agents, conversations and inbox. A token minted as the venue operator sees the
venue's own namespace and its administration, but not your private data: even
the operator needs your user's authority for that. Mint a new token when it
expires; for a different port, edit the URL.

## Schedule

*Settings → Schedule* lists what is set to run on its own: the reminders,
routines and check-ins that you, your assistant or any of your agents
scheduled through the venue's scheduler (the `automation` skill), soonest
first. A summary at the top says what is next, how many events repeat, and
whether any tracked run failed or is waiting for you. Each event's card shows
when it runs next, counting down while the page is open, how often it
repeats, and how its last run went. Click a card to open its details: the
operation, exact times, whether runs are kept in your job history, the last
job and any error, each copyable.

*New…* schedules any operation by reference, with a JSON5 input, a first run
and an optional repeat; an operation that doesn't resolve is refused there
rather than failing when it fires. *Run now* fires an event straight away; a
repeating one stays on its schedule. *Cancel* stops it for good, and asks
first. Right-click a card for these and more: *New from this…* starts a new
event with the same operation and timing, and the operation, handle, last
job and error can each be copied. The scheduler does not list an event's
input yet ([covia#583](https://github.com/covia-ai/covia/issues/583)), so
*New from this…* leaves it for you to add, and an event can't be edited in
place: cancel it and schedule again.

Scheduling, running and cancelling each leave a job record; looking at the
page does not. Acting as the venue operator shows and manages the venue's own
schedule instead.

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
