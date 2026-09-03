# Architecture

Brightside is one JVM process holding a Swing desktop app and a complete Covia
venue: single Maven module `ai.covia:brightside`, main class
`brightside.BrightSide`. No daemon, no socket to secure, no serialisation
between the UI and the agent's state.

- **The UI is a client, not an insider.** The window acts as the owner's named
  principal and drives the agent through the ordinary agent operations. What it
  shows is what any other client of the venue would see; there is no private
  model path.
- **Reads are free; actions are not.** State is read in-process with no job,
  computed reads run read-only operations transiently, and only actions leave a
  durable job.
- **Configuration is data.** The owner's `venue` map is merged over defaults
  and handed to the venue, so new venue options need no Brightside change.
- **One UI kit, themed by FlatLaf.** Every screen is composed from
  `ui/components`; no screen hard-codes a colour or a font.
- **Skills carry the abilities.** The assistant starts small and loads what a
  task needs ([SKILLS.md](SKILLS.md)).

Product principles are in [DESIGN.md](DESIGN.md); startup, takeover and exit
in [LAUNCH.md](LAUNCH.md); keys and recovery in [SECURITY.md](SECURITY.md);
the file and API keys in [CONFIGURATION.md](CONFIGURATION.md).

## The shape of it

```
Swing UI ──► LocalVenue client (as u:<name>) ──► embedded Covia venue
                                                   engine
                                                   adapters
                                                   agent framework
                                                   lattice state → venue.etch
                                                   HTTP / MCP / A2A on 127.0.0.1
```

## Reads and actions

Three tiers, and the rule for choosing between them:

- **State reads** — a lattice path, as the user: straight from the in-process
  engine, no job (`EmbeddedVenue.resolve`, `agentRecord`). Lattice values are
  immutable and content-addressed, so change detection is an equality check of
  the last value shown against the current one. That is why the conversation
  watcher can poll every couple of seconds silently and near-free.
- **Computed reads** — adapter logic with no side effects: what an agent is,
  what its model would see, a bot's status. `Venue.run` on an operation
  declared `readOnly`: a transient job, so the contract, authority and
  admission still apply but nothing is persisted. Opening a screen leaves no
  record.
- **Actions** — chat, create, update, rename, delete, respond, write:
  `Venue.invoke`, a durable job with a receipt, cancellation and history.

Reaching further into the engine's Java API than resolving a path is
deliberately not done, even though the venue is in-process: it would bypass
the capability checks that make the UI a client, and couple Brightside to
internals that change far more often than the operation contracts. An
operation that is a read but is not declared `readOnly` is fixed upstream, not
worked around.

## Orientation map

```
brightside/
  BrightSide             entry point and application controller
  AppConfig, Identity    ~/.brightside/config.json (JSON5); name, slug and pinned user DID
  EmbeddedVenue          the venue in-process: launch, per-user clients, path reads
  Takeover               detect a running instance and ask it to step aside
  BrightsideAdapter      Brightside's own operations: context, info, delete-skill,
                         report-skill-feedback, shutdown, the Odin bridges
  BrightsideSkillsAdapter  installs the shipped skills under v/skills/brightside
  Odin                   the operator's administrative agent
  Moltbook, MoltbookAdapter, Discord   integrations: the account or bot, and
                         the venue operations the assistant uses them through
  SessionHistory, ConversationWatcher  the transcript projection and change watcher
  AgentContext, AgentInfo, SkillIndex, Inbox   the read models behind the screens
  chat/ChatSession       agent configuration and the chat itself
  model/Providers        model providers and their secret names
  skills/FilesystemSkills   imports SKILL.md folders into w/skills
  vault/                 passphrase key, seed-derived store key, BIP39 phrase
  markdown/              Markdown → StyledDocument; depends only on commonmark
  ui/                    LAF and MainWindow; components/ (the kit), settings/,
                         onboarding/, chat/, inspect/, inbox/

resources/
  brightside/skills/*.json       the shipped skills
  brightside/ui/*.properties     FlatLaf defaults and style classes
  adapters/brightside/*.json     Brightside's operation assets
  fonts/, icons/                 Lato, Inconsolata, Lucide, the Brightside mark
```

Tests boot temporary venues headless and test mechanism, never prose.

## Key decisions

**Threading.** Swing on the event thread only. Venue launch and close, agent
calls and desktop integration run on background threads.

**The chat session absorbs the agent framework's quirks.** Configuration is
re-applied on the next send rather than at launch, so a launch submits no
jobs; a failed transition leaves the agent suspended, so it is resumed after
re-applying; only the venue's "unknown session" error starts a fresh session,
so a model or key failure never mints an orphan conversation; follow-ups sent
while a reply is in flight are delivered to the same session.

**A reply has no deadline.** A turn takes as long as its model and tool calls
take, and the venue bounds each of those itself. After a while the thinking
bubble offers a stop control; confirming it cancels the chat job, which
releases the composer but does not interrupt the agent, so whatever it still
finishes lands in the conversation.

**Transcript items.** `SessionHistory` projects a conversation into messages
(user and final assistant text) and activity (the tool calls of a turn).
Messages render as bubbles, activity as a collapsed chip that opens into
per-tool rows. New message kinds are new item types with their own rows.

**The assistant writes Markdown.** Assistant bubbles render through
`brightside.markdown`: commonmark-java's AST becomes a styled document, with
the look supplied from the current theme. The package depends on commonmark
and the JDK only, so it can be lifted out. The owner's own words stay as
typed.

**In-flight activity shows facts only.** The thinking bubble presents what
Brightside has been told: preparing, running, elapsed time, and tool calls as
the venue reports them. It never derives progress from the clock or exposes
hidden model reasoning.

**Conversations are switchable, and a change never yanks you elsewhere.** The
agent record holds many sessions; a background update re-renders the one on
screen. Home shows the conversation in progress and startup restores the most
recent; only the explicit new-chat control starts an empty one, and the venue
mints its session on the first message.

**The tray is best-effort.** Without a tray the window still behaves sensibly,
and a tray failure never takes the app down.

**Shutdown flushes state.** Exit closes the venue before the process ends, and
a shutdown hook covers Ctrl-C and SIGTERM.

## Packaging

The runnable jar is built by `maven-shade-plugin` with the services
transformer, because Jetty, Javalin and LangChain4j rely on
`META-INF/services`, and drops the venue's bundled OpenAPI plugin so the venue
serves its own document. Logging is configured programmatically from
`brightside/logback.xml` because the venue jar ships a root `logback.xml` of
its own.
