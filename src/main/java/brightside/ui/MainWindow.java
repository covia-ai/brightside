package brightside.ui;

import static brightside.BrightSide.APP_NAME;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

import brightside.BrightSide;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.SessionHistory;
import brightside.Sizes;
import brightside.StoreSpace;
import brightside.chat.ChatSession;
import brightside.ui.chat.ChatPanel;
import brightside.ui.chat.ConversationList;
import brightside.ui.chat.NewAgentPanel;
import brightside.ui.components.Dialogs;
import brightside.ui.components.SelectableText;
import brightside.ui.inspect.SessionViews;
import brightside.ui.settings.AccessPanel;
import brightside.ui.settings.DiscordPanel;
import brightside.ui.settings.GeneralPanel;
import brightside.ui.settings.IntegrationsPanel;
import brightside.ui.settings.ModelPanel;
import brightside.ui.settings.MoltbookPanel;
import brightside.ui.settings.ProfilePanel;
import brightside.ui.settings.SchedulePanel;
import brightside.ui.settings.SecretsPanel;
import brightside.ui.settings.SettingsScreen;
import brightside.ui.settings.ThemePanel;
import brightside.ui.settings.VaultPanel;
import brightside.ui.shell.CommandPalette;
import brightside.ui.shell.Commands;
import brightside.ui.shell.Destination;
import brightside.ui.shell.Rail;
import brightside.ui.shell.ShellCommands;
import brightside.ui.shell.ShellLayout;
import brightside.ui.shell.StatusSlot;
import brightside.ui.shell.StatusStrip;

