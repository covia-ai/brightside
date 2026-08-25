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
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
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

/**
 * The BrightSide window. Two full-window screens on a {@link CardLayout}: the
 * friendly {@link WelcomePanel} ("What should I call you?") and the chat. All
 * technical detail (the local address, the identity behind {@code u:<name>})
 * lives in the About box and the Advanced menu, never in the main flow.
 */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {

	private static final String CARD_WELCOME = "welcome";
	private static final String CARD_CHAT = "chat";

	private final BrightSide app;
	private final CardLayout cards = new CardLayout();
	private final JPanel deck = new JPanel(cards);
	private final WelcomePanel welcomePanel;
	private final ChatPanel chatPanel = new ChatPanel();
	private final JLabel whoLabel = new JLabel(" ");
	private final JLabel brandLabel = new JLabel("Powered by the Covia Grid");

	private JMenuItem changeNameItem;
	private JMenuItem dashboardItem;

	private EmbeddedVenue venue; // set once ready
	private Identity identity;
	private String currentCard;

	public MainWindow(BrightSide app, boolean startOnWelcome, String welcomePrefill) {
		super(BrightSide.APP_NAME);
		this.app = app;
		setIconImages(Icons.appIcons());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setJMenuBar(buildMenuBar());

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

		JPanel chatCard = new JPanel(new BorderLayout());
		chatCard.add(chatPanel, BorderLayout.CENTER);
		chatCard.add(buildStatusBar(), BorderLayout.SOUTH);

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

		setSize(780, 600);
		setMinimumSize(new Dimension(460, 420));
		setLocationRelativeTo(null);

		if (startOnWelcome) {
			welcomePanel.prepare(welcomePrefill, false);
			show(CARD_WELCOME);
		} else {
			chatPanel.appendSystem("Just a moment while everything starts up…");
			show(CARD_CHAT);
		}
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
		changeNameItem = item("Change my name…", null, e -> app.changeName());
		changeNameItem.setEnabled(false);
		file.add(changeNameItem);
		file.addSeparator();
		if (app.hasTray()) {
			file.add(item("Hide to tray", KeyStroke.getKeyStroke(KeyEvent.VK_H, shortcut), e -> app.hideToTray()));
		}
		file.add(item("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcut), e -> app.exit()));
		bar.add(file);

		// Everything technical lives here, out of the everyday flow.
		JMenu advanced = new JMenu("Advanced");
		advanced.setMnemonic(KeyEvent.VK_A);
		dashboardItem = item("Open dashboard in browser", null, e -> app.openDashboard());
		dashboardItem.setEnabled(false);
		advanced.add(dashboardItem);
		advanced.add(item("Open settings file", null, e -> app.openConfigFile()));
		advanced.add(item("Open logs folder", null, e -> app.openLogsFolder()));
		bar.add(advanced);

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
		updateWho();
		chatPanel.restore(history);
		chatPanel.setSession(session);
		if (history.isEmpty()) {
			chatPanel.appendSystem("Hi " + identity.name()
				+ " — I'm Brightside, ready whenever you are. Ask me anything.");
		} else {
			chatPanel.appendSystem("Welcome back, " + identity.name() + ".");
		}
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

	/** Update the transcript to the venue's live conversation (only re-renders if changed). */
	public void refreshConversation(List<SessionHistory.Item> turns) {
		chatPanel.refreshTo(turns);
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
		if (CARD_WELCOME.equals(currentCard)) {
			welcomePanel.setError("Sorry — BrightSide couldn't start up.");
		}
		chatPanel.appendError("BrightSide couldn't start up. Technical details are in the log folder"
			+ " (Advanced ▸ Open logs folder).");
	}

	private void updateWho() {
		if (identity == null) return;
		whoLabel.setText(identity.name());
		setTitle(BrightSide.APP_NAME + " — " + identity.name());
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
		JOptionPane.showMessageDialog(this, sb.toString(),
			"About " + BrightSide.APP_NAME, JOptionPane.INFORMATION_MESSAGE, new ImageIcon(Icons.icon(64)));
	}
}
