package covia.brightside;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import convex.core.util.Shutdown;
import covia.brightside.chat.ChatSession;
import covia.brightside.ui.LAF;
import covia.brightside.ui.MainWindow;
import covia.brightside.ui.TrayManager;

/**
 * BrightSide: a Covia venue on the desktop.
 *
 * <p>Runs an {@link EmbeddedVenue} inside the process and puts a chat window
 * in front of it. The window minimises (and closes) to a system-tray icon so
 * the venue keeps running in the background; Exit — from the tray menu or the
 * File menu — flushes the venue's state and stops the process.
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
	private volatile EmbeddedVenue venue;
	private volatile ChatSession chat;
	private volatile Identity identity;
	private MainWindow window; // event thread only
	private TrayManager tray; // event thread only
	private final AtomicBoolean exiting = new AtomicBoolean();
	private final AtomicBoolean chatStarted = new AtomicBoolean();

	BrightSide(AppConfig config, Path configPath) {
		this.config = config;
		this.configPath = configPath;
	}

	/** Entry point. Optional argument: path to the configuration file. */
	public static void main(String[] args) {
		configureLogging();
		Path path = (args.length > 0) ? Path.of(args[0]).toAbsolutePath().normalize() : AppConfig.DEFAULT_FILE;
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
		boolean welcome = (saved == null);
		String prefill = welcome ? Identity.suggestName() : null;
		try {
			SwingUtilities.invokeAndWait(() -> {
				tray = TrayManager.install(this);
				window = new MainWindow(this, welcome, prefill);
				window.setVisible(true);
			});
		} catch (Exception e) {
			throw new IllegalStateException("Could not create the main window", e);
		}
		// Flush venue state on any JVM exit (Ctrl-C, SIGTERM) ahead of Convex's
		// own store shutdown — the same ordering MainVenue uses.
		Shutdown.addHook(Shutdown.SERVER - 10, this::closeVenue);
		Thread t = new Thread(this::launchVenue, "brightside-venue");
		t.setDaemon(true);
		t.start();
	}

	private void launchVenue() {
		try {
			EmbeddedVenue v = EmbeddedVenue.launch(config.venueConfig());
			venue = v;
			log.info("Venue '{}' ready at {} as {}", v.name(), v.url(), v.did());
			onVenueReady();
		} catch (Throwable t) {
			log.error("Venue failed to start", t);
			SwingUtilities.invokeLater(() -> window.startupFailed(t));
		}
	}

	/**
	 * The venue is up. A returning person (name already known) — or a new one
	 * who typed their name while it booted — starts chatting now; otherwise we
	 * wait for {@link #submitName}.
	 */
	private void onVenueReady() {
		// Install Brightside's default skills into v/ (venue-principal write),
		// before any agent that pins them is created.
		try {
			BrightsideSkills.seed(venue.clientAs(venue.did()));
		} catch (Exception e) {
			log.warn("Could not seed default skills", e);
		}
		Identity id = identity;
		if (id != null) startChatOnce(venue, id);
	}

	/**
	 * The person entered a name on the welcome screen. Saves it, then either
	 * starts chatting (first time) or rebinds the chat to the new name (a
	 * later change). Called on the event thread from {@link MainWindow}.
	 */
	public void submitName(String rawName) {
		Identity id;
		try {
			id = Identity.of(rawName);
		} catch (IllegalArgumentException e) {
			window.welcomeBusy(null); // clear any busy state; the panel shows its own error
			return;
		}
		boolean changing = chatStarted.get();
		identity = id;
		persistIdentity(id);
		EmbeddedVenue v = venue;
		if (changing) {
			startChatBackground(v, id, false);
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

	/** Starts the first chat exactly once, whichever of name/venue arrives last. */
	private void startChatOnce(EmbeddedVenue v, Identity id) {
		if (v == null || id == null) return;
		if (chatStarted.compareAndSet(false, true)) startChatBackground(v, id, true);
	}

	private void startChatBackground(EmbeddedVenue v, Identity id, boolean firstStart) {
		Thread t = new Thread(() -> startChat(v, id, firstStart), "brightside-chat");
		t.setDaemon(true);
		t.start();
	}

	/** Binds a chat session to {@code id}'s principal and readies its agent. */
	private void startChat(EmbeddedVenue v, Identity id, boolean firstStart) {
		String userDID = id.userDID(v.did());
		ChatSession session = new ChatSession(v.clientAs(userDID), config.chat(), id.name());
		chat = session;
		log.info("Chatting as {} ({})", id.label(), userDID);
		SwingUtilities.invokeLater(() -> {
			if (firstStart) window.showChat(v, session, id);
			else window.userChanged(session, id);
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name());
		});
		// Create/refresh the agent now, so the first message is quick and any
		// configuration problem shows up straight away.
		try {
			session.ensureAgent();
		} catch (Exception e) {
			log.warn("Chat agent not ready", e);
			SwingUtilities.invokeLater(() -> window.showSystemMessage(
				"I'm having trouble getting ready: " + e.getMessage()));
		}
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

	/** Window close button: to the tray when there is one, otherwise exit. */
	public void onWindowClosing() {
		if (tray != null) hideToTray();
		else exit();
	}

	/** Minimise: to the tray when there is one, otherwise the usual taskbar minimise. */
	public void onWindowIconified() {
		if (tray != null) hideToTray();
	}

	public void newConversation() {
		ChatSession c = chat;
		if (c == null) return;
		c.reset();
		SwingUtilities.invokeLater(() -> window.showSystemMessage("Started a new chat."));
	}

	/** Opens the venue's own web dashboard (Advanced menu — a demo/diagnostics surface). */
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

	/** Logback from the bundled resource, overriding any logback.xml a dependency jar ships. */
	static void configureLogging() {
		try (InputStream is = BrightSide.class.getResourceAsStream("/brightside/logback.xml")) {
			if (is == null) return;
			LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
			JoranConfigurator configurator = new JoranConfigurator();
			configurator.setContext(ctx);
			ctx.reset();
			configurator.doConfigure(is);
		} catch (Exception e) {
			System.err.println("BrightSide: logging configuration failed: " + e);
		}
	}
}
