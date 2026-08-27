package covia.brightside;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import convex.core.util.Shutdown;
import covia.brightside.chat.ChatSession;
import covia.brightside.model.AgentRef;
import covia.brightside.model.Providers;
import covia.brightside.ui.LAF;
import covia.brightside.ui.MainWindow;
import covia.brightside.ui.TrayManager;
import covia.brightside.ui.onboarding.OnboardingWizard;
import covia.brightside.vault.Vault;

/**
 * BrightSide: a Covia venue on the desktop.
 *
 * <p>Runs an {@link EmbeddedVenue} inside the process and puts a chat window
 * in front of it. The window minimises (and closes) to a system-tray icon so
 * the venue keeps running in the background; Exit — from the tray menu or
 * Settings → General — flushes the venue's state and stops the process.
 *
 * <p>Threading: the UI lives on the Swing event thread; the venue is launched
 * and closed, and the desktop (browser, editor) is opened, on background
 * threads. All public action methods may be called from any thread.
 */
public final class BrightSide {

	private static final Logger log = LoggerFactory.getLogger(BrightSide.class);

	public static final String APP_NAME = "Brightside";

	private final AppConfig config;
	private final Path configPath;
	private final Prefs prefs;
	private volatile EmbeddedVenue venue;
	private volatile ChatSession chat;
	private volatile ConversationWatcher watcher; // event thread
	private volatile Identity identity;
	private volatile covia.grid.Venue client; // in-process client for the acting user
	private volatile String userDID; // the acting user's DID (for in-process lattice reads)
	private volatile String agentId;
	private volatile String viewedSessionId; // the conversation currently on screen
	private volatile List<SessionHistory.Session> sessions = List.of(); // switcher list, newest first
	private MainWindow window; // event thread only
	private TrayManager tray; // event thread only
	private final AtomicBoolean exiting = new AtomicBoolean();
	private final AtomicBoolean chatStarted = new AtomicBoolean();
	private volatile Vault vault; // set once onboarded/unlocked
	private volatile String venueSeedHex; // the venue's Ed25519 seed hex (authorises operator takeover)
	private volatile String llmOverride; // chosen model op for the first onboarding launch
	/** API-key secret names actually provisioned into the running venue at launch. */
	private final java.util.Set<String> provisionedSecrets = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private enum Mode {
		ONBOARD, UNLOCK
	}

	/** How a chat (re)bind should be presented: first launch, a name change, or an agent switch. */
	private enum Bind {
		FIRST, NAME_CHANGE, AGENT_SWITCH
	}