/**
 * The BrightSide window. Two full-window screens on a {@link CardLayout}: the
 * friendly {@link WelcomePanel} ("What should I call you?") and the chat. All
 * technical detail (the local address, the identity behind {@code u:<name>})
 * lives in Settings and the About box, never in the main flow.
 */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {

	private static final String CARD_ONBOARD = "onboarding";
	private static final String CARD_WELCOME = "welcome";
	private static final String CARD_CHAT = "chat";

	private static final int SIDEBAR_WIDTH = 400; // agents pane + sessions list
	private static final String MAIN_CHAT = "chat-main";
	private static final String MAIN_SETTINGS = "settings";
	private static final String MAIN_INBOX = "inbox";
	private static final String MAIN_SKILLS = "skills";
	private static final String MAIN_PEERS = "peers";
	private static final String MAIN_FILES = "files";

	private final BrightSide app;
	private final CardLayout cards = new CardLayout();
	private final JPanel deck = new JPanel(cards);

	// The bottom-nav content area: the chat (with an optional sessions bar) or settings.
	private final CardLayout mainCards = new CardLayout();
	private JSplitPane split;
	private JPanel mainDeck;
	private JPanel sessionsPane; // agents + sessions, the split's left side
	private SessionViews views; // the chat and its tabs, the split's right side
	private String currentAgentId;
	private NavBar navBar;
	private SettingsScreen settingsScreen;
	private brightside.ui.inbox.InboxScreen inboxScreen;
	private brightside.ui.skills.SkillsScreen skillsScreen;
	private brightside.ui.files.FileBrowser fileBrowser;
	private String filesOwner;
	private EmbeddedVenue filesVenue;
	private brightside.ui.chat.AgentList agentList;
	private int sidebarWidth = SIDEBAR_WIDTH;
	private final brightside.ui.onboarding.OnboardingWizard onboarding;
	private final WelcomePanel welcomePanel;
	private final ChatPanel chatPanel = new ChatPanel();
	private final ConversationList conversations;
	private NavBar.Tab activeTab = NavBar.Tab.HOME;
	private brightside.ui.peers.PeersScreen peersScreen;

	// The Workbench shell (ui.shell = rail, the default; classic keeps the bottom bar):
	// the sidebar, the status strip, the menu bar and the command palette.
	private static final String MAIN_FEED = "feed";
	private static final String MAIN_AGENTS = "agents";
	private brightside.ui.agents.AgentsScreen agentsScreen;
	/** The agents as last reported, for the Agents roster. */
	private List<brightside.model.AgentRef> lastAgents = List.of();
	private String lastDefaultAgent;
	/** The window opens at about this size the first time; a remembered size always wins. */
	static final int DEFAULT_WIDTH = 1200, DEFAULT_HEIGHT = 800;
	private Rail rail;
	private StatusStrip strip;
	private CommandPalette palette;
	private Commands commands;
	private JPanel chatCard;
	private boolean railShell;
	private boolean railCollapsedByChoice;
	/** On Agents, whether the person wants the agents and Chats list; it docks where there is room. */
	private boolean chatsListOpen = true;
	private Destination destination = Destination.HOME;

	private EmbeddedVenue venue; // set once ready
	private Identity identity;
	/** What the venue last reported each provider serving, for the new-agent dialog. */
	private Map<String, List<String>> servedModels = Map.of();
	private String currentCard;

	public MainWindow(BrightSide app) {
		super(BrightSide.APP_NAME);
		this.app = app;
		setIconImages(Icons.appIcons());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

		onboarding = new brightside.ui.onboarding.OnboardingWizard(app::onOnboardingComplete);

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
				views.newConversation();
			}

			@Override
			public void onSelectSession(String sessionId) {
				views.openSession(sessionId);
			}

			@Override
			public void onRenameSession(String sessionId, String newTitle) {
				views.renameSession(sessionId, newTitle);
			}

			@Override
			public void onCopyTranscript(String sessionId) {
				views.copyTranscript(sessionId);
			}

			@Override
			public void onSummariseSession(String sessionId) {
				views.summariseSession(sessionId);
			}

			@Override
			public void onCompactSession(String sessionId) {
				views.compactSession(sessionId);
			}

			@Override
			public void onInspectSession(String sessionId) {
				showSessionView(sessionId, SessionViews.View.OVERVIEW);
			}

			@Override
			public void onDeleteSession(String sessionId) {
				views.deleteSession(sessionId);
			}
		});

		// Replies are read aloud only when the owner has chosen it (Settings → General).
		chatPanel.readRepliesAloudWhen(app::readRepliesAloud);
		chatPanel.sendAfterPause(app::sendAfterPauseSeconds);

		// The conversation menu beside Send acts on the conversation on screen.
		chatPanel.setHost(new ChatPanel.Host() {
			@Override
			public void newConversation() {
				views.newConversation();
			}

			@Override
			public void inspectConversation(String sessionId) {
				showSessionView(sessionId, SessionViews.View.OVERVIEW);
			}

			@Override
			public void showInSessions(String sessionId) {
				selectTab(NavBar.Tab.SESSIONS);
				conversations.reveal(sessionId);
			}
		});

		// Agents pane (left) beside the sessions list — together the split's left
		// side. Each agent has its own sessions; selecting one switches the chat.
		agentList = new brightside.ui.chat.AgentList(new brightside.ui.chat.AgentList.Listener() {
			@Override
			public void onSelectAgent(String agentId) {
				app.switchAgent(agentId);
			}

			@Override
			public void onNewAgent() {
				newAgent();
			}

			@Override
			public void onAgentInfo(String agentId) {
				showAgentView(agentId);
			}

			@Override
			public void onDeleteAgent(String agentId) {
				app.deleteAgent(agentId);
			}
		});
		// Each agent's mini moon in the colour the Peers map draws it.
		agentList.setColours(aid -> {
			String owner = app.actingPrincipal();
			if (owner == null) return brightside.ui.components.Theme.muted();
			boolean main = !app.actingAsOperator() && aid.equals(app.mainAgentId());
			return brightside.ui.peers.Colours.agent(owner, aid, main);
		});
		sessionsPane = new JPanel(new BorderLayout());
		sessionsPane.add(agentList, BorderLayout.WEST);
		sessionsPane.add(conversations, BorderLayout.CENTER);

		// The chat with, on Sessions, tabs above it for the conversation's context
		// and its agent. Their reads are the app's, off the event thread.
		views = new SessionViews(chatPanel, new SessionViews.Host() {
			@Override
			public void loadSession(String sessionId, int version) {
				app.showSessionInfo(sessionId, version);
			}

			@Override
			public void loadAgent(String agentId, int version) {
				app.showAgentInfo(agentId, version);
			}

			@Override
			public void saveInstructions(String agentId, String instructions) {
				app.setAgentInstructions(agentId, instructions);
			}

			@Override
			public void sessionsChanged(List<SessionHistory.Session> sessions, String selectedId) {
				conversations.setSessions(sessions, selectedId);
			}

			@Override
			public void agentsChanged() {
				app.refreshAgents();
			}
			@Override
			public void openRecord() {
				go(Destination.JOBS);
			}


			@Override
			public void showSkill(String path) {
				selectTab(NavBar.Tab.SKILLS);
				skillsScreen.reveal(path);
			}
		});

		// A split so the sessions area can be resized (Sessions tab) or collapsed
		// (Home tab). The bottom nav switches Home/Sessions/Settings.
		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPane, views);
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
			public void setTakeOverWithoutAsking(boolean value) {
				app.setTakeOverWithoutAsking(value);
			}

			@Override
			public void setUnlockWithoutAsking(boolean value) {
				app.setUnlockWithoutAsking(value);
			}

			@Override
			public void setStartAtSignIn(boolean value) {
				app.setStartAtSignIn(value);
			}

			@Override
			public void setReadRepliesAloud(boolean value) {
				if (!value) {
					app.setReadRepliesAloud(false);
					chatPanel.stopReadingAloud();
					return;
				}
				// On only once the voice is here, or the owner has agreed to fetch it.
				chatPanel.getReadyToReadAloud().thenAccept(ready -> {
					app.setReadRepliesAloud(ready);
					settingsScreen.general().refreshVoice(ready, app.sendAfterPauseSeconds());
				});
			}

			@Override
			public void setSendAfterPause(int seconds) {
				app.setSendAfterPauseSeconds(seconds);
			}

			@Override
			public void setCompactAtNextStart(boolean value) {
				try {
					app.setCompactAtNextStart(value);
				} catch (IOException e) {
					Dialogs.warn(MainWindow.this, "Data store", APP_NAME + " couldn't save that: " + e.getMessage());
				}
				settingsScreen.general().refreshStore(app.storeReport());
			}

			@Override
			public void removeKeptOriginals() {
				StoreSpace.Report report = app.storeReport();
				if (report == null || report.keptOriginals().isEmpty()) return;
				int n = report.keptOriginals().size();
				if (Dialogs.confirmDanger(MainWindow.this, "Remove kept files",
						"Delete " + ((n == 1) ? "the file" : "the " + n + " files") + " kept from earlier repairs ("
							+ Sizes.of(report.keptBytes()) + ")?\nKeep them if anything has seemed missing since a repair."
							+ " This can't be undone.")) {
					try {
						app.removeKeptOriginals();
					} catch (IOException e) {
						Dialogs.warn(MainWindow.this, "Data store", APP_NAME + " couldn't remove them: " + e.getMessage());
					}
				}
				settingsScreen.general().refreshStore(app.storeReport());
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

		ThemePanel themePanel = new ThemePanel(new ThemePanel.Host() {
			@Override
			public void setMode(String mode) {
				app.setMode(mode);
			}

			@Override
			public void setTheme(String id) {
				app.setTheme(id);
			}

			@Override
			public void setAccent(String accent) {
				app.setAccent(accent);
			}

			@Override
			public void openThemesFolder() {
				app.openThemesFolder();
			}

			@Override
			public void setShell(boolean rail) {
				app.setUiPref("shell", rail ? "rail" : "classic");
				applyShell(rail);
			}

			@Override
			public void setDensity(boolean compact) {
				app.setUiPref("density", compact ? "compact" : "comfortable");
				applyDensity(compact);
			}
		});

		ModelPanel modelPanel = new ModelPanel(new ModelPanel.Handler() {
			@Override
			public void applyModel(String providerId, String modelId) {
				app.applyModel(providerId, modelId);
				updateModelChip();
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
			public char[] rememberedPassphrase() {
				return app.rememberedPassphrase();
			}

			@Override
			public void actAs(boolean operator) {
				app.actAs(operator);
			}
		});
		IntegrationsPanel integrationsPanel = new IntegrationsPanel(new DiscordPanel.Host() {
			@Override
			public void saveDiscord(String token, List<String> allow) {
				app.saveDiscord(token, allow);
			}

			@Override
			public void removeDiscord() {
				app.removeDiscord();
			}
		}, new MoltbookPanel.Host() {
			@Override
			public void registerMoltbook(String name, String description) {
				app.registerMoltbook(name, description);
			}

			@Override
			public void connectMoltbook(String apiKey) {
				app.connectMoltbook(apiKey);
			}

			@Override
			public void forgetMoltbook() {
				app.forgetMoltbook();
			}

			@Override
			public void refreshMoltbookStatus() {
				app.refreshMoltbookStatus();
			}
		});
		SecretsPanel secretsPanel = new SecretsPanel(new SecretsPanel.Host() {
			@Override
			public java.util.List<String> listSecrets() {
				return app.listSecretNames();
			}

			@Override
			public boolean storeSecret(String name, String value) {
				return app.storeSecret(name, value);
			}

			@Override
			public boolean deleteSecret(String name) {
				return app.deleteSecret(name);
			}

			@Override
			public String revealSecret(String name, char[] passphrase) throws Exception {
				return app.revealSecret(name, passphrase);
			}

			@Override
			public char[] rememberedPassphrase() {
				return app.rememberedPassphrase();
			}
		});
		SchedulePanel schedulePanel = new SchedulePanel(new SchedulePanel.Host() {
			@Override
			public void refresh() {
				app.refreshSchedule();
			}

			@Override
			public void runNow(brightside.Schedule.Event event, SchedulePanel.Result done) {
				app.runScheduled(event, done);
			}

			@Override
			public void cancel(brightside.Schedule.Event event, SchedulePanel.Result done) {
				app.cancelScheduled(event, done);
			}

			@Override
			public void schedule(String operation, convex.core.data.ACell input, long afterMillis, long everyMillis,
					boolean track, SchedulePanel.Result done) {
				app.scheduleNew(operation, input, afterMillis, everyMillis, track, done);
			}

			@Override
			public void openConversation(String agentId, String sessionId) {
				MainWindow.this.openConversation(agentId, sessionId);
			}
		});
		brightside.ui.jobs.JobsScreen jobsScreen = new brightside.ui.jobs.JobsScreen(new brightside.ui.jobs.JobsScreen.Host() {
			@Override
			public void control(brightside.JobList.Job job, brightside.ui.jobs.JobsScreen.Control control, String message,
					brightside.ui.jobs.JobsScreen.Result done) {
				app.controlJob(job, control, message, done);
			}

			@Override
			public void openConversation(String agentId, String sessionId) {
				MainWindow.this.openConversation(agentId, sessionId);
			}
		});
		VaultPanel vaultPanel = new VaultPanel(app::forgetRememberedPassphrase);
		AccessPanel accessPanel = new AccessPanel(new AccessPanel.Host() {
			@Override
			public List<AccessPanel.Row> tokens() {
				return app.listTokens();
			}

			@Override
			public convex.lattice.ALatticeComponent<?> tokenStore() {
				return app.tokenStore();
			}

			@Override
			public String chatAgentId() {
				return app.mainAgentId();
			}

			@Override
			public String createToken(String name, brightside.Tokens.Scope scope) throws Exception {
				return app.createToken(name, scope);
			}

			@Override
			public String newTokenKey(String name) throws Exception {
				return app.newTokenKey(name);
			}

			@Override
			public void revokeToken(String name) throws Exception {
				app.revokeToken(name);
			}

			@Override
			public String revealTokenKey(String name, char[] passphrase) throws Exception {
				return app.revealTokenKey(name, passphrase);
			}

			@Override
			public char[] rememberedPassphrase() {
				return app.rememberedPassphrase();
			}

			@Override
			public String mintLogin(long seconds, boolean asOperator) {
				return app.mintAccessToken(seconds, asOperator);
			}

			@Override
			public boolean keepingToolToken() {
				return app.keepToolToken();
			}

			@Override
			public void keepToolToken(boolean keep) {
				app.setKeepToolToken(keep);
			}
		});
		settingsScreen = new SettingsScreen(generalPanel, themePanel, modelPanel, profilePanel, integrationsPanel,
			schedulePanel, jobsScreen, secretsPanel, vaultPanel, accessPanel);

		mainDeck = new JPanel(mainCards);
		mainDeck.add(split, MAIN_CHAT);
		mainDeck.add(settingsScreen, MAIN_SETTINGS);
		inboxScreen = new brightside.ui.inbox.InboxScreen(new brightside.ui.inbox.RequestForm.Listener() {
			@Override
			public void onAnswer(brightside.Inbox.Request request, brightside.Inbox.Answer answer) {
				app.answerRequest(request, answer);
			}

			@Override
			public void onReject(brightside.Inbox.Request request, String reason) {
				app.rejectRequest(request, reason);
			}
		});
		mainDeck.add(inboxScreen, MAIN_INBOX);

		skillsScreen = new brightside.ui.skills.SkillsScreen();
		mainDeck.add(skillsScreen, MAIN_SKILLS);
		// Where files live is managed where they are browsed: folders on this
		// computer apply on the next start, personal drives at once.
		fileBrowser = new brightside.ui.files.FileBrowser(new brightside.ui.files.Places() {
			@Override public java.util.Map<String, Boolean> savedFolders() throws Exception {
				java.util.Map<String, Boolean> folders = new java.util.LinkedHashMap<>();
				for (brightside.FileRoots.Root root : app.fileRoots().list()) folders.put(root.name(), root.readOnly());
				return folders;
			}
			@Override public void addFolder(String name, java.nio.file.Path folder, boolean readOnly) throws Exception {
				app.fileRoots().add(name, folder, readOnly);
			}
			@Override public void setReadOnly(String name, boolean readOnly) throws Exception {
				app.fileRoots().setReadOnly(name, readOnly);
			}
			@Override public void removeFolder(String name) throws Exception { app.fileRoots().remove(name); }
			@Override public void createDrive(String name) throws Exception {
				brightside.PersonalDrives drives = app.personalDrives();
				if (drives == null) throw new IllegalStateException("Open " + APP_NAME + " to make a drive.");
				drives.create(name);
			}
			@Override public String folderNameProblem(String name) { return brightside.FileRoots.nameProblem(name); }
			@Override public String driveNameProblem(String name) { return brightside.PersonalDrives.nameProblem(name); }
			@Override public String suggestFolderName(java.nio.file.Path folder) { return brightside.FileRoots.suggestName(folder); }
		});
		mainDeck.add(fileBrowser, MAIN_FILES);

		// Your venue, live from the embedded venue, among sample venues: nothing on the wire.
		// How the map is drawn is the acting person's own, kept apart from the data.
		peersScreen = new brightside.ui.peers.PeersScreen(brightside.peers.PeerSource.combined(
			new brightside.peers.SamplePeerSource(), new brightside.peers.LiveSource(app::homePeers)), app::peersMapSettings,
			new brightside.ui.peers.PeersScreen.Actions() {
				@Override
				public boolean actingAsOperator() {
					return app.actingAsOperator();
				}

				@Override
				public void actAs(boolean operator) {
					app.actAs(operator);
				}

				@Override
				public void chat(boolean operator, String agentId) {
					app.chatWith(operator, agentId);
					selectTab(NavBar.Tab.HOME);
				}
			});
		mainDeck.add(peersScreen, MAIN_PEERS);

		navBar = new NavBar(this::selectTab);
		mainDeck.add(new FeedScreen(), MAIN_FEED);
		agentsScreen = new brightside.ui.agents.AgentsScreen(new AgentsHost());
		mainDeck.add(agentsScreen, MAIN_AGENTS);
		chatCard = new JPanel(new BorderLayout());
		installShell();

		deck.add(onboarding, CARD_ONBOARD);
		deck.add(welcomePanel, CARD_WELCOME);
		deck.add(chatCard, CARD_CHAT);
		add(deck, BorderLayout.CENTER);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				saveGeometry();
				app.onWindowClosing();
			}

			@Override
			public void windowIconified(WindowEvent e) {
				app.onWindowIconified();
			}
		});

		setMinimumSize(new Dimension(720, 520));
		restoreGeometry();
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				relayout();
			}
		});
		// The first screen is chosen by BrightSide.start() (onboarding / name /
		// chat), via the show* methods below; a returning owner unlocks in the
		// UnlockDialog before this window is shown at all.
	}

	/** First-run: the onboarding wizard. */
	public void showOnboarding() {
		show(CARD_ONBOARD);
	}

	/**
	 * Returning after unlock: Home, exactly as it will be once the chat is
	 * bound — the composer's hint alone says the app is still starting.
	 */
	public void showChatStartup() {
		selectTab(NavBar.Tab.HOME);
		chatPanel.setStartingUp(true);
		show(CARD_CHAT);
	}

	// ------------------------------------------------------------------
	// Keyboard shortcuts (visible actions live in Settings → General)
	// ------------------------------------------------------------------


	private void showNewChat() {
		selectTab(NavBar.Tab.HOME);
		views.newConversation();
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
		updateModelChip();
		selectTab(NavBar.Tab.HOME);
		show(CARD_CHAT);
		app.refreshServedModels(); // answers through showServedModels, off the event thread
	}

	/** Switched to another agent: rebind the chat to its conversation (no name-change note). */
	public void showAgentChat(ChatSession session, List<SessionHistory.Item> history, String agentName) {
		chatPanel.setAgentName(agentName);
		updateModelChip();
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
		chatPanel.appendSystem("Okay, I'll call you " + identity.name() + " from now on.");
		show(CARD_CHAT);
	}

	/** Clears every current-user UI binding while the venue keeps running. */
	public void userLoggedOut() {
		views.close();
		identity = null;
		identityChanged();
		chatPanel.clearSession();
		conversations.setSessions(List.of(), null);
		agentList.setAgents(List.of(), null, null);
		currentAgentId = null;
		clearInbox();
		settingsScreen.profile().clearSensitive();
		settingsScreen.integrations().clearSensitive();
		settingsScreen.schedule().clear();
		settingsScreen.secrets().clearSensitive();
		fileBrowser.clear();
		filesOwner = null;
		filesVenue = null;
		settingsScreen.access().clearSensitive();
		settingsScreen.general().refresh(app.hasTray(), app.keepInTray(), app.minimiseToTray(), false, venue != null);
	}

	/** The session view owns selection, live reads and subscriptions. */
	public void bindSessions(EmbeddedVenue venue, String owner, ChatSession session,
			List<SessionHistory.Session> sessions) {
		views.bind(venue, owner, session, sessions);
		if (filesVenue != venue || !java.util.Objects.equals(filesOwner, owner)) {
			filesVenue = venue;
			filesOwner = owner;
			fileBrowser.bind(new brightside.files.VenueFiles(venue, owner));
			if (activeTab == NavBar.Tab.FILES) fileBrowser.refresh();
		}
	}

	public void refreshSessions() {
		views.refresh();
	}

	public void closeSessions() {
		views.close();
	}

	/** Replace the agents pane's list, highlighting the current agent; {@code defaultId} is the standard agent. */
	public void setAgents(List<brightside.model.AgentRef> agents, String selectedId, String defaultId) {
		agentList.setAgents(agents, selectedId, defaultId);
		lastAgents = List.copyOf(agents);
		lastDefaultAgent = defaultId;
		if (agentsScreen != null && destination == Destination.AGENTS) refreshAgentsScreen();
		// The welcome greets by the current agent's name, and follows a rename.
		String name = null;
		for (brightside.model.AgentRef a : agents) {
			if (a.id().equals(selectedId)) name = a.name();
		}
		chatPanel.setAgentName((name != null && !name.isBlank()) ? name : selectedId);
		if (java.util.Objects.equals(selectedId, currentAgentId)) views.agentChanged();
		else views.setAgent(selectedId);
		currentAgentId = selectedId;
	}

	/**
	 * Show a conversation's context on Sessions: the conversation opens (if it
	 * is not the one on screen) and the chosen view comes to the front.
	 */
	private void showSessionView(String sessionId, SessionViews.View view) {
		selectTab(NavBar.Tab.SESSIONS);
		if (sessionId != null) {
			views.openSession(sessionId);
			conversations.reveal(sessionId);
		}
		views.select(view);
	}

	/** Show an agent on Sessions: the chat switches to it (if it is not the current one) and its view comes to the front. */
	private void showAgentView(String agentId) {
		selectTab(NavBar.Tab.SESSIONS);
		if (agentId != null && !agentId.equals(currentAgentId)) app.switchAgent(agentId);
		views.setAgent(agentId);
		views.select(SessionViews.View.AGENT);
	}

	/**
	 * Shows agent {@code agentId}'s conversation {@code sessionId} in the chat,
	 * switching to that agent first if another is on screen.
	 */
	public void openConversation(String agentId, String sessionId) {
		if (agentId == null || sessionId == null) return;
		selectTab(NavBar.Tab.SESSIONS);
		if (agentId.equals(currentAgentId)) views.openSession(sessionId);
		else app.switchAgent(agentId, sessionId);
	}

	/** Switch the bottom-nav content between the chat screens and settings. */
	private void selectTab(NavBar.Tab tab) {
		// Entering Home keeps the conversation in progress; only the explicit
		// new-chat control starts a fresh one.
		activeTab = tab;
		switch (tab) {
			case HOME -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(false);
				views.setTabsVisible(false);
			}
			case SESSIONS -> {
				mainCards.show(mainDeck, MAIN_CHAT);
				setSidebar(true);
				views.setTabsVisible(true);
				// Agents and conversations can change out of band (assistant-created
				// agents, another client): re-read on entry.
				app.refreshAgents();
			}
			case PEERS -> {
				// The acting person may have changed, or saved elsewhere: read their settings afresh.
				peersScreen.reload();
				mainCards.show(mainDeck, MAIN_PEERS);
			}
			case FILES -> {
				fileBrowser.refresh();
				mainCards.show(mainDeck, MAIN_FILES);
			}
			case INBOX -> mainCards.show(mainDeck, MAIN_INBOX);
			case SKILLS -> {
				mainCards.show(mainDeck, MAIN_SKILLS);
				// The assistant can author skills between visits: re-read on entry.
				app.refreshSkills();
			}
			case SETTINGS -> {
				refreshSettings();
				mainCards.show(mainDeck, MAIN_SETTINGS);
			}
		}
		navBar.setActive(tab);
		syncRail(tab);
		relayout();
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

	/** Re-read every settings page from the app's state — on entering Settings, and after the acting principal changes. */
	public void refreshSettings() {
		String name = (identity != null) ? identity.name() : null;
		String venueDid = (venue != null) ? venue.did() : null;
		String userDid = (identity != null && venueDid != null) ? identity.userDID(venueDid) : null;
		settingsScreen.general().refresh(app.hasTray(), app.keepInTray(), app.minimiseToTray(),
			identity != null, venue != null);
		settingsScreen.general().refreshStartup(app.takeOverWithoutAsking(), app.unlockWithoutAsking(),
			app.hasRememberedPassphrase());
		settingsScreen.general().refreshSignIn(app.startAtSignInOffered(), app.startAtSignIn(),
			app.hasRememberedPassphrase(), app.startAtSignInUnavailable());
		settingsScreen.general().refreshVoice(app.readRepliesAloud(), app.sendAfterPauseSeconds());
		settingsScreen.general().refreshStore(app.storeReport());
		settingsScreen.theme().refresh(app.mode(), app.theme(), app.accent());
		settingsScreen.theme().refreshLayout(railShell, brightside.ui.components.Density.compact());
		settingsScreen.model().preselect(app.currentModelOp());
		app.refreshServedModels(); // answers through showServedModels, off the event thread
		settingsScreen.profile().refresh(name, userDid, venueDid, app.publicKeyHex(), app.canRevealPrivateSeed(),
			app.actingAsOperator(), app.operatorName());
		app.refreshDiscordStatus(); // answers through showDiscordStatus, off the event thread
		app.refreshMoltbookStatus(); // likewise, through showMoltbookStatus
		app.refreshSchedule(); // answers through showSchedule, off the event thread
		settingsScreen.secrets().refresh(app.listSecretNames());
		settingsScreen.vault().refresh(app.hasRememberedPassphrase());
		settingsScreen.access().bind();
	}

	/** Re-read the Theme page after the look changed: the mode's list, its selected theme and the accent. */
	public void refreshTheme() {
		settingsScreen.theme().refresh(app.mode(), app.theme(), app.accent());
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

	/** The owner's Discord bot as the venue reports it (null: none), with an optional note for Settings → Integrations. */
	/** The models the venue reports each provider serving right now, by provider id, for the model pickers. */
	public void showServedModels(Map<String, List<String>> served) {
		servedModels = served;
		settingsScreen.model().offer(served);
	}

	public void showDiscordStatus(brightside.Discord.Bot bot, String note) {
		settingsScreen.integrations().discord().showStatus(bot, note);
	}

	/** The acting principal's scheduled operations, soonest first, or the error that stopped the read, for Settings → Schedule. */
	public void showSchedule(java.util.List<brightside.Schedule.Event> events, String error) {
		settingsScreen.schedule().show(events, error);
	}

	/** The owner's Moltbook account as Brightside reads it (null: none), with an optional note for Settings → Integrations. */
	public void showMoltbookStatus(brightside.Moltbook.Account account, String note) {
		settingsScreen.integrations().moltbook().showStatus(account, note);
	}

	/** What the model receives for a conversation, as read ({@code report} null: it could not be), for the Sessions tabs. */
	public void showContextInfo(String sessionId, int version, brightside.AgentContext.Report report,
			List<SessionHistory.RawTurn> turns, List<brightside.SkillIndex.Skill> skills) {
		views.showSession(sessionId, version, report, turns, skills);
	}

	/**
	 * An agent as read ({@code info} null: it could not be), for the Agent tab
	 * on Sessions; {@code editable}: the owner may change its instructions there.
	 */
	public void showAgentInfo(String agentId, int version, brightside.AgentInfo.Summary info, boolean editable) {
		views.showAgent(agentId, version, info, editable);
	}

	/** The agent on screen changed: its Agent tab is read again. */
	public void agentChanged() {
		views.agentChanged();
	}

	/** Replace the Skills screen's tree: {@code agentName}'s skillsets (the roots, in search order) over what they reach. */
	public void setSkills(String agentName, List<String> skillsets, List<brightside.SkillIndex.Skill> index) {
		skillsScreen.setSkills(agentName, skillsets, index);
	}

	/** Whether the Skills screen is on view, so a refresh knows to re-read it. */
	public boolean isSkillsShowing() {
		return isShowing() && CARD_CHAT.equals(currentCard) && activeTab == NavBar.Tab.SKILLS;
	}

	/** Replace the Inbox's requests (open first) and badge the tab with the number waiting. */
	public void setInbox(List<brightside.Inbox.Request> requests) {
		inboxScreen.setRequests(requests);
		int waiting = brightside.Inbox.pending(requests);
		navBar.setBadge(NavBar.Tab.INBOX, waiting);
		setWaiting(waiting);
	}

	/** {@code owner}'s jobs for Settings → Jobs, which watches them while on screen; null for none. */
	public void setJobs(covia.venue.Jobs jobs, String owner) {
		settingsScreen.jobs().setJobs(jobs, owner);
	}

	public void clearJobs() {
		settingsScreen.jobs().clear();
	}

	public void clearInbox() {
		inboxScreen.clear();
		navBar.setBadge(NavBar.Tab.INBOX, 0);
		setWaiting(0);
	}

	/** A line under the open request: the result of the last answer or rejection. */
	public void showInboxNotice(brightside.Inbox.Request request, String text) {
		inboxScreen.showNotice(request, text);
	}

	/** Switch to the Inbox tab. */
	public void showInbox() {
		selectTab(NavBar.Tab.INBOX);
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
		startupFailed(APP_NAME + " couldn't start up. Technical details are in the log folder"
			+ " (Settings → General → Open logs folder).");
	}

	/** Startup failed for a reason the person can act on — show it in their words. */
	public void startupFailed(String message) {
		if (CARD_ONBOARD.equals(currentCard)) {
			// The wizard is still on screen: the chat panel behind it is not.
			onboarding.showFailure(message);
			return;
		}
		if (CARD_WELCOME.equals(currentCard)) {
			welcomePanel.setError("Sorry, " + APP_NAME + " couldn't start up.");
		}
		chatPanel.appendError(message);
	}

	private void updateWho() {
		identityChanged();
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
		sb.append("Powered by the Covia Grid, an open platform for AI agents and data.\n");
		sb.append("\nType: Inter and Inconsolata, each under the SIL Open Font License 1.1.\n");
		if (identity != null) sb.append("\nYou are: ").append(identity.name());
		if (venue != null) {
			// The technical identity, kept for the curious — this is the "settings" surface.
			sb.append("\nRunning locally at ").append(venue.url());
			if (venue.did() != null) sb.append("\nIdentity: ").append(identity != null
				? identity.userDID(venue.did()) : venue.did());
		}
		Dialogs.info(this, "About " + BrightSide.APP_NAME, new SelectableText(sb.toString()), new ImageIcon(Icons.icon(64)));
	}


	// ------------------------------------------------------------------
	// The Workbench shell
	// ------------------------------------------------------------------

	/** The composer's model chip follows the model the chat runs on. */
	public void updateModelChip() {
		String op = app.currentModelOp();
		chatPanel.setModel(brightside.model.Providers.label(op), brightside.model.Providers.marker(op),
			() -> showSettings(SettingsScreen.Tab.MODEL));
	}

	/** The New agent dialog: from the Agents list's + Agent, File → New agent, and the palette. */
	private void newAgent() {
		NewAgentPanel.Options options = NewAgentPanel.showDialog(this, app.currentModelOp(), servedModels);
		if (options != null) {
			app.createAgent(options.name(), options.modelOp(), options.systemPrompt(), options.template().skill());
		}
	}

	/** Builds the sidebar, status strip, palette and menu bar, then lays the chat card out for the chosen shell. */
	private void installShell() {
		Shell host = new Shell();
		commands = ShellCommands.build(host);
		rail = new Rail(host);
		strip = new StatusStrip(host);
		palette = new CommandPalette(commands);
		for (Commands.Command c : commands.all()) if (c.id().equals("palette")) strip.setSearchKey(keyText(c.key()));
		setJMenuBar(menuBar());
		railCollapsedByChoice = "true".equals(app.uiPref("rail.collapsed", "false"));
		applyShell(!"classic".equals(app.uiPref("shell", "rail")));
		applyDensity("compact".equals(app.uiPref("density", "comfortable")));
	}

	/** {@code true}: the sidebar and status strip; {@code false}: the classic bottom bar. Live, remembered by the caller. */
	private void applyShell(boolean railShell) {
		this.railShell = railShell;
		// The agents and Chats list: one column in the sidebar shell (the picker atop the
		// conversations), two side by side in the classic layout, as before.
		sessionsPane.removeAll();
		agentList.setStacked(railShell);
		sessionsPane.add(agentList, railShell ? BorderLayout.NORTH : BorderLayout.WEST);
		sessionsPane.add(conversations, BorderLayout.CENTER);
		sidebarWidth = railShell ? ShellLayout.LIST : SIDEBAR_WIDTH;
		if (sessionsPane.isVisible()) split.setDividerLocation(sidebarWidth);
		sessionsPane.revalidate();
		chatCard.removeAll();
		if (railShell) {
			JPanel content = new JPanel(new BorderLayout());
			content.add(strip, BorderLayout.NORTH);
			content.add(mainDeck, BorderLayout.CENTER);
			chatCard.add(rail, BorderLayout.WEST);
			chatCard.add(content, BorderLayout.CENTER);
		} else {
			chatCard.add(mainDeck, BorderLayout.CENTER);
			chatCard.add(navBar, BorderLayout.SOUTH);
		}
		chatCard.revalidate();
		chatCard.repaint();
		relayout();
	}

	/** Whether the sidebar shell is in use (for tests and the harness). */
	public boolean railShell() {
		return railShell;
	}

	/**
	 * Applies {@link ShellLayout} at the window's current width: the rail
	 * collapses before content is squeezed, and on Agents the agents and Chats
	 * list docks only where the main column keeps its floor; elsewhere it is
	 * shown on request (the Chats list toggle), never removed.
	 */
	private void relayout() {
		if (rail == null || split == null) return;
		int width = getContentPane().getWidth();
		if (width <= 0) width = getWidth();
		// The Chats list lives beside the chat on Home and on Agents in the sidebar shell.
		boolean agents = activeTab == NavBar.Tab.SESSIONS || (railShell && activeTab == NavBar.Tab.HOME);
		// The layout's widths are window points, as the window's own size is.
		// The inspector (the chat's Overview, Context and Agent) docks beside the chat from 1440.
		boolean inspecting = activeTab == NavBar.Tab.SESSIONS;
		ShellLayout.Plan plan = ShellLayout.plan(width, sidebarWidth, agents && chatsListOpen, inspecting,
			railCollapsedByChoice);
		if (!railShell) {
			views.setInspectorDocked(false); // the classic layout behaves exactly as before
			return;
		}
		rail.setCollapsed(plan.railCollapsed());
		views.setInspectorDocked(inspecting && plan.side() == ShellLayout.Mode.DOCKED);
		if (agents) {
			boolean show = chatsListOpen && (plan.list() == ShellLayout.Mode.DOCKED || listForced);
			if (show != sessionsPane.isVisible()) setSidebar(show);
		}
	}

	/** The person asked for the list at a width where it would not dock by itself. */
	private boolean listForced;

	private void syncRail(NavBar.Tab tab) {
		if (rail == null) return;
		switch (tab) {
			case HOME -> markDestination(Destination.HOME);
			case SESSIONS -> markDestination(Destination.HOME); // the chat with its inspect tabs
			case FILES -> markDestination(Destination.FILES);
			case SKILLS -> markDestination(Destination.SKILLS);
			case PEERS -> markDestination(Destination.PEERS);
			case INBOX -> markDestination(Destination.INBOX);
			case SETTINGS -> {
				// Jobs and the Vault's pages live in Settings until their own screens land.
				SettingsScreen.Tab page = settingsScreen.selected();
				if (page == SettingsScreen.Tab.JOBS && (destination == Destination.JOBS || destination == Destination.RECORD)) {
					markDestination(destination);
				} else if ((page == SettingsScreen.Tab.SECRETS || page == SettingsScreen.Tab.VAULT
						|| page == SettingsScreen.Tab.ACCESS) && destination == Destination.VAULT) {
					markDestination(Destination.VAULT);
				} else {
					rail.setSettingsActive();
					rail.slot().setQuietScreenOpen(false);
				}
			}
		}
	}

	private void markDestination(Destination d) {
		destination = d;
		rail.setActive(d);
		// The Record, the Vault and the Constellation are quiet screens: the slot keeps to the glyph.
		rail.slot().setQuietScreenOpen(d == Destination.RECORD || d == Destination.VAULT || d == Destination.PEERS);
	}

	/** Goes to a sidebar destination; Jobs, Record and the Vault open their Settings pages until their screens land. */
	public void go(Destination d) {
		destination = d;
		switch (d) {
			case HOME -> selectTab(NavBar.Tab.HOME);
			case AGENTS -> {
				selectTab(NavBar.Tab.HOME);
				app.refreshAgents(); // agents can change out of band: re-read on entry
				refreshAgentsScreen();
				mainCards.show(mainDeck, MAIN_AGENTS);
			}
			case FEED -> {
				selectTab(NavBar.Tab.HOME);
				mainCards.show(mainDeck, MAIN_FEED);
				markDestination(Destination.FEED);
			}
			case JOBS, RECORD -> showSettings(SettingsScreen.Tab.JOBS);
			case INBOX -> selectTab(NavBar.Tab.INBOX);
			case PEERS -> selectTab(NavBar.Tab.PEERS);
			case SKILLS -> selectTab(NavBar.Tab.SKILLS);
			case FILES -> selectTab(NavBar.Tab.FILES);
			case VAULT -> showSettings(SettingsScreen.Tab.SECRETS);
		}
		if (d != Destination.FEED) markDestination(d);
	}

	/**
	 * The Agents roster: your own agents (the venue's own, with their roles,
	 * when acting as the operator), then outside agents that reach the venue
	 * with a named token.
	 */
	private void refreshAgentsScreen() {
		List<brightside.ui.agents.AgentsScreen.Row> rows = new java.util.ArrayList<>();
		boolean operator = app.actingAsOperator();
		for (brightside.model.AgentRef a : lastAgents) {
			String role = operator ? godRole(a.id()) : null;
			rows.add(new brightside.ui.agents.AgentsScreen.Row(a.id(), a.name(),
				role != null ? brightside.ui.agents.AgentsScreen.Kind.VENUE : brightside.ui.agents.AgentsScreen.Kind.YOURS,
				"This computer", "Ready", role != null ? "Looks after this computer" : "Acts for you", role,
				app.actingPrincipal() != null ? app.actingPrincipal() + "/g/" + a.id() : null));
		}
		try {
			for (AccessPanel.Row t : app.listTokens()) {
				rows.add(new brightside.ui.agents.AgentsScreen.Row(t.name(), t.name(),
					brightside.ui.agents.AgentsScreen.Kind.OUTSIDE_MCP, "A local client", "Token active",
					t.unrestricted() ? "Unrestricted" : t.scope(), null));
			}
		} catch (RuntimeException e) {
			// No token store yet (still starting): the roster shows the agents alone.
		}
		agentsScreen.setRows(rows, lastDefaultAgent);
	}

	private static String godRole(String id) {
		return switch (id) {
			case "Odin" -> "the administrator";
			case "Thor" -> "the guardian";
			case "Njord" -> "the treasurer";
			case "Eir" -> "the healer";
			default -> null;
		};
	}

	/** What the Agents roster asks of the window. */
	private final class AgentsHost implements brightside.ui.agents.AgentsScreen.Host {
		@Override
		public void open(brightside.ui.agents.AgentsScreen.Row agent) {
			if (!agent.id().equals(currentAgentId)) app.switchAgent(agent.id());
			MainWindow.this.go(Destination.HOME);
		}

		@Override public void info(brightside.ui.agents.AgentsScreen.Row agent) { showAgentView(agent.id()); }
		@Override public void delete(brightside.ui.agents.AgentsScreen.Row agent) { app.deleteAgent(agent.id()); }
		@Override public void createHere() { newAgent(); }

		@Override
		public void connectOutside() {
			destination = Destination.VAULT;
			showSettings(SettingsScreen.Tab.ACCESS);
		}

		@Override public void manageAccess(brightside.ui.agents.AgentsScreen.Row agent) { connectOutside(); }

		@Override
		public void copy(String text) {
			brightside.ui.components.Clipboard.copy(text, getRootPane());
		}
	}

	/** The open chat with its inspect tabs (Chat, Overview, Context, Agent) on view. */
	private void inspectChat(boolean raw) {
		selectTab(NavBar.Tab.SESSIONS);
		if (raw) {
			views.select(SessionViews.View.CONTEXT);
			views.select(SessionViews.Part.RAW);
		} else {
			views.select(SessionViews.View.OVERVIEW);
		}
	}

	/** The destination the sidebar shows as active, or null while Settings is open. */
	public Destination destination() {
		return rail.active();
	}

	private void setWaiting(int waiting) {
		if (rail == null) return;
		rail.setInboxCount(waiting);
		strip.setWaiting(waiting);
		rail.slot().setState(waiting > 0 ? StatusSlot.State.WAITING : StatusSlot.State.RESTING,
			waiting > 0 ? waiting + (waiting == 1 ? " decision" : " decisions") : null);
	}

	/** The acting identity or the venue changed: the strip, the sidebar's identity menu, and the venue chip follow. */
	private void identityChanged() {
		if (rail == null) return;
		String name = (identity != null) ? (app.actingAsOperator() ? app.operatorName() : identity.name()) : null;
		rail.setIdentity(name);
		strip.setActing(name, app.actingAsOperator());
		strip.setVenue(venue != null ? StatusStrip.Venue.RUNNING : StatusStrip.Venue.STARTING,
			venue != null ? venue.url() : null);
	}

	private void applyDensity(boolean compact) {
		brightside.ui.components.Density.set(compact);
		SwingUtilitiesRepaint.all(this);
	}

	private String myIdentity() {
		if (identity == null || venue == null || venue.did() == null) return null;
		return app.actingAsOperator() ? venue.did() : identity.userDID(venue.did());
	}

	private static String keyText(KeyStroke k) {
		return brightside.ui.shell.Keys.text(k);
	}

	/** The menu bar, from the same registry as the palette: every shortcut shows here. */
	private JMenuBar menuBar() {
		JMenuBar bar = new JMenuBar();
		for (String name : ShellCommands.MENUS) {
			JMenu menu = new JMenu(name);
			String group = null;
			for (Commands.Command c : commands.menu(name)) {
				JMenuItem item = new JMenuItem(c.name());
				if (c.key() != null) item.setAccelerator(c.key());
				item.setToolTipText(c.where());
				item.addActionListener(e -> c.action().run());
				// A rule between the destinations and the rest of View.
				String g = c.id().startsWith("go:") ? "go" : "rest";
				if (group != null && !group.equals(g)) menu.addSeparator();
				group = g;
				menu.add(item);
			}
			bar.add(menu);
		}
		return bar;
	}

	private void saveGeometry() {
		boolean max = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
		app.setUiPref("window.maximised", Boolean.toString(max));
		if (!max) {
			app.setUiPref("window.bounds", getX() + "," + getY() + "," + getWidth() + "," + getHeight());
		}
	}

	/** A remembered size and place, if it still fits a screen; else about 1200 × 800, centred. */
	private void restoreGeometry() {
		String b = app.uiPref("window.bounds", null);
		java.awt.Rectangle r = null;
		if (b != null) {
			try {
				String[] p = b.split(",");
				r = new java.awt.Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
					Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
			} catch (RuntimeException e) {
				r = null;
			}
		}
		if (r != null && onAScreen(r)) {
			setBounds(r);
		} else {
			java.awt.Rectangle screen = getGraphicsConfiguration() != null ? getGraphicsConfiguration().getBounds()
				: new java.awt.Rectangle(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
			setSize(Math.min(DEFAULT_WIDTH, screen.width), Math.min(DEFAULT_HEIGHT, screen.height));
			setLocationRelativeTo(null);
		}
		if ("true".equals(app.uiPref("window.maximised", "false"))) setExtendedState(Frame.MAXIMIZED_BOTH);
	}

	private static boolean onAScreen(java.awt.Rectangle r) {
		if (r.width < 720 || r.height < 520) return false;
		for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
			if (d.getDefaultConfiguration().getBounds().intersects(r)) return true;
		}
		return false;
	}

	/** Lists every command that has a shortcut, with where it lives. */
	private void showShortcuts() {
		StringBuilder sb = new StringBuilder();
		for (Commands.Command c : commands.all()) {
			if (c.key() != null) sb.append(keyText(c.key())).append("   ").append(c.name()).append('\n');
		}
		sb.append("\nIn the chat: Enter sends; Shift+Enter or Ctrl+Enter adds a line; Esc stops.\n");
		sb.append("Files keeps its own keys: Enter, F2, Delete, Shift+F10, Alt+Left.");
		Dialogs.info(this, "Keyboard shortcuts", new SelectableText(sb.toString()), null);
	}

	/** Repaints a window's tree after a density change. */
	private static final class SwingUtilitiesRepaint {
		static void all(java.awt.Window w) {
			javax.swing.SwingUtilities.updateComponentTreeUI(w);
		}
	}

	/** The Feed until it lands: says it is coming, and fakes nothing. */
	@SuppressWarnings("serial")
	private static final class FeedScreen extends JPanel {
		FeedScreen() {
			super(new BorderLayout());
			add(new brightside.ui.components.ScreenHeader("Feed"), BorderLayout.NORTH);
			brightside.ui.components.EmptyState empty = new brightside.ui.components.EmptyState("rss",
				brightside.ui.components.Tone.ACCENT, "Coming soon: activity from agents you follow",
				"Nothing to show here yet.");
			add(empty, BorderLayout.CENTER);
		}
	}

	/** The shell's hosts: what the sidebar, the strip, the menu bar and the palette ask of the window. */
	private final class Shell implements ShellCommands.Actions, Rail.Host, StatusStrip.Host {
		@Override public void go(Destination d) { MainWindow.this.go(d); }
		@Override public void openSettings() { selectTab(NavBar.Tab.SETTINGS); }

		@Override
		public void settings(ShellCommands.Page page) {
			SettingsScreen.Tab t = SettingsScreen.Tab.valueOf(page.name());
			// Pages that moved keep their new home active in the sidebar.
			destination = switch (t) {
				case JOBS -> Destination.JOBS;
				case SECRETS, VAULT, ACCESS -> Destination.VAULT;
				default -> destination;
			};
			showSettings(t);
		}

		@Override public void newChat() { showNewChat(); }
		@Override public void newAgent() { MainWindow.this.newAgent(); }
		@Override public void refresh() { app.refreshNow(); }
		@Override public boolean hasTray() { return app.hasTray(); }
		@Override public void hideToTray() { app.hideToTray(); }
		@Override public void lock() { app.logout(); }
		@Override public void quit() { app.exit(); }
		@Override public void about() { showAbout(); }

		@Override
		public void openPalette() {
			if (CARD_CHAT.equals(currentCard)) palette.open(getRootPane());
		}

		@Override
		public void toggleRail() {
			railCollapsedByChoice = !railCollapsedByChoice;
			app.setUiPref("rail.collapsed", Boolean.toString(railCollapsedByChoice));
			relayout();
		}

		@Override
		public void toggleChatsList() {
			boolean listHere = activeTab == NavBar.Tab.SESSIONS || (railShell && activeTab == NavBar.Tab.HOME);
			if (!listHere) {
				MainWindow.this.go(Destination.AGENTS);
				chatsListOpen = true;
				listForced = true;
			} else if (sessionsPane.isVisible()) {
				chatsListOpen = false;
				listForced = false;
				setSidebar(false);
			} else {
				chatsListOpen = true;
				listForced = true;
				setSidebar(true);
			}
			relayout();
		}

		@Override
		public void setDensity(boolean compact) {
			app.setUiPref("density", compact ? "compact" : "comfortable");
			applyDensity(compact);
		}

		@Override public void setMode(boolean dark) { app.setMode(dark ? LAF.DARK : LAF.LIGHT); }

		@Override
		public void setShell(boolean rail) {
			app.setUiPref("shell", rail ? "rail" : "classic");
			applyShell(rail);
		}

		@Override public void openDashboard() { app.openDashboard(); }
		@Override public void openSettingsFile() { app.openConfigFile(); }
		@Override public void openLogsFolder() { app.openLogsFolder(); }

		@Override
		public void copyLocalAddress() {
			if (venue != null) brightside.ui.components.Clipboard.copy(venue.url(), getRootPane());
		}

		@Override
		public void copyMyIdentity() {
			String did = myIdentity();
			if (did != null) brightside.ui.components.Clipboard.copy(did, getRootPane());
		}

		@Override public void showShortcuts() { MainWindow.this.showShortcuts(); }
		@Override public void inspect() { inspectChat(false); }
		@Override public void inspectRaw() { inspectChat(true); }

		@Override
		public void fillIdentityMenu(JPopupMenu menu) {
			JMenuItem who = new JMenuItem(identity != null ? "Signed in as " + identity.name() : "Not signed in");
			who.setEnabled(false);
			menu.add(who);
			menu.addSeparator();
			JMenuItem sw = new JMenuItem(app.actingAsOperator() ? "Switch to yourself" : "Switch to the operator");
			sw.addActionListener(e -> settings(ShellCommands.Page.PROFILE));
			sw.setToolTipText("Settings → Identity → Acting as");
			menu.add(sw);
			JMenuItem copy = new JMenuItem("Copy my identity");
			copy.setEnabled(myIdentity() != null);
			copy.addActionListener(e -> copyMyIdentity());
			menu.add(copy);
			JMenuItem lock = new JMenuItem("Lock");
			lock.addActionListener(e -> lock());
			menu.add(lock);
		}

		@Override
		public void showIdentityMenu(java.awt.Component from) {
			JPopupMenu menu = new JPopupMenu();
			fillIdentityMenu(menu);
			menu.show(from, 0, from.getHeight());
		}

		@Override public void openInbox() { MainWindow.this.go(Destination.INBOX); }
	}
}
