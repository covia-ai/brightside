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
import covia.brightside.ui.IdentityDialog;
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

	public static final String APP_NAME = "BrightSide";

	private final AppConfig config;
	private final Path configPath;
	private volatile EmbeddedVenue venue;
	private volatile ChatSession chat;
	private volatile Identity identity;
	private MainWindow window; // event thread only
	private TrayManager tray; // event thread only
	private final AtomicBoolean exiting = new AtomicBoolean();

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

	/** Shows the window, then brings the venue up in the background. */
	void start() {
		try {
			SwingUtilities.invokeAndWait(() -> {
				tray = TrayManager.install(this);
				window = new MainWindow(this);
				window.setStatus("Starting venue on port " + config.port() + "…");
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
			Identity id = resolveIdentity();
			identity = id;
			startChat(v, id, true);
		} catch (Throwable t) {
			log.error("Venue failed to start", t);
			SwingUtilities.invokeLater(() -> window.venueFailed(t));
		}
	}

	/**
	 * The saved identity, or the name the user chooses at the first-launch
	 * screen (falling back to a suggestion if they dismiss it). Runs on the
	 * venue thread; the picker is shown on the event thread.
	 */
	private Identity resolveIdentity() {
		Identity saved = Identity.load(config.home());
		if (saved != null) {
			log.info("Using saved identity {}", saved.label());
			return saved;
		}
		Identity chosen = promptForName("Welcome to " + APP_NAME,
			"Choose a name for yourself on this venue:", Identity.suggestName());
		if (chosen == null) chosen = Identity.of(Identity.suggestName());
		persistIdentity(chosen);
		return chosen;
	}

	/** Shows the name picker on the event thread and blocks for the answer. */
	private Identity promptForName(String title, String prompt, String initial) {
		Identity[] out = new Identity[1];
		try {
			SwingUtilities.invokeAndWait(() -> out[0] = IdentityDialog.ask(window, title, prompt, initial));
		} catch (Exception e) {
			log.warn("Name picker failed: {}", e.toString());
		}
		return out[0];
	}

	private void persistIdentity(Identity id) {
		try {
			id.save(config.home());
			log.info("Saved identity {} to {}", id.label(), config.home().resolve(Identity.FILE_NAME));
		} catch (IOException e) {
			log.warn("Could not save identity", e);
		}
	}

	/** Binds a chat session to {@code id}'s principal and readies its agent. */
	private void startChat(EmbeddedVenue v, Identity id, boolean firstStart) {
		String userDID = id.userDID(v.did());
		ChatSession session = new ChatSession(v.clientAs(userDID), config.chat(), id.label());
		chat = session;
		log.info("Chatting as {} ({})", id.label(), userDID);
		SwingUtilities.invokeLater(() -> {
			if (firstStart) window.venueReady(v, session, id);
			else window.userChanged(session, id);
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.label() + "\n" + v.name() + " on port " + v.port());
		});
		// Create/refresh the agent now, so the first message is quick and any
		// configuration problem shows up straight away.
		try {
			session.ensureAgent();
		} catch (Exception e) {
			log.warn("Chat agent not ready", e);
			SwingUtilities.invokeLater(() -> window.showSystemMessage("Chat agent not ready: " + e.getMessage()));
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
		SwingUtilities.invokeLater(() -> window.showSystemMessage("New conversation started."));
	}

	/**
	 * Asks for a name and, if it changed, rebinds the chat to that user. The
	 * picker is modal on the event thread (this is called from a menu action);
	 * the rebind, which touches the venue, runs on a background thread.
	 */
	public void switchUser() {
		EmbeddedVenue v = venue;
		if (v == null) return;
		Identity current = identity;
		Identity chosen = IdentityDialog.ask(window, "Switch user",
			"Choose a name for yourself on this venue:",
			(current != null) ? current.name() : Identity.suggestName());
		if (chosen == null || chosen.equals(current)) return;
		identity = chosen;
		persistIdentity(chosen);
		window.showSystemMessage("Switching to " + chosen.label() + "…");
		Thread t = new Thread(() -> startChat(v, chosen, false), "brightside-switch-user");
		t.setDaemon(true);
		t.start();
	}

	public void openVenueInBrowser() {
		EmbeddedVenue v = venue;
		if (v == null) return;
		String url = v.url();
		desktop("open " + url, d -> d.browse(URI.create(url)));
	}

	public void openConfigFile() {
		desktop("open " + configPath, d -> d.open(configPath.toFile()));
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
