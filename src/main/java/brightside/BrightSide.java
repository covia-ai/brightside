package brightside;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brightside.chat.ChatSession;
import brightside.model.AgentRef;
import brightside.model.Providers;
import brightside.ui.LAF;
import brightside.ui.MainWindow;
import brightside.ui.TrayManager;
import brightside.ui.inbox.InboxBinding;
import convex.core.data.Blob;
import convex.core.data.Index;
import brightside.ui.onboarding.OnboardingWizard;
import brightside.ui.onboarding.UnlockDialog;
import brightside.ui.onboarding.UnlockPanel;
import brightside.ui.settings.SchedulePanel;
import brightside.vault.Vault;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import convex.core.util.Shutdown;

/**
 * BrightSide: a Covia venue on the desktop.
 *
 * <p>Runs an {@link EmbeddedVenue} inside the process and puts a chat window
 * in front of it. The window can be hidden to a system-tray icon so the venue
 * keeps running in the background — explicitly (Hide to tray), or on close /
 * minimise when the owner opts in under Settings → General; by default
 * minimise goes to the taskbar and close quits. Exit — from the tray menu or
 * Settings → General — flushes the venue's state and stops the process.
 *
 * <p>Threading: the UI lives on the Swing event thread; the venue is launched
 * and closed, and the desktop (browser, editor) is opened, on background
 * threads. All public action methods may be called from any thread.
 */
public final class BrightSide {

	private static final Logger log = LoggerFactory.getLogger(BrightSide.class);

	/** The product name shown to the owner; internal names stay stable. */
	public static final String APP_NAME = "Tau";

	/**
	 * Preference keys for the look chosen in Settings → Theme: the mode (light or
	 * dark), each mode's theme (a {@link LAF.Choice#id()}) and the accent.
	 */
	private static final String PREF_MODE = "ui.mode";
	private static final String PREF_THEME_DARK = "ui.theme.dark";
	private static final String PREF_THEME_LIGHT = "ui.theme.light";
	private static final String PREF_ACCENT = "ui.accent";
	/**
	 * Preference keys for Settings → General → Startup: take over a Brightside
	 * found running without asking, and unlock at launch with the remembered
	 * passphrase without waiting for Unlock. {@code --takeover} does both.
	 */
	private static final String PREF_TAKE_OVER = "startup.takeOver";
	private static final String PREF_UNLOCK = "startup.unlock";
	private static final String PREF_AT_SIGN_IN = "startup.atSignIn";
	/** The venue's model listing: each provider's catalog, readiness and live models. */
	private static final String MODELS_OP = "v/ops/langchain/models";

