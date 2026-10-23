# Launch, takeover and exit

How a Brightside process starts, what happens when one is already running, and
how it stops. Code: `BrightSide`, `Takeover`, `EmbeddedVenue`, `TrayManager`.

- **Nothing shows without live data behind it.** The unlock dialog or wizard
  comes first; the main window appears only once the venue is ready.
- **A launch submits no jobs.** Everything read at startup is read
  in-process; the agent is configured on the first message.
- **Two processes cannot share the store**, so a newcomer asks the running
  instance to step aside with a request only the venue's own key can sign, and
  waits for it to release the store before opening it.
- **Closing the window quits** unless the owner opts into the tray; quitting
  always flushes state, and waits for a launch in progress first.

## Startup

The launcher `Main` normally enters `BrightSide.main`. With `--mcp-stdio`,
it runs a connection to the app, starting it with the existing unattended
unlock flow when absent. `McpStartup` serialises concurrent cold connections
and waits for authenticated readiness; an existing app is never taken over.
Standard output carries MCP messages only; diagnostics go to standard error.
Closing the client's input ends that connection and leaves the app running.
See [stdio setup](CONFIGURATION.md#from-a-stdio-mcp-client).

1. `BrightSide.main` loads `~/.brightside/config.json` (`AppConfig`), configures
   logging to `~/.brightside/logs/brightside.log` (dated rollovers; uncaught
   exceptions on any thread land there too) and installs the look and feel.
2. `start()` installs the tray icon where the desktop has one and shows the
   first screen at once: the onboarding wizard in the main window when no
   vault exists; otherwise the **unlock dialog** (`UnlockDialog`) on its own,
   pre-filled if the owner chose *Remember me*. The main window is built but
   stays hidden until the passphrase is accepted. With *Unlock with my stored
   passphrase, without asking* (Settings → General → Startup), a launch
   presses Unlock itself: the dialog shows only its progress, and takes a
   passphrase by hand if the stored one fails. Forgetting the passphrase turns
   that off. *Lock* (Settings → General) hides the window and brings the
   dialog back, and always asks; the tray's *Show* brings back whichever is
   current.

   `--unlock` on the command line (`java -jar brightside.jar --unlock`) is for
   a launch nobody is sitting at, such as one from Claude Code: it unlocks at
   once with the remembered passphrase and puts nothing on screen: no unlock
   dialog, and once the venue is up it stays in the tray (minimised, on a
   desktop without one) until the tray brings it back. With
   no passphrase remembered, or no vault yet, it exits at once with an error
   and opens no window; a remembered passphrase that fails exits with an
   error too, as does a launch that fails after it, since nobody is there to
   read a dialog. Once it is up it is an ordinary session: a later
   Lock asks for the passphrase as usual. If an instance is already running it
   is left alone and the launch exits, unless `--takeover` is given as well;
   the startup settings never apply to it.
   `--takeover`, with or without `--unlock`, takes over from a running
   Brightside without asking (below). On its own it is a launch someone is
   sitting at, as from an IDE: it also unlocks with the stored passphrase
   without asking, as the setting above does, and the window comes up as usual.
3. Unlock or onboarding opens the vault, yields the identity seed and secures
   the venue config with it (seed plus the Etch v3 store key). `launchVenueWith`
   then runs on a background thread while the unlock dialog stays up showing
   progress ("Starting the venue…"); the main window appears only once the
   venue is actually ready, so it never shows without live data behind it.
4. `EmbeddedVenue.launch` starts the `VenueServer` on loopback with public
   access disabled, registering `BrightsideAdapter`, `BrightsideSkillsAdapter`,
   Moltbook, Discord and Claude Code in Covia's pre-start hook: after the
   built-in adapters are installed and configured secrets provisioned, before
   durable jobs are recovered and adapter workers start, so a job a crash left
   on one of Brightside's operations is recovered by its owner and the Discord
   bots re-arm with everything else. A failure in the hook closes the
   half-assembled server, so nothing is left holding the port or the store.
   `onVenueReady` binds the
   identity to the venue and moves any provider keys still in the vault into
   the encrypted secret stores, still behind the dialog so a failure returns
   to it. Only then is the window revealed, and the chat starts as
   `<venueDID>:u:<slug>`: the agent record, the conversations, the agents list
   and the inbox are read in-process, any changed filesystem skill is imported
   and the conversation watcher starts. Nothing else is submitted: the agent
   is created or reconfigured on the first message, and the gods with it, so
   a launch writes no jobs. A failure while the chat binds is
   reported in the window rather than left as a composer that never wakes.

A first run that fails to launch reports on the wizard's last step, with
Finish available again once the cause is dealt with.

## Taking over a running instance

Two processes cannot share `venue.etch`; the second fails on the file lock. So
`start()` probes the configured port before any window appears, and
`launchVenueWith` probes it again before launching:

- `Takeover.isRunning(port)` — any HTTP answer from `/api/v1/status` means
  something is running; only a refused connection means nothing is. (A private
  venue answers `401` to strangers; that still counts.)
- A returning owner is asked *Take Over / Cancel* at startup, before the unlock
  dialog — Cancel exits without a passphrase ever being typed. `--takeover`
  answers *Take Over* in advance, as does *Always take over without asking*,
  ticked on that question or in Settings → General → Startup. The unlock
  dialog then notes that the running instance will hand over once unlocked.
  (An instance that appears only later is asked about at launch time instead.)
- A new install is told to quit the running Brightside first, and exits. It
  would mint a fresh identity, and only the running venue's own key can ask it
  to stop, so a takeover could never succeed.
- The handover itself waits for the vault, since the requests must be signed
  with the seed; it runs from the unlock dialog ("Taking over…") and this
  instance's main window appears only once the other has released the store —
  two main windows are never on screen together.
- `Takeover.identify` first asks what is on the port, running
  `v/ops/brightside/info` with a short-lived JWT (`iss = sub = aud =` the
  venue's DID) signed with the seed both instances unlocked. A Brightside
  holding our identity answers with its app name, venue DID, the store it
  holds and its process id. Anything else is refused with a message the owner
  can act on: a `401` is a Brightside with a different identity; an unknown
  operation is a Covia venue that is not Brightside; no venue answer at all is
  something else on the port.
- `Takeover.requestShutdown` then POSTs `v/ops/brightside/shutdown` with the
  same kind of token. The running instance accepts only the venue operator
  (`BrightsideAdapter.handleShutdown` requires the caller DID to equal the
  venue DID) and runs its `exit()`: the store flushes and the process ends.
- The venue closes its HTTP listener before it flushes and closes the store, so
  a quiet port is not a free store. `Takeover.waitUntilDown` polls until nothing
  answers, `waitUntilExited` waits for the process the instance named, and
  `waitUntilReleased` waits for the lock on `venue.etch` to lift. Only then
  does the newcomer launch its own venue.

This is not a venue restart: the newcomer is already up and only needs the
incumbent to step aside, and it works from an IDE launch with no jar.
`TakeoverTest` runs the handshake against a private venue.

## Tray, minimise, close, quit

- Minimise goes to the taskbar. *Send to the tray when minimised* is an opt-in
  under Settings → General.
- Close quits. *Keep running in the tray on close* is an opt-in. Closing the
  unlock dialog follows the same rule.
- *Hide to tray* (Settings → General, or the hide shortcut) hides the window and
  keeps the venue running; the tray icon brings it back.
- Quit (tray menu or Settings → General) always flushes and exits.
- Without a tray (unsupported desktop, or `BRIGHTSIDE_NO_TRAY=1`) there is
  nowhere to hide: close quits, minimise minimises.

## Exit and flush

`exit()` closes the session view's feed and subscription, stops the inbox
watcher, hides the window, removes the tray icon and closes
the `VenueServer` on a background thread before `System.exit`. A launch still
in progress is waited for first (bounded), so a quit from the unlock dialog
mid-boot still ends with a cleanly closed store. A Convex `Shutdown` hook at
`SERVER - 10` does the same on Ctrl-C and SIGTERM. `EmbeddedVenue.close()` is
idempotent.

## When the store was not closed cleanly

Etch v3 records a clean checkpoint in the store's header. A process that dies
between a write and its next sync or close — a crash, a power cut, an IDE
launch stopped dead — leaves the header open, and Etch refuses to open the
file: by its design there is no writable recovery open, only a repair into a
fresh file. `StoreRepair` does that at launch, with the key in hand: it
rebuilds `venue.etch` into a fresh file beside it, moves the old one aside as
`venue.etch.unclean-<stamp>` and installs the fresh one, then the launch goes
on and the chat opens with a note saying so. Only writes after the last sync
can be missing. If the fresh file cannot be installed, the old one is put back
so there is never no store at all. `"repairStore": false` in `config.json`
stops the launch instead, for an owner who would rather restore a backup by
hand ([CONFIGURATION.md](CONFIGURATION.md)).

## When launch fails

`Venue failed to start` in the log names the cause. `File lock failed on
venue.etch` means another process still holds the store without answering on
the port — one stopped dead mid-exit, or a different tool holding the file:
wait a moment, or quit it. A wrong passphrase is reported on the unlock screen,
not in the log.
