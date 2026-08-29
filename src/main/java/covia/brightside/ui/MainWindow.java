package covia.brightside.ui;

import java.util.List;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
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
import covia.brightside.ui.chat.NewAgentPanel;
import covia.brightside.ui.settings.AuthPanel;
import covia.brightside.ui.settings.GeneralPanel;
import covia.brightside.ui.settings.ModelPanel;
import covia.brightside.ui.settings.ProfilePanel;
import covia.brightside.ui.settings.SettingsScreen;
import covia.brightside.ui.settings.VaultPanel;

/**
 * The BrightSide window. Two full-window screens on a {@link CardLayout}: the
 * friendly {@link WelcomePanel} ("What should I call you?") and the chat. All
 * technical detail (the local address, the identity behind {@code u:<name>})
 * lives in Settings and the About box, never in the main flow.
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
	private static final String MAIN_INBOX = "inbox";

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
	private covia.brightside.ui.inbox.InboxScreen inboxScreen;
	private covia.brightside.ui.chat.AgentList agentList;
	private int sidebarWidth = SIDEBAR_WIDTH;
	private final covia.brightside.ui.onboarding.OnboardingWizard onboarding;
	private final covia.brightside.ui.onboarding.UnlockPanel unlock;
	private final WelcomePanel welcomePanel;
	private final ChatPanel chatPanel = new ChatPanel();
	private final ConversationList conversations;
	private NavBar.Tab activeTab = NavBar.Tab.HOME;

	private EmbeddedVenue venue; // set once ready
	private Identity identity;
	private String currentCard;

	public MainWindow(BrightSide app) {
		super(BrightSide.APP_NAME);
		this.app = app;
		chatPanel.setConversationCommittedListener(app::conversationCommitted);
		setIconImages(Icons.appIcons());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		installShortcuts();

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
				NewAgentPanel.Options options = NewAgentPanel.showDialog(MainWindow.this, app.currentModelOp());
				if (options != null) app.createAgent(options.name(), options.modelOp(), options.systemPrompt());
			}

			@Override
			public void onAgentInfo(String agentId) {
				app.showAgentInfo(agentId);
			}

			@Override
			public void onDeleteAgent(String agentId) {
				app.deleteAgent(agentId);
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
		// Home is the initial chat surface, including while the background bind is
		// still starting. Do not construct the split in its Sessions-visible state.
		sessionsPane.setVisible(false);
		split.setDividerSize(0);
		split.setDividerLocation(0);

		GeneralPanel generalPanel = new GeneralPanel(new GeneralPanel.Host() {
			@Override
			public void newChat() {
				showNewChat();
			}

			@Override
			public void refreshConversations() {
				app.refreshNow();
			}

			@Override
			public void changeName() {
				app.changeName();
			}

			@Override
			public void logout() {
				app.logout();
			}

			@Override
			public void setKeepInTray(boolean value) {
				app.setKeepInTray(value);
			}

			@Override
			public void setMinimiseToTray(boolean value) {
				app.setMinimiseToTray(value);
			}

			@Override
			public void hideToTray() {
				app.hideToTray();
			}

			@Override
			public void openDashboard() {
				app.openDashboard();
			}

			@Override
			public void openConfigFile() {
				app.openConfigFile();
			}

			@Override
			public void openLogsFolder() {
				app.openLogsFolder();
			}

			@Override
			public void showAbout() {
				MainWindow.this.showAbout();
			}

			@Override
			public void quit() {
				app.exit();
			}
		});

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
			public String revealPrimarySeed(char[] passphrase) throws Exception {
				return app.revealPrivateSeed(passphrase);
			}

			@Override
			public void actAs(boolean operator) {
				app.actAs(operator);
			}
		});
		VaultPanel vaultPanel = new VaultPanel(app::forgetRememberedPassphrase);
		AuthPanel authPanel = new AuthPanel(app::mintAccessToken);
		settingsScreen = new SettingsScreen(generalPanel, modelPanel, profilePanel, vaultPanel, authPanel);

		mainDeck = new JPanel(mainCards);
		mainDeck.add(split, MAIN_CHAT);
		mainDeck.add(settingsScreen, MAIN_SETTINGS);
		inboxScreen = new covia.brightside.ui.inbox.InboxScreen(new covia.brightside.ui.inbox.RequestForm.Listener() {
			@Override
			public void onAnswer(String id, covia.brightside.Inbox.Answer answer) {
				app.answerRequest(id, answer);
			}

			@Override
			public void onReject(String id, String reason) {
				app.rejectRequest(id, reason);
			}
		});
		mainDeck.add(inboxScreen, MAIN_INBOX);

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
		selectTab(NavBar.Tab.HOME);
		chatPanel.appendSystem("Just a moment while everything starts up…");
		show(CARD_CHAT);
	}

	// ------------------------------------------------------------------
	// Keyboard shortcuts (visible actions live in Settings → General)
	// ------------------------------------------------------------------

	private void installShortcuts() {
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		bindShortcut("new-chat", KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), this::showNewChat);
		bindShortcut("refresh", KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcut), app::refreshNow);
		if (app.hasTray()) {
			bindShortcut("hide-to-tray", KeyStroke.getKeyStroke(KeyEvent.VK_H, shortcut), app::hideToTray);
		}
		bindShortcut("quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcut), app::exit);
	}

	private void bindShortcut(String name, KeyStroke key, Runnable action) {
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
		getRootPane().getActionMap().put(name, new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				action.run();
			}
		});
	}

	private void showNewChat() {
		if (activeTab == NavBar.Tab.HOME) app.newConversation();
		else selectTab(NavBar.Tab.HOME); // entering Home resets exactly once
	}

	// ------------------------------------------------------------------
	// State transitions (event thread)
	// ------------------------------------------------------------------

	/** Everything is ready: show a clean Home chat without minting a session. */
	public void showChat(EmbeddedVenue venue, ChatSession session, Identity identity, List<SessionHistory.Item> history) {
		this.venue = venue;
		this.identity = identity;
		updateWho();
		chatPanel.restore(history);
		chatPanel.setSession(session);
		selectTab(NavBar.Tab.HOME);
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

	/** Clear the transcript and composer for a clean new chat. */
	public void clearChat() {
		chatPanel.startNewConversation();
	}

	/** Clears every current-user UI binding while the venue keeps running. */
	public void userLoggedOut() {
		identity = null;
		chatPanel.clearSession();
		conversations.setSessions(List.of(), null);
		agentList.setAgents(List.of(), null, null);
		setInbox(List.of());
		settingsScreen.profile().clearSensitive();
		settingsScreen.auth().clearSensitive();
		settingsScreen.general().refresh(app.hasTray(), app.keepInTray(), app.minimiseToTray(), false, venue != null);
	}

	/** Update the transcript to the venue's live conversation (only re-renders if changed). */
	public void refreshConversation(List<SessionHistory.Item> turns) {
		refreshConversation(turns, false);
	}

	/** Update once the selected session's pending/in-cycle work has settled. */
	public void refreshConversation(List<SessionHistory.Item> turns, boolean sessionActive) {
		chatPanel.refreshTo(turns, sessionActive);
	}

	/** Replace the switcher's list of past conversations, highlighting the open one. */
	public void setConversations(List<SessionHistory.Session> sessions, String selectedId) {
		conversations.setSessions(sessions, selectedId);
	}

	/** Replace the agents pane's list, highlighting the current agent; {@code defaultId} is the standard agent. */
	public void setAgents(List<covia.brightside.model.AgentRef> agents, String selectedId, String defaultId) {
		agentList.setAgents(agents, selectedId, defaultId);
	}

	/** Show a chosen past conversation's transcript (a definite switch, not a poll). */
	public void showConversation(List<SessionHistory.Item> turns) {
		chatPanel.restore(turns);
	}

	/** Switch the bottom-nav content between the chat screens and settings. */
	private void selectTab(NavBar.Tab tab) {
		boolean enteringHome = tab == NavBar.Tab.HOME && activeTab != NavBar.Tab.HOME;
		activeTab = tab;
		if (enteringHome) app.newConversation();
		switch (tab) {
			case HOME -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(false);
			}
			case SESSIONS -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(true);
			}
			case INBOX -> mainCards.show(mainDeck, MAIN_INBOX);
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
		String venueDid = (venue != null) ? venue.did() : null;
		String userDid = (identity != null && venueDid != null) ? identity.userDID(venueDid) : null;
		settingsScreen.general().refresh(app.hasTray(), app.keepInTray(), app.minimiseToTray(),
			identity != null, venue != null);
		settingsScreen.model().preselect(app.currentModelOp());
		settingsScreen.profile().refresh(name, userDid, venueDid, app.publicKeyHex(), app.canRevealPrivateSeed(),
			app.actingAsOperator());
		settingsScreen.vault().refresh(app.hasRememberedPassphrase());
	}

	private void showSettings(SettingsScreen.Tab tab) {
		settingsScreen.select(tab);
		selectTab(NavBar.Tab.SETTINGS);
	}

	/** Programmatic entry points into the Settings screen's sections. */
	public void showGeneralSettings() {
		showSettings(SettingsScreen.Tab.GENERAL);
	}

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

	/** Open a (non-modal) window describing an agent: identity, status, model, instructions, capabilities. */
	public void showAgentInfo(covia.brightside.AgentInfo.Summary info) {
		JDialog dialog = new JDialog(this, "Agent — " + info.name(), false);
		dialog.setContentPane(new covia.brightside.ui.inspect.AgentInspector(info));
		dialog.setSize(760, 600);
		dialog.setMinimumSize(new Dimension(520, 400));
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	/** Replace the Inbox's requests (open first) and badge the tab with the number waiting. */
	public void setInbox(List<covia.brightside.Inbox.Request> requests) {
		inboxScreen.setRequests(requests);
		navBar.setBadge(NavBar.Tab.INBOX, covia.brightside.Inbox.pending(requests));
	}

	/** A line under the open request: the result of the last answer or rejection. */
	public void showInboxNotice(String text) {
		inboxScreen.showNotice(text);
	}

	/** Switch to the Inbox tab. */
	public void showInbox() {
		selectTab(NavBar.Tab.INBOX);
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
			+ " (Settings → General → Open logs folder).");
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
