# Architecture

Brightside is one JVM process holding a Swing desktop app and a complete Covia
venue: single Maven module `ai.covia:brightside`, launcher `brightside.Main`
and desktop application `brightside.BrightSide`. The desktop application is a client of the Embedded
Venue API within that process, with direct access to venue state and services.

- **The Embedded Venue API is the application API.** Brightside deliberately
  couples to the venue's Java APIs, immutable lattice values, services and live
  events. The remote HTTP, MCP and A2A APIs serve external clients; they do not
  define or limit the desktop application's integration.
- **The venue owns application state and behaviour.** Views read state directly
  and perform actions through the appropriate embedded APIs. Chat uses the
  venue's agent framework under the acting principal. View selection and
  presentation belong to the UI.
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
Brightside application ──► Embedded Venue API (in-process Java)
                            ├─ state reads and live subscriptions
                            ├─ engine, adapters and venue services
                            └─ LocalVenue operations as the acting principal
                                         │
                                 embedded Covia venue
                                 lattice state → venue.etch
                                         │
External clients ──► HTTP / MCP / A2A on 127.0.0.1
```

For MCP clients that launch a stdio subprocess, `Main` selects `McpStdio`
before loading the desktop app. It bridges to the venue using the existing
local-tool token, starting the desktop app separately when needed;
the MCP SDK handles HTTP sessions and streams.
The bridge owns no venue or store. Setup is in
[CONFIGURATION.md](CONFIGURATION.md#from-a-stdio-mcp-client).

## Reads

Read application state through the Embedded Venue API. Direct lattice reads
and subscriptions use the venue's in-process data structures and events,
without serialisation or durable jobs. Computed reads can use the venue's
read-only operations transiently. A remote-client abstraction is unnecessary
for these local interactions.

## Actions

Make application state changes through the appropriate embedded venue APIs,
using their validation, permissions and persistence semantics. Use operations
where the venue models an action as an operation, and direct service APIs where
those are the idiomatic interface. The lattice remains authoritative; the UI
projects its state rather than maintaining a separate application model.

## Key decisions

**Threading.** Swing on the event thread only. Venue launch and close, agent
calls and desktop integration run on background threads.

**Storage settings distinguish installation access from personal data.**
`FileRoots` persists folder access for the next launch; `PersonalDrives` uses
the acting user's DLFS operations and prepares their initial drive once.
The Files settings page reads and changes storage off the event thread,
discarding stale results when the principal changes. See
[CONFIGURATION.md](CONFIGURATION.md#files-and-personal-drives) for their scope
and restart behaviour.

**The file browser separates presentation from storage.** `ui/files/FileBrowser`
uses the small, provider-independent `files/FileSource` boundary; `VenueFiles`
reads through Covia's running file and DLFS adapters with their access checks.
Navigation and bounded previews run off the event thread. A lazy directory
cache shares reads between tree expansion and folder selection; refresh
invalidates it, and principal changes replace the entire binding. Stale results
are discarded after selection, principal changes or logout. The browser uses
the shared Brightside cards, labels, icons and readouts. Sharing this
component with Convex GUI is future work; the first implementation lives in
Brightside without a Convex GUI dependency.

**The chat session reflects the agent framework.** The session view represents
Covia agent state and allows user interactions through the embedded APIs.
Its close coupling to the framework is intentional: the venue owns execution,
history and memory, while the view owns selection, rendering and subscriptions.

**Session recovery requires a recognised condition.** When resuming a saved
conversation, Brightside starts a fresh one only for the agent adapter's
optional unknown-session code. Missing or unfamiliar codes leave the saved
conversation in place and surface the failure; message wording never decides
recovery. The code contract belongs to [Covia Jobs](https://github.com/covia-ai/covia/blob/develop/venue/docs/JOBS.md).

**A reply has no deadline.** A turn takes as long as its model and tool calls
take, and the venue bounds each of those itself.

**The assistant writes Markdown.** Assistant bubbles render through
Covia Workbench's `covia.gui.markdown`: commonmark-java's AST becomes a styled
document. Brightside supplies its theme, bubble appearance and link actions;
the shared Swing components install no application styling. The owner's own
words stay as typed.

**In-flight activity shows facts only.** The thinking bubble presents what
Brightside has been told: preparing, running, elapsed time, and tool calls as
the venue reports them.

**Conversations are switchable, and a change never yanks you elsewhere.** The
session view owns the selected conversation, live subscription and refreshes.
`SessionFeed` reads and projects lattice state off the event thread; changing
the selection invalidates pending results, and leaving a binding closes its
feed and subscription. `BrightSide` owns application lifecycle and principal
binding, not conversation watching. The agent record remains authoritative.

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