	BrightSide(AppConfig config, Path configPath) {
		this.config = config;
		this.configPath = configPath;
		this.prefs = Prefs.load(config.home());
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
			LAF.init(AppConfig.DEFAULT_THEME);
			JOptionPane.showMessageDialog(null,
				"Could not load the configuration file\n" + path + "\n\n" + e.getMessage(),
				APP_NAME, JOptionPane.ERROR_MESSAGE);
			System.exit(66); // EX_NOINPUT, as the Covia venue does
			return;
		}
		LAF.init(config.theme());
		new BrightSide(config, path).start();
	}

	/**
	 * Shows the window — the welcome screen for a new person, or the chat
	 * (starting up) for a returning one — then brings the venue up in the
	 * background. The person can be typing their name while it starts.
	 */
	void start() {
		Identity saved = Identity.load(config.home());
		identity = saved;
		Mode mode = decideMode();
		try {
			SwingUtilities.invokeAndWait(() -> {
				tray = TrayManager.install(this);
				window = new MainWindow(this);
				switch (mode) {
					case ONBOARD -> window.showOnboarding();
					case UNLOCK -> window.showUnlock(RememberedPassphrase.load(config.home()));
				}
				window.setVisible(true);
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

	private void launchVenueWith(AMap<AString, ACell> venueConfig) {
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
					SwingUtilities.invokeLater(() -> window.startupFailed(
						"Brightside is already running on this computer and couldn't be taken over"
						+ " — please quit it (tray icon ▸ Quit) and try again."));
					return;
				}
				if (!proceed) {
					log.info("Another Brightside instance is running and takeover was cancelled; exiting");
					System.exit(0);
					return;
				}
			}
			EmbeddedVenue v = EmbeddedVenue.launch(venueConfig, this::exit);
			venue = v;
			log.info("Venue '{}' ready at {} as {}", v.name(), v.url(), v.did());
			onVenueReady();
		} catch (Throwable t) {
			log.error("Venue failed to start", t);
			closeVenue();
			venue = null;
			SwingUtilities.invokeLater(() -> window.startupFailed(t));
		}
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

				// Encrypted store + this exact identity, plus the API key provisioned
				// into the (encrypted) secret store — none of it written in the clear.
				if (setup.apiKey() != null && setup.providerId() != null) {
					String secretName = Providers.byId(setup.providerId()).secretName();
					if (secretName != null) v.storeApiKey(secretName, setup.apiKey());
				}
				AMap<AString, ACell> venueConfig = provisionKeys(v.secure(config.venueConfig(), setup.seedHex()), v);
				llmOverride = (setup.providerId() == null)
					? AppConfig.ECHO_LLM_OPERATION
					: Providers.modelOp(setup.providerId(), setup.modelId());
				config.persistModel(llmOverride);

				launchVenueWith(venueConfig);
			} catch (Exception e) {
				log.error("Onboarding failed", e);
				SwingUtilities.invokeLater(() -> window.startupFailed(e));
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
					SwingUtilities.invokeLater(window::showChatStartup);
					startChat(running, saved, effectiveChat(), Bind.FIRST);
					return;
				}
				AMap<AString, ACell> venueConfig = provisionKeys(v.secure(config.venueConfig(), seedHex), v);
				SwingUtilities.invokeLater(window::showChatStartup);
				launchVenueWith(venueConfig);
			} catch (IOException e) {
				SwingUtilities.invokeLater(() -> window.unlockError("That passphrase didn't work."));
			} catch (Exception e) {
				log.error("Unlock failed", e);
				SwingUtilities.invokeLater(() -> window.unlockError("Couldn't unlock — see the logs."));
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
		watcher = null;
		chat = null;
		client = null;
		userDID = null;
		agentId = null;
		viewedSessionId = null;
		sessions = List.of();
		identity = null;
		vault = null;
		chatStarted.set(false);
		SwingUtilities.invokeLater(() -> {
			if (oldWatcher != null) oldWatcher.stop();
			window.userLoggedOut();
			window.showUnlock(null);
		});
	}

	/** Opens recovery (Forgot passphrase?): restore identity from the recovery phrase. */
	public void openRecovery() {
		SwingUtilities.invokeLater(() -> window.openRecoveryDialog(this::recover));
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
					SwingUtilities.invokeLater(window::showChatStartup);
					startChat(running, saved, effectiveChat(), Bind.FIRST);
					return;
				}
				AMap<AString, ACell> venueConfig = provisionKeys(v.secure(config.venueConfig(), seedHex), v);
				SwingUtilities.invokeLater(window::showChatStartup);
				launchVenueWith(venueConfig);
			} catch (Exception e) {
				log.error("Recovery failed", e);
				SwingUtilities.invokeLater(() -> window.unlockError("Recovery failed — see the logs."));
			} finally {
				java.util.Arrays.fill(passphrase, '\0');
			}
		}, "brightside-recover");
		t.setDaemon(true);
		t.start();
	}

	/** Provisions every stored (encrypted) API key into the in-memory venue config's public secrets. */
	private AMap<AString, ACell> provisionKeys(AMap<AString, ACell> venueConfig, Vault vault) throws IOException {
		for (Map.Entry<String, String> e : vault.apiKeys().entrySet()) {
			venueConfig = withPublicSecret(venueConfig, e.getKey(), e.getValue());
			provisionedSecrets.add(e.getKey());
		}
		return venueConfig;
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
		boolean keyLive = p == null || !p.needsApiKey() || provisionedSecrets.contains(p.secretName());
		if (!keyLive) {
			// Saved, but this provider's API key is only injected into the venue's
			// secret store at launch — switching the running agent to it now would
			// fail at the model call with no key. Defer to the next start rather
			// than leave the agent in a broken state.
			String label = (p != null) ? p.label() : providerId;
			SwingUtilities.invokeLater(() -> window.showSystemMessage(
				"Saved. Restart Brightside to switch to " + label + " — its API key is applied at launch."));
			return;
		}
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
	 * Stores a provider API key (encrypted in the vault). Applied on the next
	 * launch — the venue provisions secrets at start. Returns false if it couldn't.
	 */
	public boolean storeApiKey(String providerId, String apiKey) {
		Vault v = vault;
		Providers.Provider p = Providers.byId(providerId);
		if (v == null || p == null || p.secretName() == null || apiKey == null || apiKey.isBlank()) return false;
		try {
			v.storeApiKey(p.secretName(), apiKey);
			return true;
		} catch (Exception e) {
			log.warn("Could not store the API key", e);
			return false;
		}
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

	/** Injects a public secret (an API key) into an in-memory venue config, merged. */
	private static AMap<AString, ACell> withPublicSecret(AMap<AString, ACell> venueConfig, String name, String value) {
		AString secretsKey = Strings.create("secrets");
		AString publicKey = Strings.create("public");
		AMap<AString, ACell> secrets = asMap(venueConfig.get(secretsKey));
		AMap<AString, ACell> pub = asMap((secrets != null) ? secrets.get(publicKey) : null);
		if (pub == null) pub = Maps.empty();
		pub = pub.assoc(Strings.create(name), Strings.create(value));
		if (secrets == null) secrets = Maps.empty();
		secrets = secrets.assoc(publicKey, pub);
		return venueConfig.assoc(secretsKey, secrets);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> asMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<AString, ACell>) m : null;
	}

	/** The chat config with the chosen model applied (onboarding's, else the saved one). */
	private AppConfig.Chat effectiveChat() {
		AppConfig.Chat c = config.chat();
		if (llmOverride == null || llmOverride.equals(c.llmOperation())) return c;
		return new AppConfig.Chat(c.agentId(), c.operation(), llmOverride, c.systemPrompt(), c.timeoutSeconds());
	}

	/**
	 * Prompts (Take Over / Cancel); on Take Over, asks the running instance to
	 * shut down cleanly and waits for it to release the store. Returns true to
	 * proceed with launch, false to cancel this instance.
	 */
	private boolean takeOver(int port) throws Exception {
		if (!confirmTakeover()) return false;
		log.info("Taking over from a running Brightside instance on port {}", port);
		Takeover.requestShutdown(port, Takeover.venueDID(port), venueSeedHex);
		if (!Takeover.waitUntilDown(port, 20_000)) {
			throw new IllegalStateException("the previous instance did not shut down in time");
		}
		return true;
	}

	private boolean confirmTakeover() {
		boolean[] takeOver = { false };
		try {
			SwingUtilities.invokeAndWait(() -> {
				Object[] options = { "Take Over", "Cancel" };
				int choice = JOptionPane.showOptionDialog(window,
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
		// Default skills are installed by BrightsideAdapter at venue launch.
		Identity id = identity;
		if (id != null) startChatOnce(venue, bindIdentityToVenue(id, venue));
	}

	/**
	 * The person entered a name on the welcome screen. Saves it, then either
	 * starts chatting (first time) or rebinds the chat to the new name (a
	 * later change). Called on the event thread from {@link MainWindow}.
	 */
	public void submitName(String rawName) {
		Identity current = identity;
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
		boolean configureExisting = chatConfig.agentId().equals(config.chat().agentId());
		startChatBackground(v, id, chatConfig, bind, configureExisting);
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
		String did = id.userDID(v.did());
		covia.grid.Venue userClient = v.clientAs(did);
		ChatSession session = new ChatSession(userClient, chatConfig, id.name(), configureExisting);
		chat = session;

		// Create/refresh the agent (agent:create if it's a brand-new one). This does
		// not create an agent session: Home leaves ChatSession.sessionId null until
		// the user actually sends their first message.
		try {
			session.ensureAgent();
		} catch (Exception e) {
			log.warn("Chat agent not ready", e);
		}
		// Import any skills the user has dropped into ~/.brightside/skills/
		// (agentskills.io SKILL.md folders) on the first bind. Check the stored
		// value in-process so an unchanged skill costs no write job.
		if (bind == Bind.FIRST) {
			try {
				covia.brightside.skills.FilesystemSkills.sync(userClient, config.skillsDir(),
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
		// Home is deliberately a clean new chat. Other rebinds (agent switch/name
		// change) retain their existing latest-session behaviour.
		SessionHistory.Snapshot history = (bind == Bind.FIRST)
			? null : SessionHistory.snapshotOf(record, null);
		if (history != null) session.resume(history.sessionId());
		List<SessionHistory.Item> turns = (history != null) ? history.items() : List.of();
		viewedSessionId = (history != null) ? history.sessionId() : null;
		List<SessionHistory.Session> sessionList = (record != null) ? SessionHistory.sessionsOf(record) : List.of();
		sessions = sessionList;
		List<covia.brightside.model.AgentRef> agentRefs = listAgents(aid);
		log.info("Chatting as {} with agent '{}' — showing {} live message(s) across {} saved conversation(s)",
			id.label(), aid, turns.size(), sessionList.size());

		SwingUtilities.invokeLater(() -> {
			switch (bind) {
				case FIRST -> window.showChat(v, session, id, turns);
				case NAME_CHANGE -> window.userChanged(session, id, turns);
				case AGENT_SWITCH -> window.showAgentChat(session, turns, displayNameFor(aid));
			}
			window.setAgents(agentRefs, aid);
			window.setConversations(sessionList, viewedSessionId);
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name());
			// Watch this agent's value; on any change, refresh the switcher and
			// re-render the conversation on screen. An in-process compare — no job.
			ConversationWatcher w = watcher;
			if (w != null) w.stop();
			watcher = new ConversationWatcher(() -> v.agentRecord(did, aid), record,
				() -> window.isChatShowing(),
				this::onAgentChanged);
			watcher.start();
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
		String prompt = covia.brightside.model.AgentTemplate.GENERAL.systemPrompt(name);
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
			? covia.brightside.model.AgentTemplate.GENERAL.systemPrompt(displayNameFor(aid)) : systemPrompt;
		AppConfig.Chat chosen = new AppConfig.Chat(aid, base.operation(), chosenModel, chosenPrompt, base.timeoutSeconds());
		// Applying the supplied configuration is deliberate here. Later switches to
		// the agent preserve what its record already holds.
		startChatBackground(v, id, chosen, Bind.AGENT_SWITCH, true);
	}

	/** The chat config for {@code aid}: the default persona for the default agent, else a named one. */
	private AppConfig.Chat chatConfigFor(String aid) {
		AppConfig.Chat base = effectiveChat();
		if (aid.equals(config.chat().agentId())) {
			return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), base.systemPrompt(), base.timeoutSeconds());
		}
		String persona = "You are " + displayNameFor(aid) + ", a private personal AI assistant running on the user's "
			+ "own computer. Be genuinely helpful, warm, and concise.";
		return new AppConfig.Chat(aid, base.operation(), base.llmOperation(), persona, base.timeoutSeconds());
	}

	private AppConfig.Chat currentChatConfig() {
		return chatConfigFor((agentId != null) ? agentId : config.chat().agentId());
	}

	/** Lists the user's agents (via agent:list), always including the default and current. */
	private List<AgentRef> listAgents(String currentAid) {
		java.util.LinkedHashMap<String, AgentRef> map = new java.util.LinkedHashMap<>();
		String def = config.chat().agentId();
		map.put(def, new AgentRef(def, displayNameFor(def)));
		if (currentAid != null) {
			map.putIfAbsent(currentAid, new AgentRef(currentAid, displayNameFor(currentAid)));
		}
		try {
			convex.core.data.ACell res = invokeOpResult(client, "v/ops/agent/list", Maps.empty());
			convex.core.data.ACell agents = convex.core.lang.RT.getIn(res, "agents");
			if (agents instanceof convex.core.data.AVector<?> vec) {
				for (long i = 0; i < vec.count(); i++) {
					AString aidS = convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(vec.get(i), "agentId"));
					if (aidS != null) {
						String aid = aidS.toString();
						map.putIfAbsent(aid, new AgentRef(aid, displayNameFor(aid)));
					}
				}
			}
		} catch (Exception e) {
			log.warn("Could not list agents: {}", e.getMessage());
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

	/** A human display name from an agent id: {@code "bob"} → "Bob", {@code "bob-smith"} → "Bob Smith". */
	private static String displayNameFor(String agentId) {
		if (agentId == null || agentId.isBlank()) return "Agent";
		StringBuilder sb = new StringBuilder();
		for (String part : agentId.split("[-_]")) {
			if (part.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return (sb.length() > 0) ? sb.toString() : agentId;
	}

	/** An agent id (path segment) from a display name: lowercase, hyphenated. */
	private static String slug(String name) {
		if (name == null) return "";
		return name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	private static convex.core.data.ACell invokeOpResult(Venue client, String operation, AMap<AString, ACell> input)
			throws Exception {
		Job job = client.invoke(operation, input).get(30, TimeUnit.SECONDS);
		return job.future().get(30, TimeUnit.SECONDS);
	}

	/**
	 * The agent record changed (a new turn here or an out-of-band update):
	 * refresh the switcher list and re-render the conversation currently on
	 * screen — the one the user picked, not necessarily the newest. Called on
	 * the event thread; the cell projection runs off it.
	 */
	private void onAgentChanged(convex.core.data.ACell record) {
		new SwingWorker<Void, Void>() {
			private List<SessionHistory.Session> list;
			private SessionHistory.Snapshot snap;
			private String vsid;
			private boolean sessionActive;

			@Override
			protected Void doInBackground() {
				list = SessionHistory.sessionsOf(record);
				// The send-completion reconciliation normally pins a newly minted id;
				// this is also a safe fallback for changes made by another client.
				vsid = (viewedSessionId != null) ? viewedSessionId
					: (chat != null ? chat.sessionId() : null);
				snap = (vsid != null) ? SessionHistory.snapshotOf(record, vsid) : null;
				sessionActive = SessionHistory.isSessionActive(record, vsid);
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
			}
		}.execute();
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
			// turns are just a lattice read, done in-process.
			AgentContext.Report report = AgentContext.load(c, aid, sessionId);
			List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(v.agentRecord(did, aid), sessionId);
			SwingUtilities.invokeLater(() -> {
				if (report == null) window.showSystemMessage("Sorry — I couldn't read the context for that conversation.");
				else window.showContextInfo(report, turns, title);
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

	public void showWindow() {
		SwingUtilities.invokeLater(() -> window.showAndFocus());
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

	/** Whether minimising sends the window to the tray (default true). */
	public boolean minimiseToTray() {
		return prefs.getBool("tray.minimise", true);
	}

	public void setMinimiseToTray(boolean value) {
		prefs.setBool("tray.minimise", value);
	}

	/** Force an immediate lattice value compare and refresh the transcript if it changed. */
	public void refreshNow() {
		ConversationWatcher w = watcher;
		if (w != null) w.checkNow();
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
			if (window != null) window.setVisible(false);
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
