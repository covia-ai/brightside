# Launch, takeover and exit

How a Brightside process starts, what happens when one is already running, and
how it stops. Code: `BrightSide`, `Takeover`, `EmbeddedVenue`, `TrayManager`.

## Startup

1. `BrightSide.main` loads `~/.brightside/config.json` (`AppConfig`), configures
   logging to `~/.brightside/logs/brightside.log` (dated rollovers) and installs
   the look and feel.
2. `start()` installs the tray icon where the desktop has one and shows the
   first screen at once: the onboarding wizard in the main window when no
   vault exists; otherwise the **unlock dialog** (`UnlockDialog`) on its own,
   pre-filled if the owner chose *Remember me*. The main window is built but
   stays hidden until the passphrase is accepted. *Lock* (Settings → General)
   hides the window and brings the dialog back; the tray's *Show* brings back
   whichever is current.
3. Unlock or onboarding opens the vault, yields the identity seed, secures the
   venue config with it (seed plus the Etch v3 store key) and provisions the
   model API keys. `launchVenueWith` then runs on a background thread while the
   unlock dialog stays up showing progress ("Starting the venue…"); the main
   window appears only once the venue is actually ready, so it never shows
   without live data behind it.
4. `EmbeddedVenue.launch` starts the `VenueServer` on loopback with public
   access disabled and registers `BrightsideAdapter` and
   `BrightsideSkillsAdapter`. `onVenueReady` binds the identity to the venue
   and starts the chat as `<venueDID>:u:<slug>`: the agent record, the
   conversations, the agents list and the inbox are read in-process, any
   changed filesystem skill is imported and the conversation watcher starts.
   Nothing else is submitted: the agent is created or reconfigured on the
   first message, and Odin when first needed, so a launch writes no jobs.

## Taking over a running instance

Two processes cannot share `venue.etch`; the second fails on the file lock. So
`start()` probes the configured port before any window appears, and
`launchVenueWith` probes it again before launching:

- `Takeover.isRunning(port)` — any HTTP answer from `/api/v1/status` means a
  venue is running; only a refused connection means none. (A private venue may
  answer `401` to strangers; that still counts.)
- The owner is asked *Take Over / Cancel* at startup, before the unlock dialog
  — Cancel exits without a passphrase ever being typed. The unlock dialog then
  notes that the running instance will hand over once unlocked. (An instance
  that appears only later is asked about at launch time instead.)
- The handover itself waits for the vault, since the shutdown request must be
  signed with the seed; it runs from the unlock dialog ("Taking over…") and
  this instance's main window appears only once the other has released the
  store — two main windows are never on screen together.
- `Takeover.requestShutdown` signs a short-lived JWT (`iss = sub = aud =` the
  venue's DID) with the seed both instances unlocked and POSTs
  `v/ops/brightside/shutdown` to `/api/v1/run`. The DID is the one the venue
  reports anonymously when it does; otherwise `Takeover.venueDIDFor(seed)`, the
  `did:key` of the seed's public key — the venue's own derivation on loopback.
- The running instance accepts only the venue operator
  (`BrightsideAdapter.handleShutdown` requires the caller DID to equal the venue
  DID) and runs its `exit()`: the store flushes and the process ends. Another
  identity is refused (`400` or `401`) and the newcomer reports that it could
  not take over.
- `Takeover.waitUntilDown` polls until nothing answers (up to 20 s); then the
  newcomer launches its own venue.

This is not `venue/restart`: the newcomer is already up and only needs the
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

`exit()` stops the watcher, hides the window, removes the tray icon and closes
the `VenueServer` on a background thread before `System.exit`. A Convex
`Shutdown` hook at `SERVER - 10` closes the venue on Ctrl-C and SIGTERM.
`EmbeddedVenue.close()` is idempotent.

## When launch fails

`Venue failed to start` in the log names the cause. `File lock failed on
venue.etch` means another process still holds the store — usually one that is
mid-exit: wait a moment, or quit it from its tray icon. A wrong passphrase is
reported on the unlock screen, not in the log.
