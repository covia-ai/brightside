# Brightside

**Brightside** is a Covia desktop companion: a JVM application that runs a
[Covia](https://covia.ai) venue *inside the process* and puts a chat window in
front of it. Minimise or close the window and the venue keeps running from a
system-tray icon; **Exit** flushes its state and stops it.

Its purpose is to **show off the Covia Grid and lattice technology as a personal
agent** — a full venue (engine, adapters, lattice-backed state, agent framework,
MCP/A2A/HTTP surface) running on your own machine, under your own identity.

- Modern Swing UI — [FlatLaf](https://www.formdev.com/flatlaf/) macOS-style themes, a purple accent and rounded **chat bubbles** (dark by default, light available)
- **Reopens your last conversation on restart** — transcript restored and the assistant's session continued
- A warm first-run welcome ("What should I call you?") — no jargon on the everyday screens
- Embedded Covia venue: full engine, all built-in adapters, HTTP/MCP endpoint on `localhost`
- Your own private assistant with a **memory** (`n/memory`) that persists across chats
- Its persona is a **Covia skill** (`v/skills/brightside/introduction`)
- **It can teach itself** — author new skills into `w/skills` (a gated `skill-authoring` ability)
- Chat window talking to your own agent on that venue, entirely in-process
- Tray icon; minimise and close go to the tray. Technical bits (dashboard, identity, logs) live under **Advanced**
- Persistent state under `~/.brightside/`

## Requirements

- Java 21+ (JDK)
- Maven 3.7+
- A local build of Covia `0.10.0-SNAPSHOT` — snapshots are not published to
  Maven Central. Covia depends on Convex, so build Convex first:

```bash
cd ../convex && mvn clean install -DskipTests
cd ../covia  && mvn clean install -DskipTests
```

## Build and run

```bash
mvn package                       # compiles, runs the tests, builds target/brightside.jar
java -jar target/brightside.jar   # run it
mvn exec:java                     # or run straight from the build
```

`java -jar target/brightside.jar path/to/config.json` runs with a specific
configuration file (its directory becomes the data directory).

## Configuration

On first launch Brightside writes `~/.brightside/config.json` — JSON5, with
comments — and every key in it is optional:

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

**`venue`** accepts any key the Covia venue runtime understands (`mcp`, `a2a`,
`adapters`, `modules`, `auth`, `store`, …); each key you set replaces
Brightside's default for that key. The defaults bind to `127.0.0.1`, keep a
persistent store at `~/.brightside/venue.etch` (with the venue's identity seed
in `venue.key` beside it — delete both together to reset), auto-create users
and enable the MCP endpoint at `http://127.0.0.1:8085/mcp`.

**`chat`** describes the agent the window talks to. It is created on first use
and its configuration re-applied on every start (history is kept). The default
model operation needs an API key: put `ANTHROPIC_API_KEY` in the environment
before launching, or store it in the venue's secret store — the `secrets.public`
block above, which every local user resolves from.  For an offline smoke test
set `"llmOperation": "v/test/ops/llm"` — an echo bot.

Edit the file and restart Brightside to apply changes. *Advanced → Open
settings file* opens it in your editor. Logs go to `~/.brightside/logs/`.

## Your name

At first launch Brightside asks *"What should I call you?"* — that's all you
need to give it. Behind the scenes the name makes you a principal on your own
venue (`u:<name>`, the DID `<venueDID>:u:<name>`, with your agent at
`<venueDID>:u:<name>/g/brightside`), which is what makes the venue treat your
messages as coming from the assistant's owner — you. You only ever see the
name; the technical identity lives in **Help → About**. The name is saved in
`~/.brightside/identity.json` (separate from `config.json`); change it any time
with **File → Change my name…**.

## Using it

- Type a message and press **Enter** to send (**Shift+Enter** for a newline).
- **File → New chat** starts a fresh conversation.
- **File → Change my name…** changes how the assistant addresses you.
- **Advanced → Open dashboard in browser** opens the venue's web UI, API docs
  (`/swagger`) and MCP endpoint — the platform behind the assistant.
- **Advanced** also has *Open settings file* and *Open logs folder*; **Help →
  About** shows the local address and identity.
- Minimising or closing the window hides it to the tray; click the tray icon
  to bring it back. **Quit** (tray menu or File menu) stops it.
- Without a system tray (headless-ish desktops, or `BRIGHTSIDE_NO_TRAY=1`)
  the window behaves normally and closing it quits.

## Project layout

```
src/main/java/covia/brightside/
├── BrightSide.java        entry point and application controller
├── AppConfig.java         ~/.brightside/config.json (JSON5) and defaults
├── Identity.java          the u:<name> user; ~/.brightside/identity.json
├── ConversationStore.java the last conversation; ~/.brightside/conversations/<slug>.json
├── BrightsideAdapter.java  Covia adapter: brightside:info + v/skills/brightside/… skills
├── EmbeddedVenue.java     VenueServer + per-user in-process LocalVenue client
├── chat/ChatSession.java  agent config (skills, n/memory) + agent:chat
└── ui/                    LAF, MainWindow, WelcomePanel, ChatPanel, TrayManager, Icons
src/main/resources/brightside/skills/*.json      shipped skill assets (introduction, skills, skill-authoring)
src/main/resources/adapters/brightside/info.json brightside:info operation
src/main/resources/brightside/logback.xml
docs/DESIGN.md             product design guidelines
src/test/java/…            unit tests (boot temporary venue engines; headless)
```

## Licence

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
