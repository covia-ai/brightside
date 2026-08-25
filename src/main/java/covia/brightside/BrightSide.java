package covia.brightside;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
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
	private volatile ConversationWatcher watcher; // event thread
	private volatile Identity identity;
	private volatile covia.grid.Venue client; // in-process client for the acting user
	private volatile String agentId;
	private volatile String viewedSessionId; // the conversation currently on screen
	private volatile List<SessionHistory.Session> sessions = List.of(); // switcher list, newest first
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
		// Default skills are installed by BrightsideAdapter at venue launch.
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

	/** Binds a chat session to {@code id}'s principal and reopens its live conversation. */
	private void startChat(EmbeddedVenue v, Identity id, boolean firstStart) {
		String userDID = id.userDID(v.did());
		covia.grid.Venue userClient = v.clientAs(userDID);
		ChatSession session = new ChatSession(userClient, config.chat(), id.name());
		chat = session;

		// Create/refresh the agent, then read the last conversation from the
		// venue's live session state (the single source of truth) and continue it.
		try {
			session.ensureAgent();
		} catch (Exception e) {
			log.warn("Chat agent not ready", e);
		}
		String aid = config.chat().agentId();
		this.client = userClient;
		this.agentId = aid;

		SessionHistory.Snapshot history = SessionHistory.loadLatest(userClient, aid);
		if (history != null) session.resume(history.sessionId());
		List<SessionHistory.Item> turns = (history != null) ? history.items() : List.of();
		convex.core.data.ACell baseline = (history != null) ? history.agentValue() : null;
		viewedSessionId = (history != null) ? history.sessionId() : null;
		List<SessionHistory.Session> sessionList = SessionHistory.listSessions(userClient, aid);
		sessions = sessionList;
		log.info("Chatting as {} ({}) — reopened {} live message(s) across {} conversation(s)",
			id.label(), userDID, turns.size(), sessionList.size());

		SwingUtilities.invokeLater(() -> {
			if (firstStart) window.showChat(v, session, id, turns);
			else window.userChanged(session, id, turns);
			window.setConversations(sessionList, viewedSessionId);
			if (tray != null) tray.setTooltip(APP_NAME + " — " + id.name());
			// Watch the venue's agent value; on any change, refresh the switcher
			// and re-render the conversation the user is currently viewing.
			ConversationWatcher w = watcher;
			if (w != null) w.stop();
			watcher = new ConversationWatcher(userClient, aid, baseline,
				() -> window.isChatShowing(),
				this::onAgentChanged);
			watcher.start();
		});
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

			@Override
			protected Void doInBackground() {
				list = SessionHistory.sessionsOf(record);
				// A brand-new chat has no session id until its first reply lands;
				// adopt it once the session framework mints it.
				vsid = (viewedSessionId != null) ? viewedSessionId
					: (chat != null ? chat.sessionId() : null);
				snap = (vsid != null) ? SessionHistory.snapshotOf(record, vsid) : null;
				return null;
			}

			@Override
			protected void done() {
				sessions = list;
				if (vsid != null) {
					viewedSessionId = vsid;
					if (snap != null) window.refreshConversation(snap.items());
				}
				window.setConversations(list, viewedSessionId);
			}
		}.execute();
	}

	/** Switch the chat to a past conversation, reopening its transcript and continuing it. */
	public void openSession(String sessionId) {
		covia.grid.Venue c = client;
		ChatSession s = chat;
		String aid = agentId;
		if (c == null || s == null || aid == null || sessionId == null) return;
		if (sessionId.equals(viewedSessionId)) return;
		Thread t = new Thread(() -> {
			SessionHistory.Snapshot snap = SessionHistory.load(c, aid, sessionId);
			if (snap == null) return;
			s.resume(snap.sessionId());
			List<SessionHistory.Session> list = SessionHistory.listSessions(c, aid);
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
			List<SessionHistory.Session> list = SessionHistory.listSessions(c, aid);
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
		Venue c = client;
		String aid = agentId;
		if (c == null || aid == null || sessionId == null) return;
		Thread t = new Thread(() -> {
			SessionHistory.Snapshot snap = SessionHistory.load(c, aid, sessionId);
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
			List<SessionHistory.Session> list = SessionHistory.listSessions(c, aid);
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

	/** Window close button: to the tray when there is one, otherwise exit. */
	public void onWindowClosing() {
		if (tray != null) hideToTray();
		else exit();
	}

	/** Minimise: to the tray when there is one, otherwise the usual taskbar minimise. */
	public void onWindowIconified() {
		if (tray != null) hideToTray();
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
			window.showSystemMessage("Started a new chat.");
			window.setConversations(sessions, null);
		});
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
