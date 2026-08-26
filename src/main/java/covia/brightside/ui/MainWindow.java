package covia.brightside.ui;

import java.util.List;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import covia.brightside.BrightSide;
import covia.brightside.SessionHistory;
import covia.brightside.EmbeddedVenue;
import covia.brightside.Identity;
import covia.brightside.chat.ChatSession;
import covia.brightside.ui.chat.ChatPanel;
import covia.brightside.ui.chat.ConversationList;
import covia.brightside.ui.settings.AuthPanel;
import covia.brightside.ui.settings.ModelPanel;
import covia.brightside.ui.settings.ProfilePanel;
import covia.brightside.ui.settings.SettingsScreen;
import covia.brightside.ui.settings.VaultPanel;

/**
 * The BrightSide window. Two full-window screens on a {@link CardLayout}: the
 * friendly {@link WelcomePanel} ("What should I call you?") and the chat. All
 * technical detail (the local address, the identity behind {@code u:<name>})
 * lives in the About box and the Advanced menu, never in the main flow.
 */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {

	private static final String CARD_ONBOARD = "onboarding";
	private static final String CARD_UNLOCK = "unlock";
	private static final String CARD_WELCOME = "welcome";
	private static final String CARD_CHAT = "chat";

	private static final int SIDEBAR_WIDTH = 400; // agents pane + sessions list
	private static final String MAIN_CHAT = "chat-main";
	private static final String MAIN_SETTINGS = "settings";

	private final BrightSide app;
	private final CardLayout cards = new CardLayout();
	private final JPanel deck = new JPanel(cards);

	// The bottom-nav content area: the chat (with an optional sessions bar) or settings.
	private final CardLayout mainCards = new CardLayout();
	private JSplitPane split;
	private JPanel mainDeck;
	private JPanel sessionsPane; // agents + sessions, the split's left side
	private NavBar navBar;
	private SettingsScreen settingsScreen;
	private covia.brightside.ui.chat.AgentList agentList;
	private int sidebarWidth = SIDEBAR_WIDTH;
	private final covia.brightside.ui.onboarding.OnboardingWizard onboarding;
	private final covia.brightside.ui.onboarding.UnlockPanel unlock;
	private final WelcomePanel welcomePanel;
	private final ChatPanel chatPanel = new ChatPanel();
	private final ConversationList conversations;
	private final JLabel whoLabel = new JLabel(" ");
	private final JLabel brandLabel = new JLabel("Powered by the Covia Grid");

	private JMenuItem changeNameItem;
	private JMenuItem logoutItem;
	private JMenu userMenu;
	private JMenuItem dashboardItem;

	private EmbeddedVenue venue; // set once ready
	private Identity identity;
	private String currentCard;

	public MainWindow(BrightSide app) {
		super(BrightSide.APP_NAME);
		this.app = app;
		setIconImages(Icons.appIcons());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setJMenuBar(buildMenuBar());

		onboarding = new covia.brightside.ui.onboarding.OnboardingWizard(app::onOnboardingComplete);
		unlock = new covia.brightside.ui.onboarding.UnlockPanel(new covia.brightside.ui.onboarding.UnlockPanel.Listener() {
			@Override
			public void onUnlock(char[] passphrase, boolean remember) {
				app.onUnlock(passphrase, remember);
			}

			@Override
			public void onForgot() {
				app.openRecovery();
			}
		});

		welcomePanel = new WelcomePanel(new WelcomePanel.Listener() {
			@Override
			public void onNameEntered(String name) {
				app.submitName(name);
			}

			@Override
			public void onCancel() {
				app.cancelNameChange();
			}
		});

		conversations = new ConversationList(new ConversationList.Listener() {
			@Override
			public void onNewConversation() {
				app.newConversation();
			}

			@Override
			public void onSelectSession(String sessionId) {
				app.openSession(sessionId);
			}

			@Override
			public void onRenameSession(String sessionId, String newTitle) {
				app.renameSession(sessionId, newTitle);
			}

			@Override
			public void onCopyTranscript(String sessionId) {
				app.copyTranscript(sessionId);
			}

			@Override
			public void onInspectSession(String sessionId) {
				app.showSessionInfo(sessionId);
			}

			@Override
			public void onDeleteSession(String sessionId) {
				app.deleteSession(sessionId);
			}
		});

		// Agents pane (left) beside the sessions list — together the split's left
		// side. Each agent has its own sessions; selecting one switches the chat.
		agentList = new covia.brightside.ui.chat.AgentList(new covia.brightside.ui.chat.AgentList.Listener() {
			@Override
			public void onSelectAgent(String agentId) {
				app.switchAgent(agentId);
			}

			@Override
			public void onNewAgent() {
				String name = javax.swing.JOptionPane.showInputDialog(MainWindow.this,
					"Name your new agent:", "New agent", javax.swing.JOptionPane.PLAIN_MESSAGE);
				if (name != null && !name.isBlank()) app.createAgent(name.trim());
			}
		});
		sessionsPane = new JPanel(new BorderLayout());
		sessionsPane.add(agentList, BorderLayout.WEST);
		sessionsPane.add(conversations, BorderLayout.CENTER);

		// A split so the sessions area can be resized (Sessions tab) or collapsed
		// (Home tab). The bottom nav switches Home/Sessions/Settings.
		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPane, chatPanel);
		split.setBorder(null);
		split.setContinuousLayout(true);
		split.setResizeWeight(0.0); // extra width goes to the chat, not the sidebar
		split.setDividerLocation(SIDEBAR_WIDTH);

		ModelPanel modelPanel = new ModelPanel(new ModelPanel.Handler() {
			@Override
			public void applyModel(String providerId, String modelId) {
				app.applyModel(providerId, modelId);
			}

			@Override
			public boolean storeApiKey(String providerId, String apiKey) {
				return app.storeApiKey(providerId, apiKey);
			}
		}, app.currentModelOp());
		ProfilePanel profilePanel = new ProfilePanel(new ProfilePanel.Host() {
			@Override
			public void saveName(String name) {
				app.submitName(name);
			}

			@Override
			public String revealPrivateKey(char[] passphrase) throws Exception {
				return app.revealPrivateSeed(passphrase);
			}
		});
		VaultPanel vaultPanel = new VaultPanel(app::forgetRememberedPassphrase);
		AuthPanel authPanel = new AuthPanel(app::mintAccessToken);
		settingsScreen = new SettingsScreen(modelPanel, profilePanel, vaultPanel, authPanel);

		mainDeck = new JPanel(mainCards);
		mainDeck.add(split, MAIN_CHAT);
		mainDeck.add(settingsScreen, MAIN_SETTINGS);

		navBar = new NavBar(this::selectTab);

		JPanel chatCard = new JPanel(new BorderLayout());
		chatCard.add(mainDeck, BorderLayout.CENTER);
		chatCard.add(navBar, BorderLayout.SOUTH);

		deck.add(onboarding, CARD_ONBOARD);
		deck.add(unlock, CARD_UNLOCK);
		deck.add(welcomePanel, CARD_WELCOME);
		deck.add(chatCard, CARD_CHAT);
		add(deck, BorderLayout.CENTER);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				app.onWindowClosing();
			}

			@Override
			public void windowIconified(WindowEvent e) {
				app.onWindowIconified();
			}
		});

		setSize(1040, 760);
		setMinimumSize(new Dimension(720, 520));
		setLocationRelativeTo(null);
		// The first screen is chosen by BrightSide.start() (onboarding / unlock /
		// name / chat), via the show* methods below.
	}

	/** First-run: the onboarding wizard. */
	public void showOnboarding() {
		show(CARD_ONBOARD);
	}

	/** Returning with a vault: the unlock screen. */
	public void showUnlock(char[] prefill) {
		unlock.reset();
		if (prefill != null) {
			try {
				unlock.prefill(prefill);
			} finally {
				java.util.Arrays.fill(prefill, '\0');
			}
		}
		show(CARD_UNLOCK);
		java.awt.EventQueue.invokeLater(unlock::focusField);
	}

	/** Open the (modal) recovery dialog, wired to recover via the app. */
	public void openRecoveryDialog(covia.brightside.ui.onboarding.RecoveryDialog.Listener listener) {
		new covia.brightside.ui.onboarding.RecoveryDialog(this, listener).setVisible(true);
	}

	/** A wrong passphrase on the unlock screen. */
	public void unlockError(String message) {
		unlock.showError(message);
	}

	/** Returning after unlock: the chat is starting up. */
	public void showChatStartup() {
		chatPanel.appendSystem("Just a moment while everything starts up…");
		show(CARD_CHAT);
	}

	// ------------------------------------------------------------------
	// Menus
	// ------------------------------------------------------------------

	private JMenuBar buildMenuBar() {
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		JMenuBar bar = new JMenuBar();

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(item("New chat", KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), e -> app.newConversation()));
		file.add(item("Refresh", KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcut), e -> app.refreshNow()));
		file.addSeparator();
		if (app.hasTray()) {
			file.add(item("Hide to tray", KeyStroke.getKeyStroke(KeyEvent.VK_H, shortcut), e -> app.hideToTray()));
		}
		file.add(item("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcut), e -> app.exit()));
		bar.add(file);

		// Settings and power-user surfaces, out of the everyday flow.
		JMenu settings = new JMenu("Settings");
		settings.setMnemonic(KeyEvent.VK_S);
		settings.add(item("Model & API key…", null, e -> app.openSettings()));
		settings.add(item("Access token…", null, e -> app.openAccessToken()));
		settings.addSeparator();
		if (app.hasTray()) {
			JMenu trayMenu = new JMenu("System tray");
			JCheckBoxMenuItem keepOpen = new JCheckBoxMenuItem("Keep running in tray when closed", app.keepInTray());
			keepOpen.addActionListener(e -> app.setKeepInTray(keepOpen.isSelected()));
			JCheckBoxMenuItem minimise = new JCheckBoxMenuItem("Minimise to tray", app.minimiseToTray());
			minimise.addActionListener(e -> app.setMinimiseToTray(minimise.isSelected()));
			trayMenu.add(keepOpen);
			trayMenu.add(minimise);
			settings.add(trayMenu);
			settings.addSeparator();
		}
		dashboardItem = item("Open dashboard in browser", null, e -> app.openDashboard());
		dashboardItem.setEnabled(false);
		settings.add(dashboardItem);
		settings.add(item("Open settings file", null, e -> app.openConfigFile()));
		settings.add(item("Open logs folder", null, e -> app.openLogsFolder()));
		bar.add(settings);

		// The user / account menu (its title becomes the user's name once known).
		userMenu = new JMenu("Account");
		changeNameItem = item("Change my name…", null, e -> app.changeName());
		changeNameItem.setEnabled(false);
		logoutItem = item("Log out", null, e -> app.logout());
		logoutItem.setEnabled(false);
		userMenu.add(item("Profile…", null, e -> app.openProfile()));
		userMenu.add(changeNameItem);
		userMenu.addSeparator();
		userMenu.add(logoutItem);
		bar.add(userMenu);

		JMenu help = new JMenu("Help");
		help.setMnemonic(KeyEvent.VK_H);
		help.add(item("About " + BrightSide.APP_NAME, null, e -> showAbout()));
		bar.add(help);
		return bar;
	}

	private static JMenuItem item(String text, KeyStroke accelerator, ActionListener action) {
		JMenuItem item = new JMenuItem(text);
		if (accelerator != null) item.setAccelerator(accelerator);
		item.addActionListener(action);
		return item;
	}

	private JComponent buildStatusBar() {
		Color line = UIManager.getColor("Separator.foreground");
		Color muted = UIManager.getColor("Label.disabledForeground");
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, (line != null) ? line : Color.GRAY),
			BorderFactory.createEmptyBorder(5, 12, 5, 12)));
		if (muted != null) brandLabel.setForeground(muted);
		brandLabel.putClientProperty("FlatLaf.styleClass", "small");
		whoLabel.putClientProperty("FlatLaf.styleClass", "small");
		bar.add(whoLabel, BorderLayout.WEST);
		bar.add(brandLabel, BorderLayout.EAST);
		return bar;
	}

	// ------------------------------------------------------------------
	// State transitions (event thread)
	// ------------------------------------------------------------------

	/** Everything is ready: show the chat, reopening the last conversation. */
	public void showChat(EmbeddedVenue venue, ChatSession session, Identity identity, List<SessionHistory.Item> history) {
		this.venue = venue;
		this.identity = identity;
		dashboardItem.setEnabled(true);
		changeNameItem.setEnabled(true);
		logoutItem.setEnabled(true);
		updateWho();
		chatPanel.restore(history);
		chatPanel.setSession(session);
		if (history.isEmpty()) {
			chatPanel.appendSystem("Hi " + identity.name()
				+ " — I'm Brightside, ready whenever you are. Ask me anything.");
		} else {
			chatPanel.appendSystem("Welcome back, " + identity.name() + ".");
		}
		selectTab(NavBar.Tab.SESSIONS); // the sessions bar is the default main screen
		show(CARD_CHAT);
	}

	/** Switched to another agent: rebind the chat to its conversation (no name-change note). */
	public void showAgentChat(ChatSession session, List<SessionHistory.Item> history, String agentName) {
		chatPanel.restore(history);
		chatPanel.setSession(session);
		chatPanel.appendSystem(history.isEmpty() ? "New conversation with " + agentName + "."
			: "Now chatting with " + agentName + ".");
		show(CARD_CHAT);
	}

	/** The name changed: rebind the chat to that user and their conversation. */
	public void userChanged(ChatSession session, Identity identity, List<SessionHistory.Item> history) {
		this.identity = identity;
		updateWho();
		chatPanel.restore(history);
		chatPanel.setSession(session);
		chatPanel.appendSystem("Okay — I'll call you " + identity.name() + " from now on.");
		show(CARD_CHAT);
	}

	/** Clear the transcript for a new chat. */
	public void clearChat() {
		chatPanel.clearMessages();
	}

	/** Clears every current-user UI binding while the venue keeps running. */
	public void userLoggedOut() {
		identity = null;
		chatPanel.clearSession();
		conversations.setSessions(List.of(), null);
		agentList.setAgents(List.of(), null);
		settingsScreen.profile().clearSensitive();
		settingsScreen.auth().clearSensitive();
		dashboardItem.setEnabled(false);
		changeNameItem.setEnabled(false);
		logoutItem.setEnabled(false);
		userMenu.setText("Account");
		whoLabel.setText(" ");
	}

	/** Update the transcript to the venue's live conversation (only re-renders if changed). */
	public void refreshConversation(List<SessionHistory.Item> turns) {
		chatPanel.refreshTo(turns);
	}

	/** Replace the switcher's list of past conversations, highlighting the open one. */
	public void setConversations(List<SessionHistory.Session> sessions, String selectedId) {
		conversations.setSessions(sessions, selectedId);
	}

	/** Replace the agents pane's list, highlighting the current agent. */
	public void setAgents(List<covia.brightside.model.AgentRef> agents, String selectedId) {
		agentList.setAgents(agents, selectedId);
	}

	/** Show a chosen past conversation's transcript (a definite switch, not a poll). */
	public void showConversation(List<SessionHistory.Item> turns) {
		chatPanel.restore(turns);
	}

	/** Switch the bottom-nav content between the chat screens and settings. */
	private void selectTab(NavBar.Tab tab) {
		switch (tab) {
			case HOME -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(false);
			}
			case SESSIONS -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(true);
			}
			case SETTINGS -> {
				refreshSettings();
				mainCards.show(mainDeck, MAIN_SETTINGS);
			}
		}
		navBar.setActive(tab);
	}

	/** Show or hide (collapse) the sessions bar within the chat view. */
	private void setSidebar(boolean visible) {
		if (visible) {
			sessionsPane.setVisible(true);
			split.setDividerSize(8);
			split.setDividerLocation(sidebarWidth);
		} else {
			int loc = split.getDividerLocation();
			if (loc > 20) sidebarWidth = loc; // remember the width for next time
			sessionsPane.setVisible(false);
			split.setDividerSize(0);
			split.setDividerLocation(0);
		}
		split.revalidate();
	}

	private void refreshSettings() {
		String name = (identity != null) ? identity.name() : null;
		String did = (venue != null) ? venue.did() : null;
		settingsScreen.model().preselect(app.currentModelOp());
		settingsScreen.profile().refresh(name, did, app.publicKeyHex(), app.canRevealPrivateSeed());
		settingsScreen.vault().refresh(app.hasRememberedPassphrase());
	}

	private void showSettings(SettingsScreen.Tab tab) {
		settingsScreen.select(tab);
		selectTab(NavBar.Tab.SETTINGS);
	}

	/** Menu entry points into the Settings screen's tabs. */
	public void showModelSettings() {
		showSettings(SettingsScreen.Tab.MODEL);
	}

	public void showProfileSettings() {
		showSettings(SettingsScreen.Tab.PROFILE);
	}

	public void showAuthSettings() {
		showSettings(SettingsScreen.Tab.AUTH);
	}

	/** Open a (non-modal) inspector showing exactly what the model receives for a conversation. */
	public void showContextInfo(covia.brightside.AgentContext.Report report,
			List<SessionHistory.RawTurn> turns, String title) {
		JDialog dialog = new JDialog(this, "What the assistant sees — " + title, false);
		dialog.setContentPane(new covia.brightside.ui.inspect.ContextInspector(report, turns));
		dialog.setSize(940, 720);
		dialog.setMinimumSize(new Dimension(640, 460));
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	/** True while the chat is on screen — the watcher only polls then. */
	public boolean isChatShowing() {
		return isShowing() && CARD_CHAT.equals(currentCard);
	}

	/** Show the name screen so the person can change how they're addressed. */
	public void promptChangeName(String currentName) {
		welcomePanel.setSubtitle("Change how I address you.");
		welcomePanel.prepare(currentName, true);
		show(CARD_WELCOME);
		welcomePanel.focusField();
	}

	/** Return to the chat without changing the name. */
	public void showChatCard() {
		show(CARD_CHAT);
	}

	/** A working message on the welcome screen while the app finishes starting. */
	public void welcomeBusy(String message) {
		welcomePanel.setBusy(message);
	}

	public void showSystemMessage(String text) {
		chatPanel.appendSystem(text);
	}

	public void startupFailed(Throwable t) {
		startupFailed("BrightSide couldn't start up. Technical details are in the log folder"
			+ " (Advanced ▸ Open logs folder).");
	}

	/** Startup failed for a reason the person can act on — show it in their words. */
	public void startupFailed(String message) {
		if (CARD_WELCOME.equals(currentCard)) {
			welcomePanel.setError("Sorry — BrightSide couldn't start up.");
		}
		chatPanel.appendError(message);
	}

	private void updateWho() {
		if (identity == null) return;
		whoLabel.setText(identity.name());
		if (userMenu != null) userMenu.setText(identity.name());
		setTitle(BrightSide.APP_NAME);
	}

	private void show(String card) {
		currentCard = card;
		cards.show(deck, card);
	}

	// ------------------------------------------------------------------
	// Window controls
	// ------------------------------------------------------------------

	/** Bring the window back from the tray or from behind other windows. */
	public void showAndFocus() {
		setVisible(true);
		setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
		toFront();
		requestFocus();
		if (CARD_WELCOME.equals(currentCard)) welcomePanel.focusField();
	}

	private void showAbout() {
		StringBuilder sb = new StringBuilder();
		sb.append(BrightSide.APP_NAME).append('\n');
		sb.append("Your personal assistant, running privately on your own computer.\n\n");
		sb.append("Powered by the Covia Grid — an open platform for AI agents and data.\n");
		if (identity != null) sb.append("\nYou are: ").append(identity.name());
		if (venue != null) {
			// The technical identity, kept for the curious — this is the "settings" surface.
			sb.append("\nRunning locally at ").append(venue.url());
			if (venue.did() != null) sb.append("\nIdentity: ").append(identity != null
				? identity.userDID(venue.did()) : venue.did());
		}
		JTextArea area = new JTextArea(sb.toString());
		area.setEditable(false);
		area.setOpaque(false);
		area.setBorder(null);
		area.setFont(UIManager.getFont("Label.font"));
		JOptionPane.showMessageDialog(this, area,
			"About " + BrightSide.APP_NAME, JOptionPane.INFORMATION_MESSAGE, new ImageIcon(Icons.icon(64)));
	}

}
