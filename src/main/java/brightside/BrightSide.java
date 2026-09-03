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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

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
import brightside.ui.onboarding.OnboardingWizard;
import brightside.ui.onboarding.UnlockDialog;
import brightside.ui.onboarding.UnlockPanel;
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

	public static final String APP_NAME = "Brightside";

	/**
	 * Preference keys for the look chosen in Settings → Theme: the mode (light or
	 * dark), each mode's theme (a {@link LAF.Choice#id()}) and the accent.
	 */
	private static final String PREF_MODE = "ui.mode";
	private static final String PREF_THEME_DARK = "ui.theme.dark";
	private static final String PREF_THEME_LIGHT = "ui.theme.light";
	private static final String PREF_ACCENT = "ui.accent";

	private final AppConfig config;
	private final Path configPath;
	private final Prefs prefs;
	private volatile EmbeddedVenue venue;
	private volatile ChatSession chat;
	private volatile ConversationWatcher watcher; // event thread
	private volatile AutoCloseable agentEventsSub; // covia's live agent tap for the bound agent
	private volatile ConversationWatcher inboxWatcher; // event thread; the owner's h/ inbox
	private final java.util.Set<String> notifiedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile List<Inbox.Request> inboxRequests = List.of(); // the owner's and the venue's, as shown
	private volatile Identity identity;
	private volatile covia.grid.Venue client; // in-process client for the acting user
	private volatile String userDID; // the acting user's DID (for in-process lattice reads)
	private volatile String agentId;
	private volatile String viewedSessionId; // the conversation currently on screen
	private volatile List<SessionHistory.Session> sessions = List.of(); // switcher list, newest first
	private MainWindow window; // event thread only
	private UnlockDialog unlockDialog; // event thread only; non-null exactly while locked
	private TrayManager tray; // event thread only
	private final AtomicBoolean exiting = new AtomicBoolean();
	private final AtomicBoolean chatStarted = new AtomicBoolean();
	/** The owner already agreed, at startup, to take over the instance found running. */
	private volatile boolean takeoverApproved;
	private volatile Vault vault; // set once onboarded/unlocked
	private volatile String venueSeedHex; // the venue's Ed25519 seed hex (authorises operator takeover)
	private volatile String llmOverride; // chosen model op for the first onboarding launch

	private enum Mode {
		ONBOARD, UNLOCK
	}

	/** How a chat (re)bind should be presented: first launch, a name change, an agent switch, or a change of acting principal. */
	private enum Bind {
		FIRST, NAME_CHANGE, AGENT_SWITCH, PRINCIPAL_SWITCH
	}

	/**
	 * Whether the app is acting as the venue operator rather than as the named
	 * user (Settings → Identity → Switch user). The operator sees and chats with the
	 * venue's own agents — Odin — and answers the venue's Inbox; everyday use is
	 * as the user. Never persisted: every launch starts as the user.
	 */
	private volatile boolean actingAsOperator;

	BrightSide(AppConfig config, Path configPath, Prefs prefs) {
		this.config = config;
		this.configPath = configPath;
		this.prefs = prefs;
	}

	/** Entry point. Optional argument: path to the configuration file. */
	public static void main(String[] args) {
		Path path = (args.length > 0) ? Path.of(args[0]).toAbsolutePath().normalize() : AppConfig.DEFAULT_FILE;
		configureLogging(path.getParent());
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
		new BrightSide(config, path, prefs).start();
	}

	/**
	 * Shows the first screen — the onboarding wizard in the main window for a
	 * new person, or the unlock dialog on its own for a returning one, with the
	 * main window built but hidden until the passphrase is accepted — then
	 * brings the venue up in the background once the vault is open.
	 */
	void start() {
		Identity saved = Identity.load(config.home());
		identity = saved;
		Mode mode = decideMode();

		// Another instance already running? Ask now, before any window — so a
		// Cancel needs no passphrase first. The handover itself waits for the
		// vault: the shutdown request must be signed with the venue seed.
		if (Takeover.isRunning(config.port())) {
			if (!confirmTakeover()) {
				log.info("Another Brightside instance is running and takeover was declined; exiting");
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
					case UNLOCK -> showUnlockDialog(RememberedPassphrase.load(config.home()));
				}
			});
		} catch (Exception e) {
			throw new IllegalStateException("Could not create the main window", e);
		}
		// Flush venue state on any JVM exit (Ctrl-C, SIGTERM) ahead of Convex's
		// own store shutdown — the same ordering MainVenue uses.
		Shutdown.addHook(Shutdown.SERVER - 10, this::closeVenue);
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
		if (takeoverApproved) unlockDialog.showNote("Another Brightside is running; it will hand over once you unlock.");
	}

	/** The passphrase was accepted (or the identity recovered): drop the unlock dialog and bring up the window. */
	private void unlocked() {
		if (unlockDialog != null) {
			unlockDialog.dispose();
			unlockDialog = null;
		}
		window.showChatStartup();
		window.showAndFocus();
	}

	/** Unlock or recovery failed: back to the dialog with the reason. Event thread. */
	private void unlockFailed(String message) {
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
	 * the other instance has stepped aside <em>and the venue is actually up</em>
	 * does {@code clearToShow} run (on the event thread): the moment the main
	 * window may appear. Two windows are never on screen together, and the
	 * window never shows without live data behind it — while the venue boots,
	 * the unlock dialog stays up showing progress.
	 */
	private void launchVenueWith(AMap<AString, ACell> venueConfig, Runnable clearToShow) {
		try {
			// Another instance already holds the venue store? Offer to take over
			// (ask it to shut down cleanly) rather than fail on the store lock.
			int port = config.port();
			if (Takeover.isRunning(port)) {
				boolean proceed;
				try {
					proceed = takeOver(port);
				} catch (Exception e) {
					// The running instance refused or didn't stop — typically a
					// different identity (its key isn't ours), so we can't ask it.
					log.warn("Could not take over the running instance on port {}: {}", port, e.toString());
					startupFailed("Brightside is already running on this computer and couldn't be taken over"
						+ " — please quit it (tray icon ▸ Quit) and try again.");
					return;
				}
				if (!proceed) {
					log.info("Another Brightside instance is running and takeover was cancelled; exiting");
					System.exit(0);
					return;
				}
			}
			// The slow part: opening the encrypted store, adapters, provisioning.
			SwingUtilities.invokeLater(() -> {
				if (unlockDialog != null) unlockDialog.showProgress("Starting the venue…");
			});
			EmbeddedVenue v = EmbeddedVenue.launch(venueConfig, this::exit);
			venue = v;
			log.info("Venue '{}' ready at {} as {}", v.name(), v.url(), v.did());
			// Reveal only now that the venue is up: the dialog drops and the window
			// comes up in its startup state; the chat bind's own event-thread
			// update is queued after it and fills in the conversation.
			if (clearToShow != null) SwingUtilities.invokeLater(clearToShow);
			onVenueReady();
		} catch (Throwable t) {
			log.error("Venue failed to start", t);
			closeVenue();
			venue = null;
			startupFailed(t);
		}
	}

	/** Startup failed: say so where the owner is looking — the unlock dialog while locked, else the window. */
	private void startupFailed(String message) {
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showError(message);
			else window.startupFailed(message);
		});
	}

	private void startupFailed(Throwable t) {
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showError("Sorry — Brightside couldn't start up. See the logs.");
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
				AMap<AString, ACell> venueConfig = v.secure(config.venueConfig(), setup.seedHex());
				llmOverride = (setup.providerId() == null)
					? AppConfig.ECHO_LLM_OPERATION
					: Providers.modelOp(setup.providerId(), setup.modelId());
				config.persistModel(llmOverride);

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
					RememberedPassphrase.clear(home);
				}
				this.vault = v;
				this.venueSeedHex = seedHex;
				Identity saved = Identity.load(home);
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
				AMap<AString, ACell> venueConfig = v.secure(config.venueConfig(), seedHex);
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
		ConversationWatcher oldWatcher = watcher;
		ConversationWatcher oldInbox = inboxWatcher;
		watcher = null;
		inboxWatcher = null;
		notifiedRequests.clear();
		chat = null;
		client = null;
		userDID = null;
		agentId = null;
		viewedSessionId = null;
		sessions = List.of();
		identity = null;
		vault = null;
		actingAsOperator = false;
		chatStarted.set(false);
		SwingUtilities.invokeLater(() -> {
			if (oldWatcher != null) oldWatcher.stop();
			closeQuietly(agentEventsSub);
			agentEventsSub = null;
			if (oldInbox != null) oldInbox.stop();
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
				RememberedPassphrase.clear(home);
				v.storeSeed(seedHex); // re-encrypt the seed under the new passphrase
				this.vault = v;
				this.venueSeedHex = seedHex;
				Identity saved = Identity.load(home);
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
				AMap<AString, ACell> venueConfig = v.secure(config.venueConfig(), seedHex);
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
	 * operator's, so each user's agents resolve their own key and Odin the
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
		config.persistModel(op);
		Providers.Provider p = Providers.byId(providerId);
		boolean keyLive = p == null || !p.needsApiKey() || keyResolves(p.secretName());
		if (!keyLive) {
			// Saved, but no key resolves for this provider yet — switching the
			// running agent to it now would fail at the model call. A key added
			// in Settings → Model takes effect immediately, so no restart needed.
			String label = (p != null) ? p.label() : providerId;
			SwingUtilities.invokeLater(() -> window.showSystemMessage(
				"Saved. Add the " + label + " API key (Settings → Model) and it takes effect straight away."));
			return;
		}
		ensureOdin(venue);
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
	 * user's and the operator's, so the chat agent and Odin both resolve it —
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
			v.secrets(id.userDID(v.did()), true).store(p.secretName(), value, v.secretKey());
			v.secrets(v.did(), true).store(p.secretName(), value, v.secretKey());
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
			bot = Discord.status(v.clientAs(did), v.resolve(v.did(), Discord.recordPath(did)));
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
				Moltbook.clearRecord(user);
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
		Moltbook.saveRecord(user, name, claimUrl, verificationCode);
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
				ACell record = v.resolve(did, Moltbook.RECORD_PATH);
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

	/** Signs the claims used by Settings → Auth. Package-visible for claim-level tests. */
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
			if (store == null || !store.exists(name)) return false;
			store.delete(name);
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
			if (!canRevealPrivateSeed()) throw new IOException("No logged-in identity");
			String candidate = Vault.open(config.home(), passphrase).seedHex();
			if (!candidate.equals(venueSeedHex)) {
				throw new IOException("The passphrase did not unlock the running identity");
			}
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


	/** The chat config with the chosen model applied (onboarding's, else the saved one). */
	private AppConfig.Chat effectiveChat() {
		AppConfig.Chat c = config.chat();
		if (llmOverride == null || llmOverride.equals(c.llmOperation())) return c;
		return new AppConfig.Chat(c.agentId(), c.operation(), llmOverride, c.systemPrompt());
	}

	/**
	 * Prompts (Take Over / Cancel); on Take Over, asks the running instance to
	 * shut down cleanly and waits for it to release the store. Returns true to
	 * proceed with launch, false to cancel this instance.
	 */
	private boolean takeOver(int port) throws Exception {
		// Already agreed at startup; otherwise the instance appeared since, so ask now.
		if (!takeoverApproved && !confirmTakeover()) return false;
		takeoverApproved = false;
		log.info("Taking over from a running Brightside instance on port {}", port);
		SwingUtilities.invokeLater(() -> {
			if (unlockDialog != null) unlockDialog.showProgress("Taking over from the running Brightside…");
		});
		Takeover.requestShutdown(port, Takeover.venueDID(port), venueSeedHex);
		if (!Takeover.waitUntilDown(port, 20_000)) {
			throw new IllegalStateException("the previous instance did not shut down in time");
		}
		return true;
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
				int choice = JOptionPane.showOptionDialog(parent,
					"Brightside is already running on this computer.\n\nTake over from it?",
					APP_NAME, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
					null, options, options[0]);
				takeOver[0] = (choice == JOptionPane.OK_OPTION);
			});
		} catch (Exception e) {
			log.warn("Takeover prompt failed; cancelling", e);
			return false;
		}
		return takeOver[0];
	}

	/**
	 * The venue is up. A returning person (name already known) — or a new one
	 * who typed their name while it booted — starts chatting now; otherwise we
	 * wait for {@link #submitName}.
	 */
	private void onVenueReady() {
		// Default skills are installed by BrightsideAdapter at venue launch. Odin
		// is not touched here: he is created or reconfigured when first needed
		// (acting as the operator, or a model change), so a launch submits no jobs.
		EmbeddedVenue v = venue;
		Vault vlt = vault;
		Identity id = identity;
		if (v != null && vlt != null && id != null) {
			migrateProviderKeys(v, vlt, id.userDID(v.did()));
		}
		if (id != null) startChatOnce(venue, bindIdentityToVenue(id, venue));
	}

	/**
	 * Keeps {@link Odin}, the operator's administrative agent, configured with
	 * the chat's model — at launch and whenever the model changes. Best-effort
	 * and off the calling thread: the chat never waits on him.
	 */
	private void ensureOdin(EmbeddedVenue v) {
		if (v == null) return;
		String model = effectiveChat().llmOperation();
		Thread t = new Thread(() -> {
			try {
				Odin.ensure(v, model);
			} catch (Exception e) {
				log.warn("{} is not available: {}", Odin.AGENT_ID, e.toString());
			}
		}, "brightside-odin");
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

	private void persistIdentity(Identity id) {
		try {
			id.save(config.home());
			log.info("Saved name '{}' to {}", id.name(), config.home().resolve(Identity.FILE_NAME));
		} catch (IOException e) {
			log.warn("Could not save the chosen name", e);
		}
	}

	/** Pins and persists the full user DID once the home venue identity is known. */
	private Identity bindIdentityToVenue(Identity id, EmbeddedVenue v) {
		Identity bound = id.withVenueDID(v.did());
		identity = bound;
		if (id.did() == null) persistIdentity(bound);
		return bound;
	}

	/** Starts the first chat exactly once, whichever of name/venue arrives last. */
	private void startChatOnce(EmbeddedVenue v, Identity id) {
		if (v == null || id == null) return;
		if (chatStarted.compareAndSet(false, true)) startChatBackground(v, id, effectiveChat(), Bind.FIRST);
	}

	private void startChatBackground(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind) {
		// The operator's agents keep the configuration their records hold —
		// Odin's is owned by Odin.ensure, never by the chat's persona config.
		boolean configureExisting = !actingAsOperator && chatConfig.agentId().equals(config.chat().agentId());
		startChatBackground(v, id, chatConfig, bind, configureExisting);
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
		Thread t = new Thread(() -> startChat(v, id, chatConfig, bind, configureExisting), "brightside-chat");
		t.setDaemon(true);
		t.start();
	}

	/** Binds a chat session to {@code id}'s principal and prepares its conversation view. */
	private void startChat(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind) {
		startChat(v, id, chatConfig, bind, true);
	}

	private void startChat(EmbeddedVenue v, Identity id, AppConfig.Chat chatConfig, Bind bind,
			boolean configureExisting) {
		String did = actingDID(id, v);
		covia.grid.Venue userClient = v.clientAs(did);
		ChatSession session = new ChatSession(userClient, chatConfig, id.name(), configureExisting);
		chat = session;

		// The agent is not created or reconfigured here: ChatSession.ensureAgent
		// runs on the first send, so binding the chat submits no jobs — and Home
		// leaves ChatSession.sessionId null until the user actually sends their
		// first message. Odin is the exception when the operator turns to him:
		// he is configured by his own ensure, so the session must find him there.
		if (actingAsOperator && Odin.AGENT_ID.equals(chatConfig.agentId())) {
			try {
				Odin.ensure(v, chatConfig.llmOperation());
			} catch (Exception e) {
				log.warn("{} not ready", Odin.AGENT_ID, e);
			}
		}
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
		this.client = userClient;
		this.agentId = aid;
		this.userDID = did;

		// The venue is in-process: read the agent record straight from the lattice
		// (no job) and project it, rather than submitting a covia:read.
		convex.core.data.ACell record = v.agentRecord(did, aid);
		// Home keeps the conversation you were in — at startup, the most recent
		// one — until a new conversation is explicitly created.
		SessionHistory.Snapshot history = SessionHistory.snapshotOf(record, null);
		if (history != null) session.resume(history.sessionId());
		List<SessionHistory.Item> turns = (history != null) ? history.items() : List.of();
		viewedSessionId = (history != null) ? history.sessionId() : null;
		List<SessionHistory.Session> sessionList = (record != null) ? SessionHistory.sessionsOf(record) : List.of();
		sessions = sessionList;
		List<brightside.model.AgentRef> agentRefs = listAgents(v, did, aid);
		// The inbox is per user, not per agent: (re)bind it with the user. It
		// shows the venue's own requests too — the app is the operator.
		convex.core.data.ACell inboxValue = (bind == Bind.AGENT_SWITCH) ? null : inboxes(v, did);
		List<Inbox.Request> inbox = parseInboxes(v, did, inboxValue);
		log.info("Chatting as {} with agent '{}' — showing {} live message(s) across {} saved conversation(s)",
			id.label(), aid, turns.size(), sessionList.size());

		SwingUtilities.invokeLater(() -> {
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
			window.setAgents(agentRefs, aid, defaultAgentId());
			window.setConversations(sessionList, viewedSessionId);
			if (bind != Bind.AGENT_SWITCH) bindInbox(v, did, inboxValue, inbox);
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name() + (actingAsOperator ? " (" + id.operatorName() + ")" : ""));
			// Watch this agent's value: covia's in-process agent event tap kicks
			// an immediate check on every run-loop event (the compare absorbs
			// duplicates), and a slow poll remains as the fallback for
			// out-of-band changes. In-process reads — no jobs.
			ConversationWatcher w = watcher;
			if (w != null) w.stop();
			closeQuietly(agentEventsSub);
			ConversationWatcher fresh = new ConversationWatcher(() -> v.agentRecord(did, aid), record,
				() -> window.isChatShowing(),
				this::onAgentChanged, ConversationWatcher.FALLBACK_INTERVAL_MS);
			watcher = fresh;
			fresh.start();
			agentEventsSub = v.subscribeAgent(did, aid, event -> {
				SwingUtilities.invokeLater(fresh::checkNow);
				if (!concernsChat(event)) return;
				// The tool's display name is resolved here, on the venue's thread
				// (an in-process read), so the event thread only paints.
				String label = liveToolLabel(v, did, event);
				SwingUtilities.invokeLater(() -> window.showActivity(event, label));
			});
		});
	}

	// ------------------------------------------------------------------
	// Agents (multi-agent switcher)
	// ------------------------------------------------------------------

	/** Switch the chat to another existing agent (from the agents pane). */
	public void switchAgent(String agentId) {
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (v == null || id == null || agentId == null || agentId.equals(this.agentId)) return;
		startChatBackground(v, id, chatConfigFor(agentId), Bind.AGENT_SWITCH);
	}

	/** Create a general-purpose agent using the current default model. */
	public void createAgent(String rawName) {
		String name = (rawName != null) ? rawName.trim() : "";
		String prompt = brightside.model.AgentTemplate.GENERAL.systemPrompt(name);
		createAgent(name, currentModelOp(), prompt);
	}

	/** Create (and switch to) a new agent with the chosen model and starting prompt. */
	public void createAgent(String rawName, String modelOp, String systemPrompt) {
		String aid = slug(rawName);
		EmbeddedVenue v = venue;
		Identity id = identity;
		if (aid.isEmpty() || v == null || id == null || aid.equals(this.agentId)) return;
		AppConfig.Chat base = effectiveChat();
		String chosenModel = (modelOp == null || modelOp.isBlank()) ? base.llmOperation() : modelOp;
		String chosenPrompt = (systemPrompt == null || systemPrompt.isBlank())
			? brightside.model.AgentTemplate.GENERAL.systemPrompt(displayNameFor(aid)) : systemPrompt;
		AppConfig.Chat chosen = new AppConfig.Chat(aid, base.operation(), chosenModel, chosenPrompt);
		// Applying the supplied configuration is deliberate here. Later switches to
		// the agent preserve what its record already holds.
		startChatBackground(v, id, chosen, Bind.AGENT_SWITCH, true);
	}

	/** Show the agent info screen for {@code agentId}. */
	public void showAgentInfo(String agentId) {
		Venue c = client;
		EmbeddedVenue v = venue;
		Identity id = identity;
		String did = userDID;
		if (c == null || v == null || id == null || did == null || agentId == null) return;
		boolean standard = agentId.equals(defaultAgentId());
		Thread t = new Thread(() -> {
			AgentInfo.Summary info = AgentInfo.load(c, v.agentRecord(did, agentId), agentId, did, id.name(), standard);
			SwingUtilities.invokeLater(() -> {
				if (info == null) window.showSystemMessage("Sorry — I couldn't read that agent.");
				else window.showAgentInfo(info);
			});
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

	/** Removes an agent's record ({@code agent:delete} with {@code remove}), not merely marking it terminated. */
	static void deleteAgent(Venue client, String agentId) throws Exception {
		invokeOp(client, "v/ops/agent/delete", Maps.of("agentId", agentId, "remove", true));
	}

	// ------------------------------------------------------------------
	// Inbox (HITL requests to the owner)
	// ------------------------------------------------------------------

	/**
	 * Shows the owner's inbox and watches its value. Event thread; called whenever
	 * the acting user (re)binds. Requests already waiting just badge the tab —
	 * only ones that arrive while running get a tray balloon.
	 */
	private void bindInbox(EmbeddedVenue v, String did, convex.core.data.ACell value, List<Inbox.Request> requests) {
		ConversationWatcher old = inboxWatcher;
		if (old != null) old.stop();
		notifiedRequests.clear();
		for (Inbox.Request r : requests) if (r.open()) notifiedRequests.add(r.id());
		inboxRequests = requests;
		window.setInbox(requests);
		inboxWatcher = new ConversationWatcher(() -> inboxes(v, did), value, () -> window.isShowing(), this::onInboxChanged);
		inboxWatcher.start();
	}

	/**
	 * The owner's inbox and the venue's own, as one value the watcher can
	 * compare. The venue's holds what {@link Odin} asks his owner; the person at
	 * the keyboard is that owner, so it belongs in the same Inbox.
	 */
	private static convex.core.data.ACell inboxes(EmbeddedVenue v, String did) {
		// As the operator the two are one inbox; list it once.
		convex.core.data.ACell own = did.equals(v.did()) ? null : v.inbox(did);
		return convex.core.data.Vectors.of(own, v.inbox(v.did()));
	}

	private static List<Inbox.Request> parseInboxes(EmbeddedVenue v, String did, convex.core.data.ACell value) {
		if (!(value instanceof convex.core.data.AVector<?> pair) || pair.count() != 2) return List.of();
		return Inbox.merge(Inbox.parse((convex.core.data.ACell) pair.get(0), did),
			Inbox.parse((convex.core.data.ACell) pair.get(1), v.did()));
	}

	/** The inbox value changed: refresh the tab and badge; balloon each request seen waiting for the first time. */
	private void onInboxChanged(convex.core.data.ACell value) {
		EmbeddedVenue v = venue;
		String did = userDID;
		List<Inbox.Request> requests = (v != null && did != null) ? parseInboxes(v, did, value) : List.of();
		inboxRequests = requests;
		window.setInbox(requests);
		for (Inbox.Request r : requests) {
			if (r.open() && notifiedRequests.add(r.id()) && tray != null) {
				tray.notify(APP_NAME + " needs your decision", r.title());
			}
		}
	}

	/**
	 * Answer a request as the inbox's owner — the named user, or the operator for
	 * a request to the venue — never as an agent; then refresh the inbox.
	 */
	public void answerRequest(String id, Inbox.Answer answer) {
		respond(id, c -> Inbox.answer(c, id, answer), "Answered.");
	}

	public void rejectRequest(String id, String reason) {
		respond(id, c -> Inbox.reject(c, id, reason), "Rejected.");
	}

	private interface InboxAction {
		void run(Venue client) throws Exception;
	}

	private void respond(String id, InboxAction action, String done) {
		Venue c = (id != null) ? clientFor(id) : null;
		if (c == null) return;
		Thread t = new Thread(() -> {
			String note;
			try {
				action.run(c);
				note = done;
			} catch (Exception e) {
				log.warn("Could not respond to request {}: {}", id, e.toString());
				note = "Sorry — that didn't go through: " + rootMessage(e);
			}
			String n = note;
			SwingUtilities.invokeLater(() -> {
				window.showInboxNotice(n);
				ConversationWatcher w = inboxWatcher;
				if (w != null) w.checkNow();
			});
		}, "brightside-hitl-respond");
		t.setDaemon(true);
		t.start();
	}

	/** The client that owns the inbox holding request {@code id}: the named user's, or the operator's for the venue's own. */
	private Venue clientFor(String id) {
		EmbeddedVenue v = venue;
		for (Inbox.Request r : inboxRequests) {
			if (id.equals(r.id())) return (v != null && v.did().equals(r.owner())) ? v.operator() : client;
		}
		return client;
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return (t.getMessage() != null) ? t.getMessage() : t.toString();
	}

	/**
	 * The chat config for {@code aid}: Odin's own identity for the operator's
	 * default, the default persona for the configured chat agent, else a named one.
	 */
	private AppConfig.Chat chatConfigFor(String aid) {
		AppConfig.Chat base = effectiveChat();
		if (actingAsOperator && aid.equals(Odin.AGENT_ID)) {
			return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), Odin.SYSTEM_PROMPT);
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
		java.util.LinkedHashMap<String, AgentRef> map = new java.util.LinkedHashMap<>();
		String def = defaultAgentId();
		map.put(def, new AgentRef(def, displayNameFor(def)));
		if (currentAid != null) {
			map.putIfAbsent(currentAid, new AgentRef(currentAid, displayNameFor(currentAid)));
		}
		for (String aid : v.agents(did)) {
			map.putIfAbsent(aid, new AgentRef(aid, displayNameFor(aid)));
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
		EmbeddedVenue v = venue;
		String did = userDID;
		if (v != null && did != null && agentId != null) {
			convex.core.data.ACell record = v.agentRecord(did, agentId);
			AString name = (record == null) ? null
				: convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(record, "config", "name"));
			if (name != null && !name.toString().isBlank()) return name.toString();
		}
		return agentId;
	}

	/** An agent id from a typed name: case kept, anything a path or DID cannot carry becomes {@code -}. */
	private static String slug(String name) {
		if (name == null) return "";
		return name.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("(^-|-$)", "");
	}

	/**
	 * The agent record changed (a new turn here or an out-of-band update):
	 * refresh the switcher list and re-render the conversation currently on
	 * screen — the one the user picked, not necessarily the newest. Called on
	 * the event thread; the cell projection runs off it.
	 */
	/** Humanised tool labels by tool name, resolved once from the op catalogue. */
	private final Map<String, String> toolLabels = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Whether a live agent event belongs to the conversation on screen. Events
	 * for another session (a Discord turn on the same agent, say) are not
	 * shown; before the venue has accepted the first message there is no
	 * session id yet and every event is the chat's.
	 */
	private boolean concernsChat(covia.venue.AgentEvents.Event event) {
		ChatSession c = chat;
		String sid = (c != null) ? c.sessionId() : null;
		return sid == null || event.concerns(Strings.create(sid));
	}

	/**
	 * The display name of the tool a {@code tool:start} event names, else null:
	 * the label the venue put on the event when it has one beyond the raw name
	 * (covia#463), else the op asset's {@code name} from the catalogue. Called
	 * on the venue's emitting thread — in-process reads only, never blocking.
	 */
	private String liveToolLabel(EmbeddedVenue v, String did, covia.venue.AgentEvents.Event event) {
		if (!covia.venue.AgentEvents.TOOL_START.equals(event.type())) return null;
		AString name = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(event.data(), "name"));
		if (name == null) return null;
		AString label = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(event.data(), "activityLabel"));
		if (label != null && !label.isEmpty() && !label.equals(name)) return label.toString();
		return toolLabel(v, did, name.toString());
	}

	/**
	 * The tool's display name: its op asset's {@code name} when one resolves,
	 * else the tool name with the underscores spaced. Cached per tool.
	 */
	private String toolLabel(EmbeddedVenue v, String did, String toolName) {
		return toolLabels.computeIfAbsent(toolName, t -> {
			for (String path : toolPathCandidates(t)) {
				ACell asset = v.resolve(did, path);
				AString n = (asset == null) ? null
					: convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(asset, "name"));
				if (n != null && !n.isEmpty()) return n.toString();
			}
			return t.replace('_', ' ');
		});
	}

	/**
	 * Where a provider tool name probably lives in the catalogue:
	 * {@code moltbook_read_post} → {@code v/ops/moltbook/read-post}, then
	 * {@code v/ops/moltbook/read/post}. Package-visible for the mapping test.
	 */
	static List<String> toolPathCandidates(String toolName) {
		List<String> out = new ArrayList<>();
		int us = toolName.indexOf('_');
		if (us > 0) {
			out.add("v/ops/" + toolName.substring(0, us) + "/" + toolName.substring(us + 1).replace('_', '-'));
		}
		out.add("v/ops/" + toolName.replace('_', '/'));
		return out;
	}

	private static void closeQuietly(AutoCloseable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Exception ignored) {
				// unsubscribing at teardown is best-effort
			}
		}
	}

	private void onAgentChanged(convex.core.data.ACell record) {
		EmbeddedVenue v = venue;
		String did = userDID;
		String aid = agentId;
		new SwingWorker<Void, Void>() {
			private List<SessionHistory.Session> list;
			private SessionHistory.Snapshot snap;
			private String vsid;
			private boolean sessionActive;
			private List<AgentRef> agents;

			@Override
			protected Void doInBackground() {
				list = SessionHistory.sessionsOf(record);
				// The send-completion reconciliation normally pins a newly minted id;
				// this is also a safe fallback for changes made by another client.
				vsid = (viewedSessionId != null) ? viewedSessionId
					: (chat != null ? chat.sessionId() : null);
				snap = (vsid != null) ? SessionHistory.snapshotOf(record, vsid) : null;
				sessionActive = SessionHistory.isSessionActive(record, vsid);
				// A turn can create, rename or delete agents (the assistant's agent
				// tools): rebuild the pane's list from g/ while off the event thread.
				agents = (v != null && did != null) ? listAgents(v, did, aid) : null;
				return null;
			}

			@Override
			protected void done() {
				sessions = list;
				if (vsid != null) {
					viewedSessionId = vsid;
					if (snap != null) window.refreshConversation(snap.items(), sessionActive);
				}
				window.setConversations(list, viewedSessionId);
				if (agents != null && java.util.Objects.equals(agentId, aid)) {
					window.setAgents(agents, aid, defaultAgentId());
				}
			}
		}.execute();
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
		if (v == null || did == null) return;
		Thread t = new Thread(() -> {
			List<AgentRef> refs = listAgents(v, did, aid);
			SwingUtilities.invokeLater(() -> {
				if (java.util.Objects.equals(agentId, aid)) window.setAgents(refs, aid, defaultAgentId());
			});
		}, "brightside-agents-refresh");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Reconciles a successful local send directly from its returned session id.
	 * This is deliberately event-driven: the watcher may have observed the record
	 * before {@link ChatSession} received the id, so polling alone would make
	 * selection of the first session timing-dependent.
	 */
	public void conversationCommitted(String sessionId) {
		EmbeddedVenue v = venue;
		ChatSession expected = chat;
		String aid = agentId;
		String did = userDID;
		if (v == null || expected == null || aid == null || did == null || sessionId == null
				|| !sessionId.equals(expected.sessionId())) return;

		viewedSessionId = sessionId;
		Thread t = new Thread(() -> {
			try {
				ACell record = v.agentRecord(did, aid);
				SessionHistory.Snapshot snap = SessionHistory.snapshotOf(record, sessionId);
				List<SessionHistory.Session> list = SessionHistory.sessionsOf(record);
				boolean sessionActive = SessionHistory.isSessionActive(record, sessionId);
				SwingUtilities.invokeLater(() -> {
					if (chat != expected || !java.util.Objects.equals(agentId, aid)
							|| !sessionId.equals(expected.sessionId())) return;
					viewedSessionId = sessionId;
					sessions = list;
					if (snap != null) window.refreshConversation(snap.items(), sessionActive);
					window.setConversations(list, sessionId);
				});
			} catch (Exception e) {
				log.warn("Could not reconcile committed conversation {}: {}", sessionId, e.toString());
			}
		}, "brightside-chat-committed");
		t.setDaemon(true);
		t.start();
	}

	/** Switch the chat to a past conversation, reopening its transcript and continuing it. */
	public void openSession(String sessionId) {
		EmbeddedVenue v = venue;
		ChatSession s = chat;
		String aid = agentId, did = userDID;
		if (v == null || s == null || aid == null || did == null || sessionId == null) return;
		if (sessionId.equals(viewedSessionId)) return;
		Thread t = new Thread(() -> {
			ACell record = v.agentRecord(did, aid);
			SessionHistory.Snapshot snap = SessionHistory.snapshotOf(record, sessionId);
			if (snap == null) return;
			s.resume(snap.sessionId());
			List<SessionHistory.Session> list = SessionHistory.sessionsOf(record);
			SwingUtilities.invokeLater(() -> {
				viewedSessionId = snap.sessionId();
				sessions = list;
				window.showConversation(snap.items());
				window.setConversations(list, viewedSessionId);
			});
		}, "brightside-open-session");
		t.setDaemon(true);
		t.start();
	}

	/** Set or clear a conversation's title (empty/blank clears it back to auto). */
	public void renameSession(String sessionId, String title) {
		Venue c = client;
		String aid = agentId;
		if (c == null || aid == null || sessionId == null) return;
		Thread t = new Thread(() -> {
			try {
				AMap<AString, ACell> input = Maps.of("agentId", aid, "sessionId", sessionId);
				if (title != null && !title.isBlank()) {
					input = input.assoc(Strings.create("title"), Strings.create(title.trim()));
				}
				invokeOp(c, "v/ops/agent/rename-session", input);
			} catch (Exception e) {
				log.warn("Could not rename session {}: {}", sessionId, e.toString());
				SwingUtilities.invokeLater(() -> window.showSystemMessage("Sorry — I couldn't rename that conversation."));
				return;
			}
			List<SessionHistory.Session> list = SessionHistory.sessionsOf(venue.agentRecord(userDID, aid));
			SwingUtilities.invokeLater(() -> {
				sessions = list;
				window.setConversations(list, viewedSessionId);
			});
		}, "brightside-rename-session");
		t.setDaemon(true);
		t.start();
	}

	/** Copy a past conversation's transcript to the clipboard as plain text. */
	public void copyTranscript(String sessionId) {
		EmbeddedVenue v = venue;
		String aid = agentId, did = userDID;
		if (v == null || aid == null || did == null || sessionId == null) return;
		Thread t = new Thread(() -> {
			SessionHistory.Snapshot snap = SessionHistory.snapshotOf(v.agentRecord(did, aid), sessionId);
			if (snap == null) {
				SwingUtilities.invokeLater(() -> window.showSystemMessage("Sorry — I couldn't read that conversation."));
				return;
			}
			String text = SessionHistory.plainText(snap.items());
			SwingUtilities.invokeLater(() -> {
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new java.awt.datatransfer.StringSelection(text), null);
				window.showSystemMessage("Copied that conversation to the clipboard.");
			});
		}, "brightside-copy-transcript");
		t.setDaemon(true);
		t.start();
	}

	/** Show the full context the assistant's model receives for a conversation. */
	public void showSessionInfo(String sessionId) {
		covia.grid.Venue c = client;
		EmbeddedVenue v = venue;
		String aid = agentId, did = userDID;
		if (c == null || v == null || aid == null || did == null || sessionId == null) return;
		String title = titleOf(sessionId);
		Thread t = new Thread(() -> {
			// The assembled context is a computation (agent:context op); the raw
			// turns and the discoverable skills are lattice reads, done in-process.
			AgentContext.Report report = AgentContext.load(c, aid, sessionId);
			ACell record = v.agentRecord(did, aid);
			List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(record, sessionId);
			List<SkillIndex.Skill> skills = SkillIndex.of(v, did, record);
			SwingUtilities.invokeLater(() -> {
				if (report == null) window.showSystemMessage("Sorry — I couldn't read the context for that conversation.");
				else window.showContextInfo(report, turns, skills, title);
			});
		}, "brightside-session-info");
		t.setDaemon(true);
		t.start();
	}

	private String titleOf(String sessionId) {
		return sessions.stream()
			.filter(s -> sessionId.equals(s.sessionId()))
			.map(SessionHistory.Session::title)
			.findFirst().orElse("this conversation");
	}

	/** Delete a conversation. If it was the one on screen, drop back to a new chat. */
	public void deleteSession(String sessionId) {
		Venue c = client;
		String aid = agentId;
		ChatSession s = chat;
		if (c == null || aid == null || sessionId == null) return;
		Thread t = new Thread(() -> {
			try {
				invokeOp(c, "v/ops/agent/delete-session", Maps.of("agentId", aid, "sessionId", sessionId));
			} catch (Exception e) {
				log.warn("Could not delete session {}: {}", sessionId, e.toString());
				SwingUtilities.invokeLater(() -> window.showSystemMessage("Sorry — I couldn't delete that conversation."));
				return;
			}
			boolean wasViewed = sessionId.equals(viewedSessionId);
			List<SessionHistory.Session> list = SessionHistory.sessionsOf(venue.agentRecord(userDID, aid));
			SwingUtilities.invokeLater(() -> {
				sessions = list;
				if (wasViewed) {
					if (s != null) s.reset();
					viewedSessionId = null;
					window.clearChat();
					window.showSystemMessage("Conversation deleted.");
				}
				window.setConversations(list, viewedSessionId);
			});
		}, "brightside-delete-session");
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
		return prefs.getBool("tray.minimise", false);
	}

	public void setMinimiseToTray(boolean value) {
		prefs.setBool("tray.minimise", value);
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
		ConversationWatcher w = watcher;
		if (w != null) w.checkNow();
		ConversationWatcher i = inboxWatcher;
		if (i != null) i.checkNow();
	}

	public void newConversation() {
		ChatSession c = chat;
		if (c == null) return;
		// A fresh session is minted on the next message; the previous one stays
		// in the venue's history but is no longer the one shown. It joins the
		// switcher once its first message lands (the watcher picks it up).
		c.reset();
		viewedSessionId = null;
		SwingUtilities.invokeLater(() -> {
			window.clearChat();
			window.setConversations(sessions, null);
		});
	}

	/** Opens General settings (once the venue is up). */
	public void openSettings() {
		if (venue == null || identity == null || vault == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Settings are available once Brightside has started."));
			return;
		}
		SwingUtilities.invokeLater(window::showGeneralSettings);
	}

	/** Opens the Identity section of Settings. */
	public void openProfile() {
		if (venue == null || identity == null || vault == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Your identity is available once Brightside has started."));
			return;
		}
		SwingUtilities.invokeLater(window::showProfileSettings);
	}

	/** Whether a passphrase is currently remembered on this computer. */
	public boolean hasRememberedPassphrase() {
		return RememberedPassphrase.exists(config.home());
	}

	/** Delete the remembered passphrase from this computer. */
	public void forgetRememberedPassphrase() {
		RememberedPassphrase.clear(config.home());
	}

	/** Opens the Auth section of Settings, once the venue is up. */
	public void openAccessToken() {
		if (venue == null || identity == null || vault == null || venueSeedHex == null) {
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Access tokens are available once Brightside has started."));
			return;
		}
		SwingUtilities.invokeLater(window::showAuthSettings);
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
		if (!exiting.compareAndSet(false, true)) return;
		log.info("Exit requested");
		SwingUtilities.invokeLater(() -> {
			ConversationWatcher w = watcher;
			if (w != null) w.stop();
			closeQuietly(agentEventsSub);
			ConversationWatcher i = inboxWatcher;
			if (i != null) i.stop();
			if (window != null) window.setVisible(false);
			if (unlockDialog != null) unlockDialog.dispose();
			if (tray != null) tray.remove();
		});
		Thread t = new Thread(() -> {
			try {
				closeVenue();
			} finally {
				System.exit(0);
			}
		}, "brightside-exit");
		t.setDaemon(true);
		t.start();
	}

	private void closeVenue() {
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
			System.err.println("BrightSide: could not create log directory: " + e);
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
			System.err.println("BrightSide: logging configuration failed: " + e);
		}
	}
}