	/** Read at launch, and again by a restart ({@link #prepareRestart}). */
	private volatile AppConfig config;
	private final Path configPath;
	/** Starting at Windows sign-in, which the owner opts into. */
	private final StartAtSignIn signIn;
	private final Prefs prefs;
	private volatile EmbeddedVenue venue;
	/** The disk the running venue's store takes; null until it is up, or for a store that is not a file. */
	private volatile StoreSpace storeSpace;
	private volatile ChatSession chat;
	private volatile InboxBinding inboxBinding; // replaced with the venue or acting principal
	private volatile Identity identity;
	private volatile covia.grid.Venue client; // in-process client for the acting user
	private volatile String userDID; // the acting user's DID (for in-process lattice reads)
	private volatile String agentId;
	private MainWindow window; // event thread only
	private UnlockDialog unlockDialog; // event thread only; non-null exactly while locked
	private TrayManager tray; // event thread only
	private final AtomicBoolean exiting = new AtomicBoolean();
	private final AtomicBoolean chatStarted = new AtomicBoolean();
	/** Whether this launch has brought the gods up to date on a first message yet. */
	private final AtomicBoolean godsEnsured = new AtomicBoolean();
	/** One god ensure at a time, so two cannot both try to create the same god. */
	private final Object godsLock = new Object();
	/** A restart of the venue inside this process is under way ({@link #prepareRestart}). */
	private final AtomicBoolean restarting = new AtomicBoolean();
	/** Lock (log out) has hidden the window, until a passphrase is proven again; read off the event thread. */
	private volatile boolean locked;
	/** What the venue's lifecycle operations ask of this app. */
	private final BrightsideAdapter.App lifecycle = new BrightsideAdapter.App() {
		@Override
		public void shutdown() {
			exit();
		}

		@Override
		public Runnable prepareRestart() throws Exception {
			return BrightSide.this.prepareRestart();
		}

		@Override
		public ProcessHandle relaunch(covia.grid.AContent jar) throws Exception {
			return BrightSide.this.relaunch(jar);
		}
	};
	private final AtomicLong chatBindings = new AtomicLong();
	/** The owner already agreed, at startup, to take over the instance found running. */
	private volatile boolean takeoverApproved;
	/** This launch took over from a running instance, which has gone: nothing else runs if it fails now. */
	private volatile boolean tookOver;
	/** Launched with --unlock: a failed unlock exits rather than waiting at the dialog for someone. */
	private volatile boolean unattended;
	/** Launched with --unlock: come up in the tray (minimised without one), not as a window. Event thread. */
	private boolean startQuiet;
	/** Launched with --takeover: a Brightside found running is taken over, and a remembered passphrase used, without asking. */
	private volatile boolean takeoverAllowed;
	/** Rewrites the token for local tools while the owner keeps one ({@link ToolToken}). */
	private final java.util.concurrent.ScheduledExecutorService toolTokens =
		java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "brightside-tool-token");
			t.setDaemon(true);
			return t;
		});
	private final AtomicBoolean toolTokensStarted = new AtomicBoolean();
	/** A venue launch in progress, for {@link #closeVenue} to wait on; null when none is. */
	private volatile CountDownLatch launching;
	private volatile Thread launchThread;
	private volatile Vault vault; // set once onboarded/unlocked
	private volatile String venueSeedHex; // the venue's Ed25519 seed hex (authorises operator takeover)
	/** A recovery withdrew the tool token before the store was open; recorded there once it is. */
	private volatile boolean toolTokenWithdrawn;
	private volatile String llmOverride; // the chosen model op, kept in Brightside's adapter state

	private enum Mode {
		ONBOARD, UNLOCK
	}

	/** How a chat (re)bind should be presented: first launch, a name change, an agent switch, or a change of acting principal. */
	private enum Bind {
		FIRST, NAME_CHANGE, AGENT_SWITCH, PRINCIPAL_SWITCH
	}

	/**
	 * Identity and request order captured before a background bind starts.
	 * {@code open} is the conversation an agent switch should show, null for the
	 * agent's latest.
	 */
	private record ChatTarget(long generation, String did, String sessionId, boolean operator, String open) {
	}

	private ChatTarget chatTarget(EmbeddedVenue venue, Identity identity, String open) {
		ChatSession current = chat;
		return new ChatTarget(chatBindings.incrementAndGet(), actingDID(identity, venue),
			current == null ? null : current.sessionId(), actingAsOperator, open);
	}

	/**
	 * Whether the app is acting as the venue operator rather than as the named
	 * user (Settings → Identity → Switch user). The operator sees and chats with the
	 * venue's own agents — the gods — and answers the venue's Inbox; everyday use is
	 * as the user. Never persisted: every launch starts as the user.
	 */
	private volatile boolean actingAsOperator;

	BrightSide(AppConfig config, Path configPath, Prefs prefs) {
		this.config = config;
		this.configPath = configPath;
		this.signIn = StartAtSignIn.forThisProcess(configPath);
		this.prefs = prefs;
	}

	/**
	 * What the command line asked for: the configuration file; whether to
	 * unlock at once with a remembered passphrase instead of waiting for someone
	 * to press Unlock ({@code --unlock}), for a launch nobody is sitting at; and
	 * whether to take over a Brightside already running without asking
	 * ({@code --takeover}).
	 */
	record Launch(Path config, boolean unlock, boolean takeover) {
		static Launch parse(String... args) {
			Path config = AppConfig.DEFAULT_FILE;
			boolean unlock = false, takeover = false;
			for (String a : args) {
				if (a.equals("--unlock")) unlock = true;
				else if (a.equals("--takeover")) takeover = true;
				else if (a.startsWith("--")) throw new IllegalArgumentException("Unknown option " + a + " (the options are --unlock and --takeover)");
				else config = Path.of(a).toAbsolutePath().normalize();
			}
			return new Launch(config, unlock, takeover);
		}
	}

	/** Entry point. Arguments: an optional path to the configuration file, {@code --unlock} and {@code --takeover}. */
	public static void main(String[] args) {
		Launch launch;
		try {
			launch = Launch.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(64); // EX_USAGE
			return;
		}
		Path path = launch.config();
		configureLogging(path.getParent());
		// Threads' uncaught exceptions — the event thread's included — go to the
		// log file, not only to a console nobody is watching.
		Thread.setDefaultUncaughtExceptionHandler(
			(thread, e) -> log.error("Uncaught exception in {}", thread.getName(), e));
		AppConfig config;
		try {
			config = AppConfig.load(path);
			log.info("Configuration loaded from {}", path);
		} catch (Exception e) {
			log.error("Could not load configuration from {}", path, e);
			LAF.init(null, AppConfig.DEFAULT_THEME, null, null);
			JOptionPane.showMessageDialog(null,
				"Could not load the configuration file\n" + path + "\n\n" + e.getMessage(),
				APP_NAME, JOptionPane.ERROR_MESSAGE);
			System.exit(66); // EX_NOINPUT, as the Covia venue does
			return;
		}
		// The look chosen in Settings → Theme (remembered in prefs) wins over config.json's light/dark.
		Prefs prefs = Prefs.load(config.home());
		String mode = prefs.getString(PREF_MODE, config.theme());
		LAF.init(prefs.getString(themeKey(mode), null), mode, prefs.getString(PREF_ACCENT, null), themesDir(config));
		new BrightSide(config, path, prefs).start(launch);
	}

	/**
	 * Shows the first screen — the onboarding wizard in the main window for a
	 * new person, or the unlock dialog on its own for a returning one, with the
	 * main window built but hidden until the passphrase is accepted — then
	 * brings the venue up in the background once the vault is open.
	 * <p>
	 * With {@link Launch#unlock}, a launch nobody may be sitting at: a
	 * remembered passphrase is used at once, without waiting for Unlock, and
	 * anything that would need someone, no passphrase remembered or one that
	 * fails, ends the process with an error instead. An instance already
	 * running is left alone unless {@link Launch#takeover} says to take over
	 * from it, which is then done without asking.
	 * <p>
	 * Otherwise someone is there. {@link Launch#takeover}, or the owner's
	 * startup settings, take over without asking and press Unlock with the
	 * remembered passphrase; the dialog still shows the progress, and takes
	 * the passphrase by hand if the remembered one fails.
	 */
	void start(Launch launch) {
		boolean unlock = launch.unlock();
		takeoverAllowed = launch.takeover();
		identity = null; // in the store: read once it is open
		Mode mode = decideMode();

		char[] remembered = null;
		if (unlock) {
			// Started from a command line: nobody to ask, so nothing that would ask.
			if (!takeoverAllowed && Takeover.isRunning(config.port())) {
				log.info("--unlock: something already answers on port {}; leaving it running and exiting", config.port());
				System.out.println(APP_NAME + " is already running on port " + config.port() + ".");
				System.exit(0);
				return;
			}
			remembered = (mode == Mode.UNLOCK) ? RememberedPassphrase.load(config.home()) : null;
			if (remembered == null) {
				String why = (mode == Mode.ONBOARD) ? APP_NAME + " is not set up yet; run it without --unlock first."
					: "No passphrase is remembered; unlock once with Remember me ticked, or run without --unlock.";
				log.error("--unlock: {}", why);
				System.err.println(why);
				System.exit(1);
				return;
			}
			unattended = true;
		}
		char[] passphrase = remembered;

		// Another instance already running? Ask now, before any window — so a
		// Cancel needs no passphrase first. The handover itself waits for the
		// vault: the shutdown request must be signed with the venue seed.
		if (Takeover.isRunning(config.port())) {
			if (unlock && !takeoverAllowed) {
				// An instance may have appeared since the first unattended probe.
				System.exit(0);
				return;
			}
			if (mode == Mode.ONBOARD) {
				// A new install mints a new identity, and only the running venue's
				// own key can ask it to stop: a takeover could never succeed, so
				// say so now rather than after the whole wizard.
				log.info("Something answers on port {} and this install has no vault yet; exiting", config.port());
				alreadyRunning();
				System.exit(0);
				return;
			}
			if (takeOverUnasked()) {
				log.info("Taking over from the instance on port {} without asking", config.port());
			} else if (!confirmTakeover()) {
				log.info("Another " + APP_NAME + " instance is running and takeover was declined; exiting");
				System.exit(0);
				return;
			}
			takeoverApproved = true;
		}

		try {
			SwingUtilities.invokeAndWait(() -> {
				tray = TrayManager.install(this);
				window = new MainWindow(this);
				switch (mode) {
					case ONBOARD -> {
						window.showOnboarding();
						window.setVisible(true);
					}
					case UNLOCK -> {
						if (passphrase == null) {
							char[] prefill = RememberedPassphrase.load(config.home());
							boolean unlockNow = prefill != null && (takeoverAllowed || unlockWithoutAsking());
							showUnlockDialog(prefill);
							if (unlockNow) {
								log.info("Unlocking with the remembered passphrase without asking");
								unlockDialog.unlockNow();
							}
						} else {
							// Started from a command line: nothing on screen. A failure ends the process.
							log.info("--unlock: unlocking with the remembered passphrase");
							startQuiet = true;
							onUnlock(passphrase, true);
						}
					}
				}
			});
		} catch (Exception e) {
			throw new IllegalStateException("Could not create the main window", e);
		}
		// Close the venue on any JVM exit (Ctrl-C, SIGTERM) at the level Covia
		// documents for embedders, before Convex closes its servers and stores.
		Shutdown.addHook(Shutdown.SERVICE, this::closeVenue);
	}

	/** New installs onboard into an encrypted vault; returning installs must unlock one. */
	private Mode decideMode() {
		return Vault.exists(config.home()) ? Mode.UNLOCK : Mode.ONBOARD;
	}

	/** The lock screen as its own small window, the main window hidden behind it. Event thread. */
	private void showUnlockDialog(char[] prefill) {
		if (unlockDialog == null) {
			unlockDialog = new UnlockDialog(new UnlockPanel.Listener() {
				@Override
				public void onUnlock(char[] passphrase, boolean remember) {
					BrightSide.this.onUnlock(passphrase, remember);
				}

				@Override
				public void onForgot() {
					openRecovery();
				}
			}, this::onUnlockClosing);
		}
		unlockDialog.showUnlock(prefill);
		if (takeoverApproved) unlockDialog.showNote("Another " + APP_NAME + " is running; it will hand over once you unlock.");
	}

	/** The passphrase was accepted (or the identity recovered): drop the unlock dialog and bring up the window. */
	private void unlocked() {
		unattended = false; // up: from here on, as any launch (a later Lock asks as usual)
		if (unlockDialog != null) {
			unlockDialog.dispose();
			unlockDialog = null;
		}
		window.showChatStartup();
		if (startQuiet) {
			// An --unlock launch stays out of the way: in the tray, or minimised without one.
			startQuiet = false;
			if (tray == null) {
				window.setExtendedState(window.getExtendedState() | java.awt.Frame.ICONIFIED);
				window.setVisible(true);
			}
			log.info("--unlock: up {}", (tray != null) ? "in the tray" : "minimised");
		} else {
			window.showAndFocus();
		}
	}

	/**
	 * An --unlock launch could not come up: nobody is there to try again, so say
	 * why and end with an error. Once it has taken over, nothing else is running
	 * — a relaunch into a jar too old for the store ends here — so where there is
	 * a desktop it also says why in a dialog, and ends when that is closed.
	 */
	private void unattendedFailed(String message) {
		log.error("--unlock: {}", message);
		System.err.println(message);
		if (tookOver && !java.awt.GraphicsEnvironment.isHeadless()) {
			Runnable tell = () -> JOptionPane.showMessageDialog(null,
				APP_NAME + " took over from the one that was running, then couldn't start:\n\n" + message,
				APP_NAME, JOptionPane.ERROR_MESSAGE);
			try {
				if (SwingUtilities.isEventDispatchThread()) tell.run();
				else SwingUtilities.invokeAndWait(tell);
			} catch (Exception e) {
				if (e instanceof InterruptedException) Thread.currentThread().interrupt();
				log.warn("Could not show why the launch failed", e);
			}
		}
		exit(1);
	}

	/** Unlock or recovery failed: back to the dialog with the reason. Event thread. */
	private void unlockFailed(String message) {
		if (unattended) {
			unattendedFailed(message);
			return;
		}
		if (unlockDialog != null) unlockDialog.showError(message);
		else window.showSystemMessage(message);
	}

	/**
	 * The unlock dialog's close button: the same policy as the window's — stay
	 * in the tray if the owner opted in, otherwise quit.
	 */
	public void onUnlockClosing() {
		if (keepInTray() && tray != null) {
			SwingUtilities.invokeLater(() -> {
				if (unlockDialog != null) unlockDialog.setVisible(false);
				tray.notifyHidden();
			});
		} else {
			exit();
		}
	}

	/**
	 * Brings the venue up. If another instance already holds the store, the
	 * owner is first asked whether to take over — and only once that is settled,
	 * the other instance has released the store <em>and the venue is actually
	 * up</em> does {@code clearToShow} run (on the event thread): the moment the
	 * main window may appear. Two windows are never on screen together, and the
	 * window never shows without live data behind it — while the venue boots,
	 * the unlock dialog stays up showing progress. {@link #closeVenue} waits for
	 * a launch in progress, so a quit mid-boot still ends with a clean store.
	 */
	private void launchVenueWith(AMap<AString, ACell> venueConfig, Runnable clearToShow) {
		CountDownLatch done = new CountDownLatch(1);
		launchThread = Thread.currentThread();
		launching = done;
		try {
			// Quitting meanwhile (during a restart): nothing to open, and the exit,
			// which waits on this launch, must not find a store left open.
			if (exiting.get()) return;
			// Another instance already holds the venue store? Offer to take over
			// (ask it to shut down cleanly) rather than fail on the store lock.
			int port = config.port();
			if (Takeover.isRunning(port)) {
				if (unattended && !takeoverAllowed) {
					// A concurrent launch won while this one was unlocking.
					System.exit(0);
					return;
				}
				boolean proceed;
				try {
					proceed = takeOver(port, storePath(venueConfig));
				} catch (Takeover.Refused e) {
					// Not ours to stop: a different identity, a venue that is not
					// Brightside, or something else on the port. Say which.
					log.warn("Could not take over the instance on port {}: {}", port, e.getMessage());
					startupFailed(e.getMessage());
					return;
				} catch (Exception e) {
					log.warn("Could not take over the running instance on port {}: {}", port, e.toString());
					startupFailed(APP_NAME + " is already running on this computer and couldn't be taken over"
						+ " — please quit it (tray icon ▸ Quit) and try again.");
					return;
				}
				if (!proceed) {
					log.info("Another " + APP_NAME + " instance is running and takeover was cancelled; exiting");
					System.exit(0);
					return;
				}
				tookOver = true;
			}
			// The slow part: opening the encrypted store, adapters, provisioning;
			// and compacting the store first, when that was asked for.
			covia.venue.Config storeConfig = new covia.venue.Config(venueConfig);
			StoreSpace space = StoreSpace.of(storeConfig);
			storeSpace = space;
			boolean compacting = space != null && space.takeRequest();
			progress(compacting ? "Compacting the data store…" : "Starting the venue…");
			EmbeddedVenue v;
			StoreRepair.Outcome repaired = null;
			StoreSpace.Compacted compacted = null;
			try {
				if (compacting) compacted = space.compact(storeConfig);
				v = launchOn(venueConfig, compacted);
			} catch (RuntimeException | IOException e) {
				if (EmbeddedVenue.isNewerStore(e)) {
					log.error("The store is newer than this build", e);
					startupFailed("This data store was written by a newer " + APP_NAME + ". Nothing was changed: start the"
						+ " newer " + APP_NAME + " to open it.");
					return;
				}
				if (StoreRepair.isInterruptedCollection(e)) {
					log.error("The store was left part-way through a collection", e);
					startupFailed(APP_NAME + " was stopped part-way through compacting its data store, and can't open"
						+ " it until that is put right by hand. Nothing has been lost: keep every venue.etch file in "
						+ space.store().getParent() + " as it is; putting it right needs Convex's etch tools.");
					return;
				}
				// A store left open by a crash (or a launch stopped dead) is refused
				// by Etch v3 until it is repaired into a fresh file. Do that here,
				// where the key is in hand, unless the owner has switched it off.
				if (!StoreRepair.isUncleanClose(e)) throw e;
				if (!config.repairStore()) {
					log.error("The store was not closed cleanly and repairStore is off", e);
					startupFailed(APP_NAME + "'s data store wasn't closed cleanly last time. Set \"repairStore\": true"
						+ " in config.json to have it repaired on launch, or restore a backup of venue.etch.");
					return;
				}
				log.warn("The store was not closed cleanly; repairing it before launch");
				progress("Checking the data store…");
				try {
					repaired = StoreRepair.repair(venueConfig);
				} catch (IOException ioe) {
					log.error("Store repair failed", ioe);
					startupFailed(APP_NAME + " couldn't repair its data store: " + ioe.getMessage());
					return;
				}
				log.warn("Store repaired — {}", repaired.summary());
				for (String problem : repaired.problems()) log.warn("Store repair noted: {}", problem);
				progress(compacting ? "Compacting the data store…" : "Starting the venue…");
				if (compacting) compacted = space.compact(storeConfig);
				v = launchOn(venueConfig, compacted);
			}
			venue = v;
			log.info("Venue '{}' ready at {} as {}", v.name(), v.url(), v.did());
			adoptModel(v);
			adoptSettings(v);
			// Bind the identity and move any keys the vault still holds while the
			// dialog is still up, so a failure goes back to it. Only then reveal:
			// the dialog drops and the window comes up in its startup state; the
			// chat bind starts after that, so its own event-thread update is
			// queued behind the reveal and fills in the conversation.
			Identity bound = onVenueReady();
			startToolTokens();
			if (prefs.getBool(PREF_AT_SIGN_IN, false)) applyStartAtSignIn(); // now that this build has started, name it
			if (clearToShow != null) SwingUtilities.invokeLater(clearToShow);
			if (bound != null) startChatOnce(v, bound);
			if (bound != null) {
				// The same guard Settings → Model applies: a model whose provider has
				// no key would otherwise fail the first message with a raw error.
				String op = currentModelOp();
				Providers.Provider keyless = keylessProvider(op, this::keyResolves);
				if (keyless != null) {
					String notice = "No " + keyless.label() + " API key is set, so " + op
						+ " can't answer yet. Add the key in Settings → Model and it takes effect straight away.";
					SwingUtilities.invokeLater(() -> window.showSystemMessage(notice));
				}
			}
			for (String problem : v.startup().problems()) {
				SwingUtilities.invokeLater(() -> window.showSystemMessage(problem));
			}
			if (repaired != null) {
				String notice = repaired.notice();
				SwingUtilities.invokeLater(() -> window.showSystemMessage(notice));
			}
			if (compacted != null) {
				String notice = compacted.notice();
				SwingUtilities.invokeLater(() -> window.showSystemMessage(notice));
			}
		} catch (Throwable t) {
			log.error("Venue failed to start", t);
			closeVenue();
			venue = null;
			startupFailed(t);
		} finally {
			launching = null;
			launchThread = null;
			done.countDown();
		}
	}

	/** Launches the venue on the store {@code compacted} opened, or else on the one the config names. */
	private EmbeddedVenue launchOn(AMap<AString, ACell> venueConfig, StoreSpace.Compacted compacted) {
		return (compacted != null) ? EmbeddedVenue.launch(venueConfig, compacted.store(), lifecycle)
			: EmbeddedVenue.launch(venueConfig, lifecycle);
	}

	/**
	 * Checks that the venue can restart inside this process, for
	 * {@code brightside:restart}, and returns the restart ({@link #restartVenue}).
	 * {@code config.json} is read again now, so a file that cannot be read, or
	 * venue settings Covia refuses, stop the restart before anything stops.
	 * Refused while exiting, before the venue is up and during another restart.
	 */
	private Runnable prepareRestart() throws Exception {
		if (exiting.get()) throw new covia.exception.ConflictException(APP_NAME + " is shutting down");
		String seedHex = venueSeedHex;
		if (venue == null || seedHex == null || launching != null) {
			throw new covia.exception.ConflictException(APP_NAME + " is still starting up");
		}
		AppConfig fresh;
		try {
			fresh = AppConfig.load(configPath);
			new covia.venue.Config(fresh.venueConfig());
		} catch (Exception e) {
			throw new covia.exception.ConfigurationException("config.json could not be used, so nothing was restarted: " + e.getMessage(), e);
		}
		if (!restarting.compareAndSet(false, true)) throw new covia.exception.ConflictException("A restart is already under way");
		return () -> restartVenue(fresh, seedHex);
	}

	/**
	 * The restart itself, once {@code brightside:restart} has answered. It lets
	 * go of the venue as Lock does, but keeps the key: the window shows Home
	 * starting up, or the unlock dialog its progress while locked. Then it closes
	 * the venue, saving the store, and launches it again through the startup
	 * path on the configuration just read. The chat binds to the new venue as
	 * at launch — as the named user, on a clean Home with the standard agent,
	 * the conversations waiting in Sessions — and while locked, at the unlock.
	 * A launch that fails is reported where the owner is looking, as at startup.
	 */
	private void restartVenue(AppConfig fresh, String seedHex) {
		try {
			log.info("Restarting the venue");
			chatBindings.incrementAndGet(); // a bind still in flight is for the old venue
			InboxBinding oldInbox = inboxBinding;
			inboxBinding = null;
			chat = null;
			client = null;
			actingAsOperator = false; // every launch starts as the user
			chatStarted.set(false);
			godsEnsured.set(false);
			SwingUtilities.invokeAndWait(() -> {
				if (oldInbox != null) oldInbox.close();
				window.clearJobs();
				window.closeSessions();
				if (unlockDialog != null) unlockDialog.showProgress("Restarting…");
				else window.showChatStartup();
			});
			closeVenue();
			venue = null;
			config = fresh;
			launchVenueWith(Vault.secure(fresh.venueConfig(), seedHex), this::restarted);
			renewToolToken(); // signed by the new venue, in case one was refreshed while it was down
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			log.error("Restart failed", e);
			startupFailed(e);
		} finally {
			restarting.set(false);
		}
	}

	/** The venue is back after a restart. Event thread. */
	private void restarted() {
		// Still locked: back to asking, and the unlock binds the chat to the new venue.
		if (unlockDialog != null) unlockDialog.showUnlock(null);
	}

	/**
	 * Starts the Brightside that takes over from this one, for
	 * {@code brightside:relaunch}: the same configuration, and the same program
	 * or {@code jar}, copied into the run directory first ({@link Relaunch#stage}),
	 * coming back as this one is ({@link #relaunchOptions}). Refused while locked —
	 * unlocking at once would undo the lock, and the newcomer cannot take over
	 * without the key — and while exiting. Never on the event thread.
	 */
	private ProcessHandle relaunch(covia.grid.AContent jar) throws Exception {
		if (exiting.get()) throw new covia.exception.ConflictException(APP_NAME + " is already shutting down");
		boolean[] asking = { false }, showing = { false };
		SwingUtilities.invokeAndWait(() -> {
			asking[0] = unlockDialog != null;
			showing[0] = window != null && window.isShowing()
				&& (window.getExtendedState() & java.awt.Frame.ICONIFIED) == 0;
		});
		if (asking[0]) throw new covia.exception.ConflictException(APP_NAME + " is locked; it can relaunch once the owner unlocks it");
		List<String> options = relaunchOptions(showing[0], RememberedPassphrase.exists(config.home()));
		if (jar == null) {
			log.info("Relaunching: starting a new " + APP_NAME + " with {}", options);
			return Relaunch.start(configPath, options).toHandle();
		}
		Path staged = Relaunch.stage(jar, config.home());
		log.info("Relaunching: starting a new " + APP_NAME + " from {} with {}", staged, options);
		return Relaunch.start(staged, configPath, options).toHandle();
	}

	/**
	 * How a relaunch starts its successor so that it comes back as this one is,
	 * taking over without asking and unlocking with the remembered passphrase at
	 * once. With the window showing, {@code --takeover}: the window comes back.
	 * In the tray, or minimised, with a passphrase remembered,
	 * {@code --unlock --takeover}: it comes back there. With none remembered it
	 * must ask, so {@code --takeover} again: its unlock dialog asks for the
	 * passphrase, and this instance runs on until it is given.
	 */
	static List<String> relaunchOptions(boolean windowShowing, boolean passphraseRemembered) {
		return (!windowShowing && passphraseRemembered) ? List.of("--unlock", "--takeover") : List.of("--takeover");
	}

	/** The persistent store {@code venueConfig} names, or null for a temp or memory store. */
	private static Path storePath(AMap<AString, ACell> venueConfig) {
		String store = new covia.venue.Config(venueConfig).getStore();
		if (store == null || store.isBlank() || "temp".equals(store) || "memory".equals(store)) return null;
		return Path.of(store).toAbsolutePath().normalize();
	}

	/** What the app is doing, on the unlock dialog while it is up. Any thread. */
	private void progress(String message) {
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showProgress(message);
		});
	}

	/** Startup failed: say so where the owner is looking — the unlock dialog while locked, else the window. */
	private void startupFailed(String message) {
		if (unattended) {
			unattendedFailed(message);
			return;
		}
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showError(message);
			else window.startupFailed(message);
		});
	}

	private void startupFailed(Throwable t) {
		if (unattended) {
			unattendedFailed(APP_NAME + " couldn't start up: " + t + " (see the logs)");
			return;
		}
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showError("Sorry — " + APP_NAME + " couldn't start up. See the logs.");
			else window.startupFailed(t);
		});
	}

	/**
	 * First-run onboarding is done: derive the vault, store the encrypted seed,
	 * remember the name and model, and bring up the encrypted venue. Called on the
	 * event thread; the work runs off it.
	 */
	public void onOnboardingComplete(OnboardingWizard.Setup setup) {
		Thread t = new Thread(() -> {
			char[] passphrase = setup.passphrase();
			try {
				Path home = config.home();
				Vault v = Vault.open(home, passphrase);
				v.storeSeed(setup.seedHex());
				this.vault = v;
				this.venueSeedHex = setup.seedHex();

				identity = Identity.of(setup.name());
				persistIdentity(identity);

				// The API key is staged encrypted in the vault only until the venue
				// is up; onVenueReady moves it into the encrypted secret stores.
				if (setup.apiKey() != null && setup.providerId() != null) {
					String secretName = Providers.byId(setup.providerId()).secretName();
					if (secretName != null) v.storeApiKey(secretName, setup.apiKey());
				}
				AMap<AString, ACell> venueConfig = Vault.secure(config.venueConfig(), setup.seedHex());
				llmOverride = (setup.providerId() == null)
					? AppConfig.ECHO_LLM_OPERATION
					: Providers.modelOp(setup.providerId(), setup.modelId());

				// The wizard is already on screen in the main window; nothing to reveal.
				launchVenueWith(venueConfig, null);
			} catch (Exception e) {
				log.error("Onboarding failed", e);
				startupFailed(e);
			} finally {
				java.util.Arrays.fill(passphrase, '\0');
			}
		}, "brightside-onboard");
		t.setDaemon(true);
		t.start();
	}

	/** Returning user entered a passphrase: derive the vault, decrypt the seed, launch. */
	public void onUnlock(char[] passphrase, boolean remember) {
		Thread t = new Thread(() -> {
			try {
				Path home = config.home();
				Vault v = Vault.open(home, passphrase);
				String seedHex = v.seedHex(); // throws on a wrong passphrase (GCM tag)
				String runningSeed = this.venueSeedHex;
				if (runningSeed != null && !runningSeed.equals(seedHex)) {
					throw new IOException("The unlocked identity does not match the running venue");
				}
				// Only now that the passphrase is proven correct: honour Remember me.
				if (remember) {
					try {
						RememberedPassphrase.store(home, passphrase);
					} catch (Exception e) {
						log.warn("Could not store the remembered passphrase: {}", e.getMessage());
					}
				} else {
					forgetRememberedPassphrase();
				}
				this.vault = v;
				this.venueSeedHex = seedHex;
				locked = false; // proven: a venue launched from here binds the chat
				EmbeddedVenue up = venue;
				Identity saved = (up != null) ? owner(up) : null;
				this.identity = saved;

				EmbeddedVenue running = venue;
				if (running != null) {
					if (saved == null) throw new IOException("The saved user identity is missing");
					saved = bindIdentityToVenue(saved, running);
					chatStarted.set(true);
					SwingUtilities.invokeLater(this::unlocked);
					startChat(running, saved, effectiveChat(), Bind.FIRST);
					return;
				}
				AMap<AString, ACell> venueConfig = Vault.secure(config.venueConfig(), seedHex);
				// The window appears only once any takeover is settled.
				launchVenueWith(venueConfig, this::unlocked);
			} catch (IOException e) {
				SwingUtilities.invokeLater(() -> unlockFailed("That passphrase didn't work."));
			} catch (Exception e) {
				log.error("Unlock failed", e);
				SwingUtilities.invokeLater(() -> unlockFailed("Couldn't unlock — see the logs."));
			} finally {
				java.util.Arrays.fill(passphrase, '\0');
			}
		}, "brightside-unlock");
		t.setDaemon(true);
		t.start();
	}

	/** Whether a logged-in user may request a passphrase-gated private-seed export. */
	public boolean canRevealPrivateSeed() {
		return identity != null && vault != null && venueSeedHex != null;
	}

	/**
	 * Re-authenticates with the vault passphrase before returning the venue's
	 * Ed25519 seed for an explicit Advanced export.
	 */
	public String revealPrivateSeed(char[] passphrase) throws IOException {
		try {
			if (!canRevealPrivateSeed()) throw new IOException("No logged-in identity");
			String candidate = Vault.open(config.home(), passphrase).seedHex();
			String runningSeed = venueSeedHex;
			if (runningSeed == null || !runningSeed.equals(candidate)) {
				throw new IOException("The passphrase did not unlock the running identity");
			}
			if (!canRevealPrivateSeed()) throw new IOException("The user logged out during re-authentication");
			return candidate;
		} finally {
			java.util.Arrays.fill(passphrase, '\0');
		}
	}

	/** The identity's Ed25519 public key (hex), derived from the seed; null if unavailable. */
	public String publicKeyHex() {
		if (!canRevealPrivateSeed()) return null;
		String seed = venueSeedHex;
		try {
			return convex.core.crypto.AKeyPair.create(convex.core.data.Blob.fromHex(seed.trim()))
				.getAccountKey().toHexString();
		} catch (Exception e) {
			return null;
		}
	}

	/** Logs out the current user while leaving the embedded venue running. */
	public void logout() {
		if (vault == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("This install has no passphrase to lock behind."));
			return;
		}
		locked = true;
		chatBindings.incrementAndGet();
		InboxBinding oldInbox = inboxBinding;
		inboxBinding = null;
		chat = null;
		client = null;
		userDID = null;
		agentId = null;
		identity = null;
		vault = null;
		actingAsOperator = false;
		chatStarted.set(false);
		renewToolToken(); // with nobody unlocked, that is none
		SwingUtilities.invokeLater(() -> {
			if (oldInbox != null) oldInbox.close();
			if (tray != null) tray.setPending(false);
			window.clearJobs();
			window.userLoggedOut();
			window.setVisible(false);
			showUnlockDialog(null);
		});
	}

	/** Opens recovery (Forgot passphrase?) over the unlock dialog: restore identity from the recovery phrase. */
	public void openRecovery() {
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.openRecovery(this::recover);
		});
	}

	/**
	 * Recover from a BIP39 recovery phrase. The store's encryption key is derived
	 * from the seed, so the recovery phrase reopens the <em>existing</em> encrypted
	 * store — recovery just re-encrypts the seed under a new passphrase. Runs from
	 * the unlock screen, where no venue is holding the store.
	 */
	public void recover(String seedHex, char[] passphrase) {
		Thread t = new Thread(() -> {
			try {
				Path home = config.home();
				Vault v = Vault.open(home, passphrase); // new passphrase, existing salt
				String runningSeed = venueSeedHex;
				if (runningSeed != null && !runningSeed.equals(seedHex)) {
					throw new IOException("That recovery phrase does not match the running venue");
				}
				if (runningSeed == null) {
					v.verifyStoreAccess(config.venueConfig(), seedHex);
				}
				// The old API-key store was encrypted under the forgotten passphrase and
				// can't be read now; drop it (keys can be re-entered in Settings). The
				// venue store itself is keyed to the seed, so it stays and reopens intact.
				Files.deleteIfExists(home.resolve(Vault.KEYS_FILE));
				forgetRememberedPassphrase();
				// Like the remembered passphrase, the tool token is something to opt back into.
				if (appState() != null) setFlag(BrightsideAdapter.KEEP_TOOL_TOKEN, false);
				else toolTokenWithdrawn = true; // the store is not open yet: withdrawn once it is
				ToolToken.clear(home);
				v.storeSeed(seedHex); // re-encrypt the seed under the new passphrase
				this.vault = v;
				this.venueSeedHex = seedHex;
				locked = false; // proven: a venue launched from here binds the chat
				EmbeddedVenue up = venue;
				Identity saved = (up != null) ? owner(up) : null;
				this.identity = saved;
				EmbeddedVenue running = venue;
				if (running != null) {
					if (saved == null) throw new IOException("The saved user identity is missing");
					saved = bindIdentityToVenue(saved, running);
					chatStarted.set(true);
					SwingUtilities.invokeLater(this::unlocked);
					startChat(running, saved, effectiveChat(), Bind.FIRST);
					return;
				}
				AMap<AString, ACell> venueConfig = Vault.secure(config.venueConfig(), seedHex);
				launchVenueWith(venueConfig, this::unlocked);
			} catch (Exception e) {
				log.error("Recovery failed", e);
				SwingUtilities.invokeLater(() -> unlockFailed("Recovery failed — see the logs."));
			} finally {
				java.util.Arrays.fill(passphrase, '\0');
			}
		}, "brightside-recover");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * One-way migration: provider keys still in the vault's {@code keys.enc}
	 * move into the encrypted secret stores — the acting user's and the
	 * operator's, so each user's agents resolve their own key and the gods the
	 * operator's — without overwriting a value already set in a store (a store
	 * edit wins). {@code keys.enc} is then removed: the stores are the single
	 * home for API keys, and they survive passphrase recovery where the vault
	 * copy never did. Package-visible for the migration test.
	 */
	static void migrateProviderKeys(EmbeddedVenue v, Vault vlt, String userDID) {
		try {
			Map<String, String> keys = vlt.apiKeys();
			if (!keys.isEmpty()) {
				for (Map.Entry<String, String> e : keys.entrySet()) {
					seedIfAbsent(v, userDID, e.getKey(), e.getValue());
					seedIfAbsent(v, v.did(), e.getKey(), e.getValue());
				}
				log.info("Moved {} provider key(s) from the vault into the encrypted secret stores", keys.size());
			}
			vlt.clearApiKeys();
		} catch (Exception e) {
			log.warn("Could not migrate provider keys into the secret stores: {}", e.toString());
		}
	}

	private static void seedIfAbsent(EmbeddedVenue v, String did, String name, String value) {
		v.secrets(did, true).storeIfAbsent(Strings.create(name), Strings.create(value), v.secretKey());
	}

	/** The provider of {@code modelOp} when it needs an API key that {@code resolves} does not find, else null. */
	static Providers.Provider keylessProvider(String modelOp, Predicate<String> resolves) {
		Providers.Provider p = Providers.byId(Providers.providerOf(modelOp));
		return (p != null && p.needsApiKey() && !resolves.test(p.secretName())) ? p : null;
	}

	/** Whether {@code s/<name>} currently resolves for the named user's agents. */
	private boolean keyResolves(String name) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null || name == null) return false;
		try {
			String key = v.engine().resolveSecret("s/" + name,
				RequestContext.of(Strings.create(id.userDID(v.did()))));
			return key != null && !key.isBlank();
		} catch (Exception e) {
			return false;
		}
	}

	// ------------------------------------------------------------------
	// Settings (model & API key)
	// ------------------------------------------------------------------

	/** The model operation the chat is currently using ({@code v/models/<provider>/<id>}). */
	public String currentModelOp() {
		return effectiveChat().llmOperation();
	}

	/** Apply a chosen model live (re-configures the running agent) and persist it. */
	public void applyModel(String providerId, String modelId) {
		String op = Providers.modelOp(providerId, modelId);
		llmOverride = op;
		EmbeddedVenue v = venue;
		BrightsideAdapter app = (v != null) ? v.brightside() : null;
		if (app != null) {
			app.saveModel(op);
			v.durable();
		}
		Providers.Provider keyless = keylessProvider(op, this::keyResolves);
		if (keyless != null) {
			// Saved, but no key resolves for this provider yet — switching the
			// running agent to it now would fail at the model call. A key added
			// in Settings → Model takes effect immediately, so no restart needed.
			String label = keyless.label();
			SwingUtilities.invokeLater(() -> window.showSystemMessage(
				"Saved. Add the " + label + " API key (Settings → Model) and it takes effect straight away."));
			return;
		}
		ensureGods(venue);
		ChatSession c = chat;
		if (c == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				// A reply in flight means agent:update is rejected (RUNNING); the
				// session then re-applies it before the next message goes out.
				note = c.reconfigure(effectiveChat())
					? "Now using " + op + "."
					: "Saved — " + op + " will be used from your next message.";
			} catch (Exception e) {
				log.warn("Could not apply the model change", e);
				note = "Saved " + op + ", but it couldn't be applied yet: " + e.getMessage();
			}
			String text = note;
			SwingUtilities.invokeLater(() -> window.showSystemMessage(text));
		}, "brightside-model");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Stores a provider API key in the encrypted secret stores — the named
	 * user's and the operator's, so the chat agent and the gods all resolve it —
	 * effective immediately. A different per-user value can be set in
	 * Settings → Secrets. Returns false if it couldn't.
	 */
	public boolean storeApiKey(String providerId, String apiKey) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		Providers.Provider p = Providers.byId(providerId);
		if (v == null || id == null || p == null || p.secretName() == null
				|| apiKey == null || apiKey.isBlank()) return false;
		try {
			String value = apiKey.trim();
			AString owner = Strings.create(id.userDID(v.did())), operator = Strings.create(v.did());
			covia.venue.SecretStore.Keys keys = v.secretKey();
			// The owner's agents and the gods use the same key: both copies change together.
			v.engine().getVenueState().execute(state -> {
				state.users().ensure(owner).secrets().store(p.secretName(), value, keys);
				state.users().ensure(operator).secrets().store(p.secretName(), value, keys);
			});
			v.durable();
			return true;
		} catch (Exception e) {
			log.warn("Could not store the API key", e);
			return false;
		}
	}

	// ------------------------------------------------------------------
	// Integrations (Discord)
	// ------------------------------------------------------------------

	/** The owner's in-process client — the named user even while the app acts as the operator. */
	private Venue ownerClient(EmbeddedVenue v, Identity id) {
		return v.clientAs(id.userDID(v.did()));
	}

	/**
	 * Reads, off the event thread, the models the venue catalogues at
	 * {@code v/models} and asks it which each provider serves right now beyond
	 * that, and offers both in the model pickers. Both are reads: the
	 * catalogue straight from the lattice, the listing a read-only operation.
	 * A listing that goes unanswered still leaves the catalogue offered.
	 */
	public void refreshServedModels() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		Thread t = new Thread(() -> {
			Map<String, List<String>> catalogued = Providers.catalogued(v.resolve(id.userDID(v.did()), "v/models"));
			Map<String, List<String>> served = Map.of();
			try {
				ACell report = ownerClient(v, id).run(MODELS_OP, Maps.empty()).get(30, TimeUnit.SECONDS);
				served = Providers.served(report);
			} catch (Exception e) {
				log.warn("Could not list the models the venue serves: {}", e.toString());
			}
			Map<String, List<String>> offered = Providers.offered(catalogued, served);
			if (!offered.isEmpty()) SwingUtilities.invokeLater(() -> window.showServedModels(offered));
		}, "brightside-served-models");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Reads the acting principal's scheduled operations off the event thread
	 * and shows them in Settings → Schedule: the owner's, or the venue's own
	 * while acting as the operator. A failed read is shown as one, never as an
	 * empty schedule.
	 */
	public void refreshSchedule() {
		Venue reader = scheduleClient();
		if (reader == null) {
			SwingUtilities.invokeLater(() -> window.showSchedule(List.of(), APP_NAME + " is still starting up."));
			return;
		}
		Thread t = new Thread(() -> {
			List<Schedule.Event> events = null;
			String error = null;
			try {
				events = Schedule.list(reader);
			} catch (Exception e) {
				log.warn("Could not read the schedule: {}", e.toString());
				error = rootMessage(e);
			}
			List<Schedule.Event> shown = events;
			String note = error;
			SwingUtilities.invokeLater(() -> window.showSchedule(shown, note));
		}, "brightside-schedule");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Runs a scheduled event now, as the acting principal, off the event
	 * thread. Waits a little for the run so the note can say how it went; a
	 * longer run carries on and its outcome shows on the next refresh.
	 */
	public void runScheduled(Schedule.Event event, SchedulePanel.Result done) {
		scheduleAction("brightside-schedule-run", done, reader -> {
			ACell result = Schedule.runNow(reader, event.handle(), 10_000);
			return (result != null) ? "Ran " + event.label() + "."
				: event.label() + " is running; its outcome shows when it finishes.";
		}, "Couldn't run " + event.label() + ": ");
	}

	/** Schedules a new event as the acting principal, off the event thread. */
	public void scheduleNew(String operation, ACell input, long afterMillis, long everyMillis, boolean track,
			SchedulePanel.Result done) {
		scheduleAction("brightside-schedule-new", done, reader -> {
			Schedule.schedule(reader, operation, input, afterMillis, everyMillis, track);
			return "Scheduled " + Schedule.label(reader, operation) + ".";
		}, "Couldn't schedule it: ");
	}

	/** Cancels a scheduled event as the acting principal, off the event thread. */
	public void cancelScheduled(Schedule.Event event, SchedulePanel.Result done) {
		scheduleAction("brightside-schedule-cancel", done, reader -> Schedule.cancel(reader, event.handle())
			? "Cancelled " + event.label() + "." : event.label() + " had already gone.",
			"Couldn't cancel " + event.label() + ": ");
	}

	private interface ScheduleAction {
		String run(Venue reader) throws Exception;
	}

	private void scheduleAction(String thread, SchedulePanel.Result done, ScheduleAction action, String failure) {
		Venue reader = scheduleClient();
		if (reader == null) {
			done.done(APP_NAME + " is still starting up.", true);
			return;
		}
		Thread t = new Thread(() -> {
			String note;
			boolean error = false;
			try {
				note = action.run(reader);
			} catch (Exception e) {
				log.warn("Scheduled event action failed: {}", e.toString());
				note = failure + rootMessage(e);
				error = true;
			}
			String n = note;
			boolean err = error;
			SwingUtilities.invokeLater(() -> done.done(n, err));
		}, thread);
		t.setDaemon(true);
		t.start();
	}

	/**
	 * The acting person's Peers map settings: kept by Brightside's adapter on
	 * the venue for whoever is acting (the owner, or the operator), or held in
	 * memory until the venue is up.
	 */
	public brightside.peers.MapSettingsStore peersMapSettings() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null) return brightside.peers.MapSettingsStore.memory();
		String did = userDID;
		if (did == null && id != null) did = id.userDID(v.did());
		return (did == null) ? brightside.peers.MapSettingsStore.memory() : v.mapSettings(did);
	}

	/**
	 * Your venue as peer data, for the Peers map: the venue, its operator, the
	 * named user and each one's agents, read in-process, so opening the tab
	 * leaves no job records. Empty until the venue and identity are up.
	 */
	public List<brightside.peers.Node> homePeers() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		String venueDID = (v == null) ? null : v.did();
		if (venueDID == null || id == null) return List.of();
		String userDID = id.userDID(venueDID);
		return brightside.peers.HomePeers.of(venueDID, v.name(), id.operatorName(), v.agentsForMap(venueDID, null),
			userDID, id.name(), v.agentsForMap(userDID, config.chat().agentId()), config.chat().agentId());
	}

	/** The acting principal's client for the schedule: the bound chat principal, else the owner. */
	private Venue scheduleClient() {
		Venue acting = client;
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (acting == null && v != null && id != null) acting = ownerClient(v, id);
		return acting;
	}

	/** Reads the owner's Discord bot off the event thread and shows it in Settings → Integrations. */
	public void refreshDiscordStatus() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) {
			SwingUtilities.invokeLater(() -> window.showDiscordStatus(null, null));
			return;
		}
		Thread t = new Thread(() -> reportDiscord(v, id, null), "brightside-discord-status");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Stores the bot token (in the owner's encrypted secret store, via
	 * {@link Discord#configure}) and creates or replaces the owner's Discord
	 * bot live, answering as the configured chat agent for the listed Discord
	 * users. {@code token} null keeps the stored one.
	 */
	public void saveDiscord(String token, List<String> allow) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				Discord.configure(ownerClient(v, id), config.chat().agentId(), token, allow);
				note = "Saved. The bot connects in a moment; message it on Discord to try it.";
			} catch (Exception e) {
				log.warn("Could not set up the Discord bot", e);
				note = "Couldn't set up the bot: " + rootMessage(e);
			}
			reportDiscord(v, id, note);
		}, "brightside-discord-save");
		t.setDaemon(true);
		t.start();
	}

	/** Disconnects and deletes the owner's bot and its Discord conversations. */
	public void removeDiscord() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				Discord.remove(ownerClient(v, id));
				note = "Removed.";
			} catch (Exception e) {
				log.warn("Could not remove the Discord bot", e);
				note = "Couldn't remove the bot: " + rootMessage(e);
			}
			reportDiscord(v, id, note);
		}, "brightside-discord-remove");
		t.setDaemon(true);
		t.start();
	}

	/** Reads the bot's state and record and hands both to the window with {@code note}. */
	private void reportDiscord(EmbeddedVenue v, Identity id, String note) {
		Discord.Bot bot = null;
		String n = note;
		try {
			String did = id.userDID(v.did());
			bot = Discord.status(v.clientAs(did));
		} catch (Exception e) {
			log.warn("Could not read the Discord bot: {}", e.toString());
			if (n == null) n = "Couldn't read the bot: " + rootMessage(e);
		}
		Discord.Bot shown = bot;
		String shownNote = n;
		SwingUtilities.invokeLater(() -> window.showDiscordStatus(shown, shownNote));
	}

	// ------------------------------------------------------------------
	// Moltbook (Settings → Integrations → Moltbook)
	//
	// The key has one home: the owner's encrypted secret store inside the
	// venue store, which is keyed from the identity seed and so outlives a
	// forgotten-passphrase recovery (that deletes keys.enc). It deliberately
	// does not go in the vault — provisionKeys publishes every vault key as a
	// venue-wide secret, and this one is the owner's alone. Both this page and
	// the assistant's own registration (MoltbookAdapter) write the same store.
	// ------------------------------------------------------------------

	/** Reads the owner's Moltbook account off the event thread and shows it in Settings → Integrations. */
	public void refreshMoltbookStatus() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) {
			SwingUtilities.invokeLater(() -> window.showMoltbookStatus(null, null));
			return;
		}
		Thread t = new Thread(() -> reportMoltbook(v, id, null), "brightside-moltbook-status");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Registers the assistant on Moltbook as {@code name}, keeps the key in the
	 * owner's venue secret store and remembers the claim page the owner must
	 * visit to activate the account.
	 */
	public void registerMoltbook(String name, String description) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				Moltbook.Registration r = Moltbook.register(name, description);
				keepMoltbookKey(v, id, r.apiKey(), name, r.claimUrl(), r.verificationCode());
				note = "Registered. Open the claim page and finish as the owner — an email, then a tweet.";
			} catch (Exception e) {
				log.warn("Could not register on Moltbook", e);
				note = "Couldn't register: " + rootMessage(e);
			}
			reportMoltbook(v, id, note);
		}, "brightside-moltbook-register");
		t.setDaemon(true);
		t.start();
	}

	/** Connects an account that already exists, from its API key (the owner dashboard can rotate one). */
	public void connectMoltbook(String apiKey) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null || apiKey == null || apiKey.isBlank()) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				Moltbook.Account account = Moltbook.lookup(apiKey, null);
				keepMoltbookKey(v, id, apiKey, account.name(), null, null);
				note = "Connected as " + account.name() + ".";
			} catch (Exception e) {
				log.warn("Could not connect the Moltbook account", e);
				note = "Couldn't connect: " + rootMessage(e);
			}
			reportMoltbook(v, id, note);
		}, "brightside-moltbook-connect");
		t.setDaemon(true);
		t.start();
	}

	/** Forgets the key and the record here; the account itself stays on Moltbook for the owner. */
	public void forgetMoltbook() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				Venue user = ownerClient(v, id);
				Moltbook.forgetKey(user);
				MoltbookAdapter moltbook = v.moltbook();
				if (moltbook != null) moltbook.clearRecord(Strings.create(id.userDID(v.did())));
				note = "Forgotten here. The account is still yours on Moltbook; connect it again with its key any time.";
			} catch (Exception e) {
				log.warn("Could not forget the Moltbook account", e);
				note = "Couldn't forget it: " + rootMessage(e);
			}
			reportMoltbook(v, id, note);
		}, "brightside-moltbook-forget");
		t.setDaemon(true);
		t.start();
	}

	private void keepMoltbookKey(EmbeddedVenue v, Identity id, String apiKey, String name,
			String claimUrl, String verificationCode) throws Exception {
		Venue user = ownerClient(v, id);
		Moltbook.storeKey(user, apiKey.trim());
		MoltbookAdapter moltbook = v.moltbook();
		if (moltbook != null) {
			moltbook.saveRecord(Strings.create(id.userDID(v.did())), Moltbook.record(name, claimUrl, verificationCode));
		}
		v.durable();
	}

	/**
	 * Reads the account (Moltbook's view, with the record's claim page while
	 * pending) and hands it to the window. The key comes from the owner's
	 * secret store in the venue, which the assistant's own registration writes
	 * too — the same resolution the Moltbook operations use.
	 */
	private void reportMoltbook(EmbeddedVenue v, Identity id, String note) {
		Moltbook.Account account = null;
		try {
			String did = id.userDID(v.did());
			String key = v.engine().resolveSecret(MoltbookAdapter.SECRET_REF, RequestContext.of(Strings.create(did)));
			if (key != null && !key.isBlank()) {
				MoltbookAdapter moltbook = v.moltbook();
				ACell record = (moltbook != null) ? moltbook.record(Strings.create(did)) : null;
				try {
					account = Moltbook.lookup(key, record);
				} catch (Exception e) {
					log.warn("Could not read the Moltbook account: {}", e.toString());
					account = Moltbook.fromRecord(record, rootMessage(e));
				}
			}
		} catch (Exception e) {
			log.warn("Could not read the Moltbook key: {}", e.toString());
		}
		Moltbook.Account shown = account;
		SwingUtilities.invokeLater(() -> window.showMoltbookStatus(shown, note));
	}

	/**
	 * Mints a venue-signed access-token JWT authenticating the bearer as the
	 * current named user, valid for {@code expiresInSeconds}. The home venue is
	 * the signing authority ({@code iss}) and audience, but the stable user DID is
	 * the subject ({@code sub}); Settings must not silently hand a user the venue
	 * operator's authority. Returns null if the venue isn't up yet or no seed is
	 * available.
	 */
	public String mintAccessToken(long expiresInSeconds, boolean asOperator) {
		if (identity == null || vault == null) return null;
		EmbeddedVenue v = venue;
		String seed = venueSeedHex;
		if (v == null || seed == null || seed.isBlank()) return null;
		try {
			String venueDID = v.did();
			String subjectDID = asOperator ? venueDID : identity.userDID(venueDID);
			long now = System.currentTimeMillis() / 1000;
			return signAccessToken(seed, venueDID, subjectDID, now, expiresInSeconds);
		} catch (Exception e) {
			log.warn("Could not mint an access token", e);
			return null;
		}
	}

	/** Signs the claims of a login made in Settings → Access. Package-visible for claim-level tests. */
	static String signAccessToken(String seedHex, String venueDID, String subjectDID,
			long issuedAt, long expiresInSeconds) {
		convex.core.crypto.AKeyPair keyPair =
			convex.core.crypto.AKeyPair.create(convex.core.data.Blob.fromHex(seedHex.trim()));
		return convex.auth.jwt.JWT.signPublic(Maps.of(
			"sub", subjectDID, "iss", venueDID, "aud", venueDID,
			"iat", issuedAt, "exp", issuedAt + expiresInSeconds), keyPair).toString();
	}

	// ------------------------------------------------------------------
	// Secrets (Settings → Secrets): the acting user's encrypted store
	// ------------------------------------------------------------------

	/** A storable secret name: what {@code s/<name>} references can carry. */
	public static boolean validSecretName(String name) {
		return name != null && name.matches("[A-Za-z0-9_.-]{1,64}");
	}

	/** The acting principal's DID for the secrets page: the bound chat principal, else the named user. */
	private String secretsDID(EmbeddedVenue v) {
		String did = userDID;
		if (did != null) return did;
		Identity id = identity;
		return (id == null) ? null : id.userDID(v.did());
	}

	/** The names in the acting user's encrypted secret store, sorted; empty when locked or before the venue is up. */
	public List<String> listSecretNames() {
		EmbeddedVenue v = venue;
		if (v == null) return List.of();
		String did = secretsDID(v);
		if (did == null) return List.of();
		try {
			covia.venue.SecretStore store = v.secrets(did, false);
			if (store == null) return List.of();
			List<String> names = new ArrayList<>();
			var listed = store.list();
			for (long i = 0; i < listed.count(); i++) names.add(listed.get(i).toString());
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return names;
		} catch (Exception e) {
			log.warn("Could not list secrets: {}", e.toString());
			return List.of();
		}
	}

	/**
	 * Stores (or replaces) a secret in the acting user's encrypted store — the
	 * same store and encryption {@code secret:set} uses, so {@code s/<name>}
	 * references resolve it immediately.
	 */
	public boolean storeSecret(String name, String value) {
		EmbeddedVenue v = venue;
		if (v == null || !validSecretName(name) || value == null || value.isBlank()) return false;
		String did = secretsDID(v);
		if (did == null) return false;
		try {
			v.secrets(did, true).store(name, value.trim(), v.secretKey());
			v.durable();
			return true;
		} catch (Exception e) {
			log.warn("Could not store secret {}: {}", name, e.toString());
			return false;
		}
	}

	/** Forgets one secret from the acting user's store; false when there is nothing to do. */
	public boolean deleteSecret(String name) {
		EmbeddedVenue v = venue;
		if (v == null || !validSecretName(name)) return false;
		String did = secretsDID(v);
		if (did == null) return false;
		try {
			covia.venue.SecretStore store = v.secrets(did, false);
			if (store == null || !store.deleteIfPresent(Strings.create(name))) return false;
			v.durable();
			return true;
		} catch (Exception e) {
			log.warn("Could not delete secret {}: {}", name, e.toString());
			return false;
		}
	}

	/**
	 * Re-authenticates with the vault passphrase, then decrypts one secret for
	 * an explicit view — the same gate as the private seed
	 * ({@link #revealPrivateSeed}). The passphrase array is wiped either way.
	 */
	public String revealSecret(String name, char[] passphrase) throws IOException {
		try {
			requirePassphrase(passphrase);
			EmbeddedVenue v = venue;
			String did = (v == null) ? null : secretsDID(v);
			if (did == null) throw new IOException("The user logged out during re-authentication");
			covia.venue.SecretStore store = v.secrets(did, false);
			convex.core.data.AString value = (store == null) ? null : store.decrypt(name, v.secretKey());
			if (value == null) throw new IOException("No secret of that name");
			return value.toString();
		} finally {
			java.util.Arrays.fill(passphrase, '\0');
		}
	}

	/** Refuses unless {@code passphrase} unlocks the running identity: the gate before showing a secret or a key. */
	private void requirePassphrase(char[] passphrase) throws IOException {
		if (!canRevealPrivateSeed()) throw new IOException("No logged-in identity");
		String candidate;
		try {
			candidate = Vault.open(config.home(), passphrase).seedHex();
		} catch (IOException e) {
			throw new IOException("That passphrase didn't unlock this identity.", e);
		}
		if (!candidate.equals(venueSeedHex)) {
			throw new IOException("That passphrase didn't unlock this identity.");
		}
	}

	// ------------------------------------------------------------------
	// Named access tokens (Settings → Access): the named user's, in-process
	// ------------------------------------------------------------------

	/** The named user's tokens, or null before the venue is up or once locked: never the operator's. */
	private Tokens tokens() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return null;
		return new Tokens(v.engine(), Strings.create(id.userDID(v.did())));
	}

	/** The named user's tokens as Settings lists them, newest first; empty before the venue is up. */
	public List<brightside.ui.settings.AccessPanel.Row> listTokens() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return List.of();
		String owner = id.userDID(v.did());
		Tokens t = new Tokens(v.engine(), Strings.create(owner));
		String agentId = config.chat().agentId();
		List<brightside.ui.settings.AccessPanel.Row> rows = new ArrayList<>();
		for (covia.adapter.token.AccessToken token : t.list()) {
			Tokens.Scope scope = Tokens.scope(token);
			TokenPreset preset = (scope == null) ? null : TokenPreset.matching(scope, agentId);
			String shown = (scope == null) ? "Accepted beyond the MCP endpoint"
				: (preset != null) ? preset.label() : "Custom: " + scope.tools().count() + " tools";
			String issuer = String.valueOf(token.issuer());
			String madeBy = issuer.equals(owner) ? "you" : issuer.substring(issuer.lastIndexOf(':') + 1);
			long created = convex.core.lang.RT.ensureLong(token.record().get(covia.api.Fields.CREATED)).longValue();
			boolean unrestricted = scope != null && covia.lattice.Caps.UNRESTRICTED.equals(scope.caps());
			rows.add(new brightside.ui.settings.AccessPanel.Row(token.name().toString(), shown, created, madeBy,
				unrestricted));
		}
		rows.sort(java.util.Comparator.comparingLong(brightside.ui.settings.AccessPanel.Row::created).reversed());
		return rows;
	}

	/** The token store, for Settings to follow; null before the venue is up. */
	public convex.lattice.ALatticeComponent<?> tokenStore() {
		Tokens t = tokens();
		return (t == null) ? null : t.store();
	}

	public String createToken(String name, Tokens.Scope scope) throws IOException {
		return requireTokens().create(name, scope).bearer();
	}

	public String newTokenKey(String name) throws IOException {
		return requireTokens().newKey(name).bearer();
	}

	public void revokeToken(String name) throws IOException {
		requireTokens().revoke(name);
	}

	/** A token's key, behind the passphrase as a secret is; the passphrase array is wiped either way. */
	public String revealTokenKey(String name, char[] passphrase) throws IOException {
		try {
			requirePassphrase(passphrase);
			return requireTokens().key(name);
		} catch (covia.exception.NotSupportedException e) {
			// Made or last given a key while the venue kept none (adapters.token.keepRawTokens).
			throw new IOException("This token was made before " + APP_NAME + " kept keys: give it a new key to see one.", e);
		} finally {
			java.util.Arrays.fill(passphrase, '\0');
		}
	}

	private Tokens requireTokens() throws IOException {
		Tokens t = tokens();
		if (t == null) throw new IOException("Tokens are available once " + APP_NAME + " is unlocked.");
		return t;
	}


	/**
	 * The chosen model, kept in Brightside's adapter state: onboarding's choice
	 * is saved there on its first launch, and a later launch reads it back.
	 */
	private void adoptModel(EmbeddedVenue v) {
		BrightsideAdapter app = v.brightside();
		if (app == null) return;
		String chosen = llmOverride;
		if (chosen == null) chosen = app.model();
		if (chosen == null) return;
		if (!chosen.equals(app.model())) app.saveModel(chosen);
		llmOverride = chosen;
	}

	/** The chat config with the chosen model applied, else config.json's. */
	private AppConfig.Chat effectiveChat() {
		AppConfig.Chat c = config.chat();
		if (llmOverride == null || llmOverride.equals(c.llmOperation())) return c;
		return new AppConfig.Chat(c.agentId(), c.operation(), llmOverride, c.systemPrompt());
	}

	/**
	 * Prompts (Take Over / Cancel); on Take Over, asks the running instance to
	 * shut down cleanly and waits for it to release the store. Returns true to
	 * proceed with launch, false to cancel this instance. Refused, with a
	 * message for the owner, when what answers on the port is not a Brightside
	 * holding our identity.
	 */
	private boolean takeOver(int port, Path store) throws Exception {
		// Already agreed at startup; otherwise the instance appeared since, so ask now.
		if (!takeoverApproved && !takeOverUnasked() && !confirmTakeover()) return false;
		takeoverApproved = false;
		progress("Taking over from the running " + APP_NAME + "…");
		// Who is it? A Brightside holding our identity answers brightside:info
		// to our key and names its store and process; anything else is refused.
		Takeover.Instance other = Takeover.identify(port, venueSeedHex);
		log.info("Taking over from {} on port {}", other.describe(), port);
		Path theirs = other.storePath();
		if (theirs != null && store != null && !theirs.equals(store)) {
			log.warn("The running instance holds a different store ({}); this one will open {}", theirs, store);
		}
		Takeover.requestShutdown(port, other.did(), venueSeedHex);
		if (!Takeover.waitUntilDown(port, 20_000)) {
			throw new covia.exception.ConflictException("the previous instance did not shut down in time");
		}
		// The venue closes its port before it flushes and closes the store, so
		// a quiet port is not a free store: wait for the process to go, then for
		// the store file's lock to lift.
		progress("Waiting for it to finish saving…");
		if (!Takeover.waitUntilExited(other.pid(), 60_000)) {
			throw new covia.exception.ConflictException("the previous instance did not exit in time");
		}
		if (!Takeover.waitUntilReleased(store, 20_000)) {
			throw new covia.exception.ConflictException("the previous instance did not release the data store in time");
		}
		return true;
	}

	/**
	 * A new install found the port taken: there is nothing to unlock and no key
	 * to take over with, so say so and stop. Never on the event thread.
	 */
	private void alreadyRunning() {
		try {
			SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
				APP_NAME + " seems to be running on this computer already"
					+ " (something answers on port " + config.port() + ").\n\n"
					+ "Quit it (tray icon ▸ Quit) before setting up a new " + APP_NAME + " here — or, to run two,\n"
					+ "give this one another port in config.json.",
				APP_NAME, JOptionPane.INFORMATION_MESSAGE));
		} catch (Exception e) {
			log.warn("Could not show the already-running notice", e);
		}
	}

	/**
	 * Asks Take Over / Cancel over whatever is on screen — the unlock dialog
	 * while locked, the window once shown, or nothing at all at startup. Never
	 * on the event thread.
	 */
	private boolean confirmTakeover() {
		boolean[] takeOver = { false };
		try {
			SwingUtilities.invokeAndWait(() -> {
				Object[] options = { "Take Over", "Cancel" };
				java.awt.Component parent = (unlockDialog != null && unlockDialog.isShowing()) ? unlockDialog
					: (window != null && window.isShowing()) ? window : null;
				// Remembered where the choice is made, undone in Settings → General → Startup.
				javax.swing.JCheckBox always = new javax.swing.JCheckBox("Always take over without asking");
				Object[] message = { APP_NAME + " is already running on this computer.\n\nTake over from it?", always };
				int choice = JOptionPane.showOptionDialog(parent, message,
					APP_NAME, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
					null, options, options[0]);
				takeOver[0] = (choice == JOptionPane.OK_OPTION);
				if (takeOver[0] && always.isSelected()) setTakeOverWithoutAsking(true);
			});
		} catch (Exception e) {
			log.warn("Takeover prompt failed; cancelling", e);
			return false;
		}
		return takeOver[0];
	}

	/**
	 * The venue is up: bind the identity to it and move any provider keys the
	 * vault still holds. Returns the bound identity for the caller to start the
	 * chat with — a returning person (name already known), or a new one who
	 * typed their name while it booted — or null while {@link #submitName} is
	 * still awaited.
	 */
	private Identity onVenueReady() {
		// Default skills are installed by BrightsideAdapter at venue launch. The
		// gods are not touched here: they are created or brought up to date when
		// first needed (the first message, acting as the operator, or a model
		// change), so a launch submits no jobs.
		if (locked) return null; // a restart behind Lock: the unlock binds
		EmbeddedVenue v = venue;
		Vault vlt = vault;
		Identity id = identity;
		if (id == null && v != null) {
			id = owner(v);
			identity = id;
		}
		if (v != null && vlt != null && id != null) {
			migrateProviderKeys(v, vlt, id.userDID(v.did()));
		}
		return (v != null && id != null) ? bindIdentityToVenue(id, v) : null;
	}

	/**
	 * Keeps the {@link Gods} configured with the chat's model on the first
	 * message, and whenever the model changes. Each launch has already brought
	 * them up to date with the stored model ({@link Startup}); this covers a
	 * first run, before a model is stored, and a change of model.
	 * A god already configured costs no job. Best-effort and off the calling
	 * thread: the chat never waits on them.
	 */
	private void ensureGods(EmbeddedVenue v) {
		if (v == null) return;
		String model = effectiveChat().llmOperation();
		Thread t = new Thread(() -> {
			synchronized (godsLock) {
				Gods.ensureAll(v, model);
			}
		}, "brightside-gods");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * The person entered a name on the welcome screen. Saves it, then either
	 * starts chatting (first time) or rebinds the chat to the new name (a
	 * later change). Called on the event thread from {@link MainWindow}.
	 */
	public void submitName(String rawName) {
		Identity current = identity;
		if (actingAsOperator && current != null) {
			renameOperator(current, rawName);
			return;
		}
		Identity id;
		try {
			// A rename keeps the existing principal (slug): same agent, memory
			// and skills, just addressed differently. Only a first name mints one.
			id = (current != null) ? current.withName(rawName) : Identity.of(rawName);
		} catch (IllegalArgumentException e) {
			window.welcomeBusy(null); // clear any busy state; the panel shows its own error
			return;
		}
		EmbeddedVenue v = venue;
		if (v != null) id = id.withVenueDID(v.did());
		boolean changing = chatStarted.get();
		identity = id;
		persistIdentity(id);
		if (changing) {
			startChatBackground(v, id, currentChatConfig(), Bind.NAME_CHANGE);
		} else if (v != null) {
			startChatOnce(v, id);
		} else {
			// Venue still booting; onVenueReady() will pick this name up.
			window.welcomeBusy("Getting everything ready…");
		}
	}

	/**
	 * While acting as the operator, "your name" is the operator's label: a
	 * display name only, since the operator is the venue itself. Saved beside
	 * the user's identity; nothing on the venue changes.
	 */
	private void renameOperator(Identity current, String rawName) {
		Identity id = current.withOperatorName(rawName);
		identity = id;
		persistIdentity(id);
		SwingUtilities.invokeLater(() -> {
			window.refreshSettings();
			window.showSystemMessage("The venue operator is now shown as " + id.operatorName() + ".");
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name() + " (" + id.operatorName() + ")");
		});
	}

	/** What the app calls the venue operator (Settings → Identity while acting as it). */
	public String operatorName() {
		Identity id = identity;
		return (id != null) ? id.operatorName() : Identity.DEFAULT_OPERATOR_NAME;
	}

	/** Cancel a name change and return to the chat. */
	public void cancelNameChange() {
		window.showChatCard();
	}

	/** Open the name screen so the person can change how they're addressed. */
	public void changeName() {
		if (venue == null) return;
		window.promptChangeName(identity != null ? identity.name() : Identity.suggestName());
	}

	/** Keeps the identity in the store; before the venue is up, binding it to the venue saves it. */
	private void persistIdentity(Identity id) {
		EmbeddedVenue v = venue;
		BrightsideAdapter app = (v != null) ? v.brightside() : null;
		if (app == null) return;
		app.saveOwner(id.toCell());
		v.durable();
		log.info("Saved name '{}'", id.name());
	}

	/** The owner's identity from the store; one an earlier Brightside kept in a file moves in, once. */
	private Identity owner(EmbeddedVenue v) {
		BrightsideAdapter app = v.brightside();
		if (app == null) return null;
		Identity id = Identity.fromCell(app.owner());
		if (id != null) return id;
		id = Identity.takeFile(config.home());
		if (id != null) app.saveOwner(id.toCell());
		return id;
	}

	/** Pins and persists the full user DID once the home venue identity is known. */
	private Identity bindIdentityToVenue(Identity id, EmbeddedVenue v) {
		Identity bound = id.withVenueDID(v.did());
		identity = bound;
		if (id.did() == null) persistIdentity(bound);
		// The Claude Code projects are the owner's to use, Brightside's own source among them.
		ClaudeCode.own(v, bound.did(), config.home());
		renewToolToken(); // unlocked again: local tools need not wait for the hour
		return bound;
	}

	/** Starts the first chat exactly once, whichever of name/venue arrives last. */
	private void startChatOnce(EmbeddedVenue v, Identity id) {
		if (v == null || id == null) return;
		if (chatStarted.compareAndSet(false, true)) startChatBackground(v, id, effectiveChat(), Bind.FIRST);
	}

	private void startChatBackground(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind) {
		startChatBackground(v, id, chatConfig, bind, null);
	}

	/** As above, showing conversation {@code open} once bound (null: the agent's latest, on a switch). */
	private void startChatBackground(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind, String open) {
		// The operator's agents keep the configuration their records hold — a
		// god's is owned by God.ensure, never by the chat's persona config.
		boolean configureExisting = !actingAsOperator && chatConfig.agentId().equals(config.chat().agentId());
		startChatBackground(v, id, chatConfig, bind, configureExisting, open);
	}

	// ------------------------------------------------------------------
	// Acting principal (the named user, or the venue operator)
	// ------------------------------------------------------------------

	public boolean actingAsOperator() {
		return actingAsOperator;
	}

	/**
	 * Switch the app between acting as the named user and as the venue operator,
	 * rebinding the chat, agents, conversations and Inbox to that principal. As
	 * the operator the default agent is Odin. No-op before the venue and identity
	 * are up, or when nothing changes.
	 */
	public void actAs(boolean operator) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null || operator == actingAsOperator) return;
		actingAsOperator = operator;
		log.info("Acting as {}", operator ? "the venue operator" : id.label());
		startChatBackground(v, id, chatConfigFor(defaultAgentId()), Bind.PRINCIPAL_SWITCH);
	}

	/**
	 * Chat with {@code agentId}, or the principal's standard agent when null,
	 * as the operator or the named user, switching principal first if need be,
	 * in one bind. From the Peers map's menu on a planet or a moon.
	 */
	public void chatWith(boolean operator, String agentId) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null) return;
		if (operator == actingAsOperator) {
			if (agentId != null && !agentId.equals(this.agentId)) switchAgent(agentId);
			return;
		}
		actingAsOperator = operator;
		log.info("Acting as {}", operator ? "the venue operator" : id.label());
		startChatBackground(v, id, chatConfigFor(agentId != null ? agentId : defaultAgentId()), Bind.PRINCIPAL_SWITCH);
	}

	/** The DID of whoever the app acts as now, or null before the venue is up. */
	public String actingPrincipal() {
		EmbeddedVenue v = venue;
		Identity id = identity;
		return (v == null || id == null) ? null : actingDID(id, v);
	}

	/** The named user's main assistant: the chat agent the configuration names. */
	public String mainAgentId() {
		return config.chat().agentId();
	}

	/** Folder access applies to the installation; edits are picked up on restart. */
	public FileRoots fileRoots() {
		return new FileRoots(config.home(), config.venueConfig());
	}

	/** Capture the principal before a settings action leaves the event thread. */
	public PersonalDrives personalDrives() {
		EmbeddedVenue v = venue;
		String did = actingPrincipal();
		return v == null || did == null ? null : new PersonalDrives(v, did);
	}

	/** The principal the app acts as: the venue itself as operator, else {@code id}'s user. */
	private String actingDID(Identity id, EmbeddedVenue v) {
		return actingAsOperator ? v.did() : id.userDID(v.did());
	}

	/** The standard agent for the acting principal: Odin for the operator, the configured chat agent otherwise. */
	private String defaultAgentId() {
		return actingAsOperator ? Odin.AGENT_ID : config.chat().agentId();
	}

	private void startChatBackground(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind,
			boolean configureExisting) {
		startChatBackground(v, id, chatConfig, bind, configureExisting, null);
	}

	private void startChatBackground(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind,
			boolean configureExisting, String open) {
		ChatTarget target = chatTarget(v, id, open);
		Thread t = new Thread(() -> {
			try {
				startChat(v, id, chatConfig, bind, configureExisting, target);
			} catch (Throwable e) {
				// Off the event thread and after the reveal: without this the
				// window would say "starting up" for ever, and the trace would
				// reach no one.
				log.error("Could not bind the chat", e);
				startupFailed(APP_NAME + " couldn't open the conversation (" + rootMessage(e) + "). See the logs.");
			}
		}, "brightside-chat");
		t.setDaemon(true);
		t.start();
	}

	/** Binds a chat session to {@code id}'s principal and prepares its conversation view. */
	private void startChat(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind) {
		startChat(v, id, chatConfig, bind, true, chatTarget(v, id, null));
	}

	private void startChat(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind,
			boolean configureExisting, ChatTarget target) {
		String did = target.did();
		covia.grid.Venue userClient = v.clientAs(did);
		if (!target.operator()) {
			try {
				new PersonalDrives(v, did).initialise();
			} catch (Exception e) {
				log.warn("Could not prepare personal storage for {}", did, e);
			}
		}
		ChatSession session = new ChatSession(userClient, chatConfig, id.name(), configureExisting);

		// The agent is not created or reconfigured here: ChatSession.ensureAgent
		// runs on the first send, so binding submits no agent jobs — and Home
		// leaves ChatSession.sessionId null until the user actually sends their
		// first message. A god is the exception when the operator turns to it:
		// it is configured by its own ensure, so the session must find it there.
		// The rest of the gods follow on the first message, so an assistant can
		// reach them; once configured, that costs nothing.
		God god = target.operator() ? Gods.byId(chatConfig.agentId()) : null;
		if (god != null) {
			try {
				synchronized (godsLock) {
					god.ensure(v, chatConfig.llmOperation());
				}
			} catch (Exception e) {
				log.warn("{} not ready", god.id(), e);
			}
		}
		session.whenReady(() -> {
			if (godsEnsured.compareAndSet(false, true)) ensureGods(v);
		});
		// Import any skills the user has dropped into ~/.brightside/skills/
		// (agentskills.io SKILL.md folders) on the first bind. Check the stored
		// value in-process so an unchanged skill costs no write job.
		if (bind == Bind.FIRST) {
			try {
				brightside.skills.FilesystemSkills.sync(userClient, config.skillsDir(),
					path -> v.resolve(did, path));
			} catch (Exception e) {
				log.warn("Filesystem skill import failed", e);
			}
		}
		String aid = chatConfig.agentId();

		// The venue is in-process: read the agent record straight from the lattice
		// (no job) and project it, rather than submitting a covia:read.
		convex.core.data.ACell record = v.agentRecord(did, aid);
		// What Home shows once bound. A launch lands on a clean Home: nothing
		// resumes until the first message mints a session, and the saved
		// conversations wait in Sessions (DESIGN.md §5). A name change keeps the
		// conversation on screen, if any; a switch of agent or principal opens
		// that agent's latest, so "Now chatting with X" has X's context behind it.
		String viewed = target.sessionId();
		SessionHistory.Snapshot history = switch (bind) {
			case FIRST -> null;
			case NAME_CHANGE -> (viewed != null) ? SessionHistory.snapshotOf(record, viewed) : null;
			case AGENT_SWITCH, PRINCIPAL_SWITCH -> SessionHistory.snapshotOf(record, target.open());
		};
		if (history != null) session.resume(history.sessionId());
		List<SessionHistory.Item> turns = (history != null) ? history.items() : List.of();
		List<SessionHistory.Session> sessionList = (record != null) ? SessionHistory.sessionsOf(record) : List.of();
		List<AgentRef> agentRefs = listAgents(v, did, aid,
			target.operator() ? Odin.AGENT_ID : config.chat().agentId());
		log.info("Chatting as {} with agent '{}' — showing {} live message(s) across {} saved conversation(s)",
			id.label(), aid, turns.size(), sessionList.size());

		SwingUtilities.invokeLater(() -> {
			if (exiting.get() || chatBindings.get() != target.generation()) return;
			chat = session;
			client = userClient;
			agentId = aid;
			userDID = did;
			switch (bind) {
				case FIRST -> window.showChat(v, session, id, turns);
				case NAME_CHANGE -> window.userChanged(session, id, turns);
				case AGENT_SWITCH -> window.showAgentChat(session, turns, displayNameFor(aid));
				case PRINCIPAL_SWITCH -> {
					window.showAgentChat(session, turns, displayNameFor(aid));
					window.showSystemMessage(actingAsOperator
						? "You're acting as " + id.operatorName() + ", the venue operator, now: the venue's own agents are listed, and " + displayNameFor(aid) + " is on the line."
						: "Back to acting as " + id.name() + ".");
					// The switch is made from Settings → Identity, which is still on
					// screen: show the identity the app now acts as.
					window.refreshSettings();
				}
			}
			window.bindSessions(v, did, session, sessionList);
			window.setAgents(agentRefs, aid, defaultAgentId());
			if (inboxBinding == null || bind == Bind.FIRST || bind == Bind.PRINCIPAL_SWITCH) {
				bindInbox(v, did);
				window.setJobs(v.jobs(did), did);
			}
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name() + (actingAsOperator ? " (" + id.operatorName() + ")" : ""));
		});
	}

	// ------------------------------------------------------------------
	// Agents (multi-agent switcher)
	// ------------------------------------------------------------------

	/** Switch the chat to another existing agent (from the agents pane). */
	public void switchAgent(String agentId) {
		switchAgent(agentId, null);
	}

	/** As {@link #switchAgent(String)}, showing its conversation {@code sessionId} (null: its latest). */
	public void switchAgent(String agentId, String sessionId) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null || agentId == null) return;
		startChatBackground(v, id, chatConfigFor(agentId), Bind.AGENT_SWITCH, sessionId);
	}

	/** Create a general-purpose agent using the current default model. */
	public void createAgent(String rawName) {
		String name = (rawName != null) ? rawName.trim() : "";
		String prompt = brightside.model.AgentTemplate.GENERAL.systemPrompt(name);
		createAgent(name, currentModelOp(), prompt, brightside.model.AgentTemplate.GENERAL.skill());
	}

	/**
	 * Create a new agent with the chosen model and starting prompt, then switch
	 * to it. The record is written first, through the venue's own
	 * {@code agent:create} — the operation the assistant's agent tools call —
	 * with the name as its {@code config.name}, so the agent exists from this
	 * moment: in the pane's {@code g/} listing, to other venues, and whether or
	 * not anything is ever said to it. (Binding a chat alone creates nothing;
	 * {@link ChatSession#ensureAgent} otherwise runs on the first send.) An
	 * agent of that id already on the venue is opened as it is, its record
	 * untouched. {@code skill}, from the template, is pinned from the start
	 * (null: none).
	 */
	public void createAgent(String rawName, String modelOp, String systemPrompt, String skill) {
		String name = (rawName != null) ? rawName.trim() : "";
		String aid = slug(name);
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (aid.isEmpty() || v == null || id == null || aid.equals(this.agentId)) return;
		String did = actingDID(id, v);
		if (v.agentRecord(did, aid) != null) {
			switchAgent(aid);
			SwingUtilities.invokeLater(() -> window.showSystemMessage(
				"An agent called " + displayNameFor(aid) + " already exists — opening it."));
			return;
		}
		AppConfig.Chat base = effectiveChat();
		String chosenModel = (modelOp == null || modelOp.isBlank()) ? base.llmOperation() : modelOp;
		String chosenPrompt = (systemPrompt == null || systemPrompt.isBlank())
			? brightside.model.AgentTemplate.GENERAL.systemPrompt(name) : systemPrompt;
		AppConfig.Chat chosen = new AppConfig.Chat(aid, base.operation(), chosenModel, chosenPrompt);
		Thread t = new Thread(() -> {
			try {
				new ChatSession(v.clientAs(did), chosen, id.name(), true).named(name).pinning(skill).ensureAgent();
			} catch (Exception e) {
				log.warn("Could not create agent {}: {}", aid, e.toString());
				SwingUtilities.invokeLater(() -> window.showSystemMessage(
					"Sorry — I couldn't create " + name + " (" + rootMessage(e) + ")."));
				return;
			}
			log.info("Created agent '{}' ({})", aid, name);
			// The record holds the chosen configuration now: bind without re-applying it.
			startChatBackground(v, id, chosen, Bind.AGENT_SWITCH, false);
		}, "brightside-create-agent");
		t.setDaemon(true);
		t.start();
	}

	/** Show the agent info screen for {@code agentId}. */
	public void showAgentInfo(String agentId, int version) {
		Venue c = client;
		EmbeddedVenue v = venue;
		Identity id = identity;
		String did = userDID;
		if (c == null || v == null || id == null || did == null || agentId == null) return;
		boolean standard = agentId.equals(defaultAgentId());
		boolean editable = instructionsEditable(agentId);
		Thread t = new Thread(() -> {
			// Null when it could not be read: the Agent view says so.
			AgentInfo.Summary info = AgentInfo.load(c, v.agentRecord(did, agentId), agentId, did, id.name(), standard);
			SwingUtilities.invokeLater(() -> window.showAgentInfo(agentId, version, info, editable));
		}, "brightside-agent-info");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Delete an agent outright — record, conversations and memory. The standard
	 * agent is never deleted; if the deleted agent was on screen, the chat drops
	 * back to the standard one.
	 */
	public void deleteAgent(String agentId) {
		Venue c = client;
		EmbeddedVenue v = venue;
		Identity id = identity;
		String def = defaultAgentId();
		if (c == null || v == null || id == null || agentId == null || agentId.equals(def)) return;
		String name = displayNameFor(agentId);
		Thread t = new Thread(() -> {
			try {
				deleteAgent(c, agentId);
			} catch (Exception e) {
				log.warn("Could not delete agent {}: {}", agentId, e.toString());
				SwingUtilities.invokeLater(() -> window.showSystemMessage("Sorry — I couldn't delete " + name + "."));
				return;
			}
			log.info("Deleted agent '{}'", agentId);
			if (agentId.equals(this.agentId)) {
				// Rebind to the standard agent first; its screen update is queued
				// before the note, so the note lands on the new conversation.
				startChat(v, id, chatConfigFor(def), Bind.AGENT_SWITCH);
			} else {
				String current = this.agentId;
				List<AgentRef> refs = listAgents(v, userDID, current);
				SwingUtilities.invokeLater(() -> window.setAgents(refs, current, def));
			}
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Deleted " + name + "."));
		}, "brightside-delete-agent");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Whether the owner may change {@code agentId}'s instructions from the Agent
	 * tab: not the standard agent's, which follow Settings and config.json and
	 * are re-applied, nor a god's, which carry policy that {@link God} re-applies.
	 */
	private boolean instructionsEditable(String agentId) {
		return agentId != null && !agentId.equals(defaultAgentId())
			&& !(actingAsOperator && Gods.byId(agentId) != null);
	}

	/**
	 * Gives one of the owner's own agents new instructions, in force from its
	 * next turn, and reads its Agent tab again. An agent mid-reply rejects the
	 * update; the owner is told and can save again once it is done.
	 */
	public void setAgentInstructions(String agentId, String instructions) {
		Venue c = client;
		if (c == null || !instructionsEditable(agentId) || instructions == null || instructions.isBlank()) return;
		String name = displayNameFor(agentId);
		Thread t = new Thread(() -> {
			String note;
			try {
				setAgentInstructions(c, agentId, instructions.trim());
				log.info("Changed the instructions of agent '{}'", agentId);
				note = "Saved " + name + "'s instructions. They apply from its next turn.";
			} catch (Exception e) {
				log.warn("Could not change the instructions of agent {}: {}", agentId, e.toString());
				note = "Couldn't save " + name + "'s instructions (" + rootMessage(e)
					+ "). If it is replying, save them again once it is done.";
			}
			String text = note;
			SwingUtilities.invokeLater(() -> {
				window.agentChanged();
				window.showSystemMessage(text);
			});
		}, "brightside-agent-instructions");
		t.setDaemon(true);
		t.start();
	}

	/** Replaces an agent's instructions: {@code agent:update} merging in its {@code systemPrompt}, keeping the rest. */
	static void setAgentInstructions(Venue client, String agentId, String instructions) throws Exception {
		invokeOp(client, "v/ops/agent/update",
			Maps.of("agentId", agentId, "config", Maps.of("systemPrompt", instructions)));
	}

	/** Removes an agent's record ({@code agent:delete} with {@code remove}), not merely marking it terminated. */
	static void deleteAgent(Venue client, String agentId) throws Exception {
		invokeOp(client, "v/ops/agent/delete", Maps.of("agentId", agentId, "remove", true));
	}

	// ------------------------------------------------------------------
	// Inbox (HITL requests to the owner)
	// ------------------------------------------------------------------

	/** Bind cards, badge and arrivals to the same owner's domain snapshots. Event thread. */
	private void bindInbox(EmbeddedVenue v, String did) {
		InboxBinding old = inboxBinding;
		if (old != null) old.close();
		window.clearInbox();
		if (tray != null) tray.setPending(false);
		inboxBinding = new InboxBinding(v.inboxes(did), requests -> {
			window.setInbox(requests);
			if (tray != null) tray.setPending(Inbox.pending(requests) > 0);
		}, request -> {
			if (tray != null) tray.notify(APP_NAME + " needs your decision", request.title());
		});
		inboxBinding.start();
	}

	/**
	 * Pauses, resumes, cancels or messages {@code job} as the acting principal,
	 * off the event thread, then says how it went.
	 */
	public void controlJob(JobList.Job job, brightside.ui.jobs.JobsScreen.Control control, String message,
			brightside.ui.jobs.JobsScreen.Result done) {
		Venue acting = scheduleClient();
		if (acting == null) {
			done.done(APP_NAME + " is still starting up.", true);
			return;
		}
		Thread t = new Thread(() -> {
			String note;
			boolean error = false;
			try {
				switch (control) {
					case PAUSE -> JobList.pause(acting, job.id());
					case RESUME -> JobList.resume(acting, job.id());
					case CANCEL -> JobList.cancel(acting, job.id());
					case MESSAGE -> JobList.message(acting, job.id(), message);
				}
				note = switch (control) {
					case PAUSE -> "Asked to pause " + job.title() + ".";
					case RESUME -> "Asked to resume " + job.title() + ".";
					case CANCEL -> "Asked to cancel " + job.title() + ".";
					case MESSAGE -> "Sent.";
				};
			} catch (Exception e) {
				log.warn("Could not {} job {}: {}", control, job.id(), e.toString());
				note = "Couldn't " + control.name().toLowerCase() + " it: " + rootMessage(e);
				error = true;
			}
			String shown = note;
			boolean failed = error;
			SwingUtilities.invokeLater(() -> done.done(shown, failed));
		}, "brightside-job-control");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Answer a request as the inbox's owner — the named user, or the operator for
	 * a request to the venue — never as an agent; then refresh the inbox.
	 */
	public void answerRequest(Inbox.Request request, Inbox.Answer answer) {
		respond(request, c -> Inbox.answer(c, request.id(), answer), "Answered.");
	}

	public void rejectRequest(Inbox.Request request, String reason) {
		respond(request, c -> Inbox.reject(c, request.id(), reason), "Rejected.");
	}

	private interface InboxAction {
		void run(Venue client) throws Exception;
	}

	private void respond(Inbox.Request request, InboxAction action, String done) {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(() -> respond(request, action, done));
			return;
		}
		InboxBinding bound = inboxBinding;
		EmbeddedVenue v = venue;
		if (request == null || bound == null || v == null || locked || exiting.get()) return;
		if (!bound.canRespond(request)) {
			window.showInboxNotice(request, "That request has changed. Refreshing…");
			bound.refresh();
			return;
		}
		Venue c = v.did().equals(request.owner()) ? v.operator() : v.clientAs(request.owner());
		Thread t = new Thread(() -> {
			String note;
			try {
				action.run(c);
				note = done;
			} catch (Exception e) {
				log.warn("Could not respond to request {}: {}", request.id(), e.toString());
				note = "Sorry — that didn't go through: " + rootMessage(e);
			}
			String n = note;
			SwingUtilities.invokeLater(() -> {
				if (inboxBinding != bound || locked || exiting.get()) return;
				window.showInboxNotice(request, n);
				bound.refresh();
			});
		}, "brightside-hitl-respond");
		t.setDaemon(true);
		t.start();
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return (t.getMessage() != null) ? t.getMessage() : t.toString();
	}

	/**
	 * The chat config for {@code aid}: a god's own identity for the operator,
	 * the default persona for the configured chat agent, else a named one.
	 */
	private AppConfig.Chat chatConfigFor(String aid) {
		AppConfig.Chat base = effectiveChat();
		God god = actingAsOperator ? Gods.byId(aid) : null;
		if (god != null) {
			return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), god.systemPrompt());
		}
		if (aid.equals(config.chat().agentId())) {
			return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), base.systemPrompt());
		}
		String persona = "You are " + displayNameFor(aid) + ", a private personal AI assistant running on the user's "
			+ "own computer. Be genuinely helpful, warm, and concise.";
		return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), persona);
	}

	private AppConfig.Chat currentChatConfig() {
		return chatConfigFor((agentId != null) ? agentId : defaultAgentId());
	}

	/**
	 * Lists the acting principal's agents through the agent adapter's public
	 * job-free listing (terminated agents already excluded upstream) — always
	 * including the default and current.
	 */
	private List<AgentRef> listAgents(EmbeddedVenue v, String did, String currentAid) {
		return listAgents(v, did, currentAid, defaultAgentId());
	}

	private List<AgentRef> listAgents(EmbeddedVenue v, String did, String currentAid, String def) {
		java.util.LinkedHashMap<String, AgentRef> map = new java.util.LinkedHashMap<>();
		map.put(def, new AgentRef(def, displayNameFor(v, did, def)));
		if (currentAid != null) {
			map.putIfAbsent(currentAid, new AgentRef(currentAid, displayNameFor(v, did, currentAid)));
		}
		for (String aid : v.agents(did)) {
			map.putIfAbsent(aid, new AgentRef(aid, displayNameFor(v, did, aid)));
		}
		return stableAgentOrder(new ArrayList<>(map.values()), def);
	}

	/** Default agent first, then immutable ids: selection and activity never reorder rows. */
	static List<AgentRef> stableAgentOrder(List<AgentRef> agents, String defaultId) {
		ArrayList<AgentRef> ordered = new ArrayList<>(agents);
		ordered.sort(Comparator
			.comparing((AgentRef agent) -> !agent.id().equals(defaultId))
			.thenComparing(AgentRef::id));
		return List.copyOf(ordered);
	}

	/** An agent's display name: an explicit {@code config.name} in its record, else the id exactly as written. */
	private String displayNameFor(String agentId) {
		return displayNameFor(venue, userDID, agentId);
	}

	private String displayNameFor(EmbeddedVenue v, String did, String agentId) {
		ACell record = (v != null && did != null && agentId != null) ? v.agentRecord(did, agentId) : null;
		return AgentRef.displayName(record, agentId, standardAssistantId());
	}

	/** The owner's standard assistant, shown by the app's name until it is given one; null while acting as the operator. */
	private String standardAssistantId() {
		return actingAsOperator ? null : config.chat().agentId();
	}

	/** An agent id from a typed name: case kept, anything a path or DID cannot carry becomes {@code -}. */
	private static String slug(String name) {
		if (name == null) return "";
		return name.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("(^-|-$)", "");
	}

	/**
	 * Re-reads the agents pane from {@code g/}. Agents can appear out of band —
	 * created by the assistant in a turn, by Odin, or by a background task — so
	 * the sessions screen refreshes on entry rather than trusting the last bind.
	 */
	public void refreshAgents() {
		EmbeddedVenue v = venue;
		String did = userDID;
		String aid = agentId;
		ChatSession expected = chat;
		if (v == null || did == null) return;
		Thread t = new Thread(() -> {
			List<AgentRef> refs = listAgents(v, did, aid);
			SwingUtilities.invokeLater(() -> {
				if (chat == expected) window.setAgents(refs, aid, defaultAgentId());
			});
		}, "brightside-agents-refresh");
		t.setDaemon(true);
		t.start();
	}

	/** Show the full context the assistant's model receives for a conversation. */
	public void showSessionInfo(String sessionId, int version) {
		covia.grid.Venue c = client;
		EmbeddedVenue v = venue;
		String aid = agentId, did = userDID;
		if (c == null || v == null || aid == null || did == null || sessionId == null) return;
		Thread t = new Thread(() -> {
			// The assembled context is a computation (agent:context op); the raw
			// turns and the discoverable skills are lattice reads, done in-process.
			// A null report (it could not be read) reaches the views, which say so.
			AgentContext.Report report = AgentContext.load(c, aid, sessionId);
			ACell record = v.agentRecord(did, aid);
			List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(record, sessionId);
			// The conversation's Skills tab lists the skills loaded in it; what they
			// reveal links into the library, the Skills screen.
			java.util.Set<String> loaded = new java.util.HashSet<>();
			if (report != null) {
				for (AgentContext.Load l : report.loads()) {
					if ("skill".equals(l.kind()) && l.ref() != null) loaded.add(l.ref());
				}
			}
			List<SkillIndex.Skill> skills = SkillIndex.of(v, did, record).stream()
				.filter(s -> loaded.contains(s.path())).toList();
			SwingUtilities.invokeLater(() -> window.showContextInfo(sessionId, version, report, turns, skills));
		}, "brightside-session-info");
		t.setDaemon(true);
		t.start();
	}

	/** Invokes a venue operation and waits for it to finish (worker thread only). */
	private static void invokeOp(Venue client, String operation, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(operation, input).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);
	}

	public AppConfig config() {
		return config;
	}

	public Path configPath() {
		return configPath;
	}

	/** The running venue, or null until it has started. */
	public EmbeddedVenue venue() {
		return venue;
	}

	public boolean hasTray() {
		return tray != null;
	}

	/** Bring the app back: the unlock dialog while locked, otherwise the window. */
	public void showWindow() {
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showAndFocus();
			else window.showAndFocus();
		});
	}

	public void hideToTray() {
		if (tray == null) return;
		SwingUtilities.invokeLater(() -> {
			window.setVisible(false);
			tray.notifyHidden();
		});
	}

	/**
	 * Window close button. Only stays resident in the tray if the user opted in
	 * (Settings → General → System tray). By default, closing the
	 * window shuts Brightside down cleanly (flushing the venue store).
	 */
	public void onWindowClosing() {
		if (keepInTray() && tray != null) hideToTray();
		else exit();
	}

	/** Minimise: to the tray when enabled and available, otherwise the usual taskbar minimise. */
	public void onWindowIconified() {
		if (minimiseToTray() && tray != null) hideToTray();
	}

	/** Brightside's adapter on the running venue, which keeps the app's own state; null before the venue is up. */
	private BrightsideAdapter appState() {
		EmbeddedVenue v = venue;
		return (v != null) ? v.brightside() : null;
	}

	/**
	 * Whether each reply the chat waits for is read aloud (default false: a fresh
	 * install is silent). Off, a reply is read only when the owner asks.
	 */
	public boolean readRepliesAloud() {
		return flag(BrightsideAdapter.READ_ALOUD);
	}

	public void setReadRepliesAloud(boolean value) {
		setFlag(BrightsideAdapter.READ_ALOUD, value);
	}

	/** One of the installation's on/off settings in Brightside's adapter state: off until chosen, and before the venue is up. */
	private boolean flag(String key) {
		BrightsideAdapter app = appState();
		Boolean on = (app != null) ? app.flag(key) : null;
		return on != null && on;
	}

	private void setFlag(String key, boolean value) {
		BrightsideAdapter app = appState();
		if (app != null) app.saveFlag(key, value);
	}

	/**
	 * How long a pause after speaking sends a spoken message, in seconds (default
	 * {@link brightside.voice.Dictation#LONG_PAUSE_SECONDS}); 0: never, only "Go"
	 * or Enter sends.
	 */
	public int sendAfterPauseSeconds() {
		BrightsideAdapter app = appState();
		Integer seconds = (app != null) ? app.sendAfterPauseSeconds() : null;
		return (seconds != null) ? seconds : brightside.voice.Dictation.LONG_PAUSE_SECONDS;
	}

	public void setSendAfterPauseSeconds(int seconds) {
		BrightsideAdapter app = appState();
		if (app != null) app.saveSendAfterPauseSeconds(seconds);
	}

	/**
	 * Moves the settings an earlier Brightside kept in prefs.properties into the
	 * store, once. prefs.properties keeps only what acts before the store opens.
	 */
	private void adoptSettings(EmbeddedVenue v) {
		BrightsideAdapter app = v.brightside();
		if (app == null) return;
		adoptFlag(app, "voice.readRepliesAloud", BrightsideAdapter.READ_ALOUD);
		adoptFlag(app, "tray.minimise", BrightsideAdapter.MINIMISE_TO_TRAY);
		adoptFlag(app, "toolToken.keep", BrightsideAdapter.KEEP_TOOL_TOKEN);
		String pause = prefs.getString("voice.sendAfterPauseSeconds", null);
		if (pause != null) {
			try {
				if (app.sendAfterPauseSeconds() == null) app.saveSendAfterPauseSeconds(Integer.parseInt(pause.strip()));
			} catch (NumberFormatException e) {
				// not a number: nothing worth keeping
			}
			prefs.setString("voice.sendAfterPauseSeconds", null);
		}
		if (toolTokenWithdrawn) {
			app.saveFlag(BrightsideAdapter.KEEP_TOOL_TOKEN, false);
			toolTokenWithdrawn = false;
		}
	}

	private void adoptFlag(BrightsideAdapter app, String pref, String key) {
		String value = prefs.getString(pref, null);
		if (value == null) return;
		if (app.flag(key) == null) app.saveFlag(key, Boolean.parseBoolean(value.strip()));
		prefs.setString(pref, null);
	}

	/** The data store's size, compaction request and kept originals; null when it is not a file or can't be read. */
	public StoreSpace.Report storeReport() {
		StoreSpace space = storeSpace;
		if (space == null) return null;
		try {
			return space.report();
		} catch (IOException e) {
			log.warn("Could not read the data store's size: {}", e.toString());
			return null;
		}
	}

	/** Compacts the data store the next time Brightside starts, or withdraws that. */
	public void setCompactAtNextStart(boolean value) throws IOException {
		StoreSpace space = storeSpace;
		if (space != null) space.compactAtNextStart(value);
	}

	/** Deletes the originals repairs kept beside the store, once the owner has confirmed. */
	public void removeKeptOriginals() throws IOException {
		StoreSpace space = storeSpace;
		if (space != null) space.removeKeptOriginals();
	}

	/** Whether closing the window keeps Brightside running in the tray (default false). */
	public boolean keepInTray() {
		return prefs.getBool("tray.keepOpen", false);
	}

	public void setKeepInTray(boolean value) {
		prefs.setBool("tray.keepOpen", value);
	}

	/**
	 * Whether minimising sends the window to the tray (default false — on Windows a
	 * click on the active window's taskbar button is a minimise, so hiding on
	 * minimise made the window vanish). The tray is reached deliberately instead.
	 */
	public boolean minimiseToTray() {
		return flag(BrightsideAdapter.MINIMISE_TO_TRAY);
	}

	public void setMinimiseToTray(boolean value) {
		setFlag(BrightsideAdapter.MINIMISE_TO_TRAY, value);
	}

	/**
	 * Whether a user token for local tools is kept fresh in a file while
	 * Brightside runs (default false). See {@link ToolToken}.
	 */
	public boolean keepToolToken() {
		return flag(BrightsideAdapter.KEEP_TOOL_TOKEN);
	}

	public void setKeepToolToken(boolean value) {
		setFlag(BrightsideAdapter.KEEP_TOOL_TOKEN, value);
		renewToolToken();
	}

	/**
	 * Whether the owner has a Brightside found running at launch taken over
	 * without asking (default false). Never for {@code --unlock}: a launch
	 * nobody is sitting at leaves a running instance alone unless
	 * {@code --takeover} says otherwise.
	 */
	public boolean takeOverWithoutAsking() {
		return prefs.getBool(PREF_TAKE_OVER, false);
	}

	public void setTakeOverWithoutAsking(boolean value) {
		prefs.setBool(PREF_TAKE_OVER, value);
	}

	/** Whether to take over without asking: {@code --takeover}, or the owner's setting. */
	private boolean takeOverUnasked() {
		return takeoverAllowed || takeOverWithoutAsking();
	}

	/**
	 * Whether a launch unlocks with the remembered passphrase without waiting
	 * for Unlock (default false). Only at launch: Lock always asks.
	 */
	public boolean unlockWithoutAsking() {
		return prefs.getBool(PREF_UNLOCK, false);
	}

	public void setUnlockWithoutAsking(boolean value) {
		prefs.setBool(PREF_UNLOCK, value);
	}

	/**
	 * Whether Tau starts when the owner signs in to Windows (default false).
	 * Only with a passphrase remembered: the start unlocks with it.
	 */
	public boolean startAtSignIn() {
		return prefs.getBool(PREF_AT_SIGN_IN, false) && hasRememberedPassphrase();
	}

	/** Whether this computer has sign-in starts at all: Windows only. */
	public boolean startAtSignInOffered() {
		return signIn.offered();
	}

	/** Why starting at sign-in cannot be turned on in this launch, or null when it can. */
	public String startAtSignInUnavailable() {
		return signIn.unavailable();
	}

	/**
	 * Turns starting at sign-in on or off, and makes the entry match. If the
	 * entry cannot be made to match, the setting goes back to what it was.
	 */
	public void setStartAtSignIn(boolean value) {
		boolean before = prefs.getBool(PREF_AT_SIGN_IN, false);
		prefs.setBool(PREF_AT_SIGN_IN, value);
		if (!applyStartAtSignIn()) {
			prefs.setBool(PREF_AT_SIGN_IN, before);
			if (window != null) SwingUtilities.invokeLater(window::refreshSettings);
		}
	}

	/**
	 * Makes the sign-in entry match the setting: there, naming this build, or
	 * gone. Never throws: a registry that fails is reported, and the launch or
	 * unlock that asked goes on.
	 */
	private boolean applyStartAtSignIn() {
		String failed = signIn.tryApply(startAtSignIn());
		if (failed == null) return true;
		log.warn("Could not update the Windows sign-in entry: {}", failed);
		if (window != null) SwingUtilities.invokeLater(() -> window.showSystemMessage(
			"Couldn't change whether " + APP_NAME + " starts when you sign in: " + failed));
		return false;
	}

	/** Keeps the tool token fresh from the moment the venue is up. Once. */
	private void startToolTokens() {
		if (toolTokensStarted.compareAndSet(false, true)) {
			toolTokens.scheduleWithFixedDelay(this::refreshToolToken, 0, ToolToken.REFRESH_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** {@link #refreshToolToken} now, off the calling thread; nothing once exiting. */
	private void renewToolToken() {
		try {
			toolTokens.execute(this::refreshToolToken);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// exiting: the file is already gone
		}
	}

	/** A fresh tool token if the owner keeps one and the venue can sign it; otherwise none at all. */
	private void refreshToolToken() {
		Path home = config.home();
		try {
			String jwt = (keepToolToken() && !exiting.get()) ? mintAccessToken(ToolToken.LIFETIME_SECONDS, false) : null;
			if (jwt == null) ToolToken.clear(home);
			else ToolToken.write(home, jwt);
		} catch (Exception e) {
			log.warn("Could not write the token for local tools: {}", e.toString());
		}
	}

	/** {@link LAF#LIGHT} or {@link LAF#DARK}: the mode in use. */
	public String mode() {
		return LAF.mode();
	}

	/** The id of the theme in use (the chosen one for the mode, else the mode's default). */
	public String theme() {
		return LAF.current().id();
	}

	/** The accent chosen in Settings → Theme as {@code #RRGGBB}, or null for the default. */
	public String accent() {
		return LAF.accent();
	}

	/** Switches to light or dark — each mode keeps its own chosen theme — and remembers it. */
	public void setMode(String mode) {
		prefs.setString(PREF_MODE, mode);
		applyLook(prefs.getString(themeKey(mode), LAF.defaultTheme(mode)), accent());
	}

	/** Remembers the theme as its mode's choice, switches to that mode, and switches every window to it. */
	public void setTheme(String id) {
		LAF.Choice chosen = LAF.choice(id);
		if (chosen == null) return;
		String mode = chosen.dark() ? LAF.DARK : LAF.LIGHT;
		prefs.setString(themeKey(mode), id);
		prefs.setString(PREF_MODE, mode);
		applyLook(id, accent());
	}

	/** Switches every window to the theme and accent, then lets the Theme page reflect what is now in use. */
	private void applyLook(String themeId, String accent) {
		SwingUtilities.invokeLater(() -> {
			LAF.apply(themeId, accent);
			if (window != null) window.refreshTheme();
		});
	}

	private static String themeKey(String mode) {
		return LAF.isLight(mode) ? PREF_THEME_LIGHT : PREF_THEME_DARK;
	}

	/** Remembers the accent (null: the default) and switches every window to it. */
	public void setAccent(String accent) {
		prefs.setString(PREF_ACCENT, accent);
		applyLook(theme(), accent);
	}

	/** Where the owner's own {@code .theme.json} files live: {@code <home>/themes}. */
	private static Path themesDir(AppConfig config) {
		return config.home().resolve("themes");
	}

	/** Opens (creating if needed) the folder for the owner's own themes. */
	public void openThemesFolder() {
		Path dir = themesDir(config);
		desktop("open the themes folder", d -> {
			try {
				Files.createDirectories(dir);
			} catch (IOException ignored) {
				// best effort; open will simply fail if it truly cannot be created
			}
			d.open(dir.toFile());
		});
	}

	/** Force an immediate lattice value compare and refresh the transcript if it changed. */
	public void refreshNow() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::refreshNow);
			return;
		}
		window.refreshSessions();
		InboxBinding i = inboxBinding;
		if (i != null) i.refresh();
		if (window.isSkillsShowing()) refreshSkills();
	}

	/**
	 * Re-read the skills the current agent can discover, for the Skills
	 * screen: its skillsets and everything they reach, read in-process as the
	 * owner. Nothing is watched for this — the screen re-reads on every visit.
	 */
	public void refreshSkills() {
		EmbeddedVenue v = venue;
		String did = userDID;
		String aid = agentId;
		if (v == null || did == null || aid == null) return;
		Thread t = new Thread(() -> {
			ACell record = v.agentRecord(did, aid);
			List<String> skillsets = SkillIndex.skillsetsOf(record);
			List<SkillIndex.Skill> index = SkillIndex.of(v, did, record);
			String name = AgentRef.displayName(record, aid, standardAssistantId());
			SwingUtilities.invokeLater(() -> {
				if (java.util.Objects.equals(agentId, aid)) window.setSkills(name, skillsets, index);
			});
		}, "brightside-skills-refresh");
		t.setDaemon(true);
		t.start();
	}

	/** Opens General settings (once the venue is up). */
	public void openSettings() {
		if (venue == null || identity == null || vault == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Settings are available once " + APP_NAME + " has started."));
			return;
		}
		SwingUtilities.invokeLater(window::showGeneralSettings);
	}

	/** Opens the Identity section of Settings. */
	public void openProfile() {
		if (venue == null || identity == null || vault == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Your identity is available once " + APP_NAME + " has started."));
			return;
		}
		SwingUtilities.invokeLater(window::showProfileSettings);
	}

	/** Whether a passphrase is currently remembered on this computer. */
	public boolean hasRememberedPassphrase() {
		return RememberedPassphrase.exists(config.home());
	}

	/**
	 * The passphrase remembered in plaintext on this computer, read afresh
	 * (null: none). A reveal that would ask for the passphrase uses this
	 * instead: it is already readable to anyone who can read the file, so
	 * asking would prove nothing. The caller wipes the array — the reveal
	 * gates do.
	 */
	public char[] rememberedPassphrase() {
		return RememberedPassphrase.load(config.home());
	}

	/**
	 * Delete the remembered passphrase from this computer. Unlocking with it
	 * without asking goes too: remembering a passphrase again is not consent to
	 * that, so it is something to opt back into.
	 */
	public void forgetRememberedPassphrase() {
		RememberedPassphrase.clear(config.home());
		prefs.setBool(PREF_UNLOCK, false);
		if (prefs.getBool(PREF_AT_SIGN_IN, false)) {
			// Starting at sign-in unlocks with it, so it goes too.
			prefs.setBool(PREF_AT_SIGN_IN, false);
			applyStartAtSignIn();
		}
	}

	/** Opens the venue's own web dashboard (Settings → General → Advanced). */
	public void openDashboard() {
		EmbeddedVenue v = venue;
		if (v == null) return;
		String url = v.url();
		desktop("open the dashboard", d -> d.browse(URI.create(url)));
	}

	public void openConfigFile() {
		desktop("open the settings file", d -> d.open(configPath.toFile()));
	}

	public void openLogsFolder() {
		Path logs = config.home().resolve("logs");
		desktop("open the logs folder", d -> {
			try {
				java.nio.file.Files.createDirectories(logs);
			} catch (IOException ignored) {
				// best effort; open will simply fail if it truly cannot be created
			}
			d.open(logs.toFile());
		});
	}

	@FunctionalInterface
	private interface DesktopAction {
		void run(Desktop desktop) throws Exception;
	}

	/** Desktop integration runs off the event thread (it can shell out) and never throws. */
	private void desktop(String what, DesktopAction action) {
		Thread t = new Thread(() -> {
			try {
				if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException("no desktop integration available");
				action.run(Desktop.getDesktop());
			} catch (Exception e) {
				log.warn("Could not {}: {}", what, e.toString());
				SwingUtilities.invokeLater(() -> window.showSystemMessage("Could not " + what + ": " + e.getMessage()));
			}
		}, "brightside-desktop");
		t.setDaemon(true);
		t.start();
	}

	/** Stops the venue (flushing its state) and ends the process. */
	public void exit() {
		exit(0);
	}

	/** {@link #exit()}, ending with {@code status}. */
	private void exit(int status) {
		if (!exiting.compareAndSet(false, true)) return;
		toolTokens.shutdownNow(); // no refresh may write it back
		ToolToken.clear(config.home()); // nothing left to connect to
		chatBindings.incrementAndGet();
		log.info("Exit requested");
		java.util.concurrent.CountDownLatch viewsClosed = new java.util.concurrent.CountDownLatch(1);
		SwingUtilities.invokeLater(() -> {
			try {
				if (window != null) window.closeSessions();
				InboxBinding i = inboxBinding;
				if (i != null) i.close();
				if (window != null) window.clearJobs();
				if (window != null) window.setVisible(false);
				if (unlockDialog != null) unlockDialog.dispose();
				if (tray != null) tray.remove();
			} finally {
				viewsClosed.countDown();
			}
		});
		Thread t = new Thread(() -> {
			try {
				// The views stop reading the lattice before the venue closes its store;
				// a stuck event thread delays the close only so long.
				viewsClosed.await(5, java.util.concurrent.TimeUnit.SECONDS);
				closeVenue();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				closeVenue();
			} finally {
				System.exit(status);
			}
		}, "brightside-exit");
		t.setDaemon(true);
		t.start();
	}

	private void closeVenue() {
		// A launch in progress owns the store: let it finish (or fail) first, or
		// the process ends with the store open and the next launch must repair it.
		// Not when the launch thread itself is exiting — it would wait on itself.
		CountDownLatch launch = launching;
		if (launch != null && launchThread != Thread.currentThread()) {
			try {
				if (!launch.await(60, TimeUnit.SECONDS)) log.warn("Exiting while the venue is still launching");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		EmbeddedVenue v = venue;
		if (v == null) return;
		try {
			v.close();
			log.info("Venue closed");
		} catch (Exception e) {
			log.warn("Venue close failed", e);
		}
	}

	/** Logback from the bundled resource, rooted beside the active configuration. */
	static void configureLogging(Path home) {
		Path logHome = (home != null) ? home.toAbsolutePath().normalize() : AppConfig.HOME;
		try {
			Files.createDirectories(logHome.resolve("logs"));
		} catch (IOException e) {
			System.err.println(APP_NAME + ": could not create log directory: " + e);
		}
		try (InputStream is = BrightSide.class.getResourceAsStream("/brightside/logback.xml")) {
			if (is == null) return;
			LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
			JoranConfigurator configurator = new JoranConfigurator();
			configurator.setContext(ctx);
			ctx.reset();
			ctx.putProperty("brightside.home", logHome.toString());
			configurator.doConfigure(is);
		} catch (Exception e) {
			System.err.println(APP_NAME + ": logging configuration failed: " + e);
		}
	}
}
