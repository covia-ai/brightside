package covia.brightside.ui;

import java.awt.BorderLayout;
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

import covia.brightside.AppConfig;
import covia.brightside.BrightSide;
import covia.brightside.EmbeddedVenue;
import covia.brightside.Identity;
import covia.brightside.chat.ChatSession;

/**
 * The BrightSide window: menu bar, chat panel, status bar. Closing and
 * minimising are delegated to the application, which decides between the
 * tray and a real exit.
 */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {

	private final BrightSide app;
	private final ChatPanel chatPanel = new ChatPanel();
	private final JLabel status = new JLabel(" ");
	private JMenuItem openVenueItem;
	private JMenuItem switchUserItem;

	private EmbeddedVenue venue; // set once the venue is up
	private Identity identity;

	public MainWindow(BrightSide app) {
		super(BrightSide.APP_NAME);
		this.app = app;
		setIconImages(Icons.appIcons());
		// Closing is the app's decision: to the tray when there is one, else exit.
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setJMenuBar(buildMenuBar());
		add(chatPanel, BorderLayout.CENTER);
		add(buildStatusBar(), BorderLayout.SOUTH);
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
		setSize(760, 580);
		setMinimumSize(new Dimension(440, 340));
		setLocationRelativeTo(null);
		chatPanel.appendSystem("Starting the embedded Covia venue…");
	}

	private JMenuBar buildMenuBar() {
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		JMenuBar bar = new JMenuBar();

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(item("New conversation", KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), e -> app.newConversation()));
		switchUserItem = item("Switch user…", null, e -> app.switchUser());
		switchUserItem.setEnabled(false);
		file.add(switchUserItem);
		file.addSeparator();
		openVenueItem = item("Open venue in browser", null, e -> app.openVenueInBrowser());
		openVenueItem.setEnabled(false);
		file.add(openVenueItem);
		file.add(item("Open configuration file", null, e -> app.openConfigFile()));
		file.addSeparator();
		if (app.hasTray()) {
			file.add(item("Hide to tray", KeyStroke.getKeyStroke(KeyEvent.VK_H, shortcut), e -> app.hideToTray()));
		}
		file.add(item("Exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcut), e -> app.exit()));
		bar.add(file);

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
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, (line != null) ? line : Color.GRAY),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		status.putClientProperty("FlatLaf.styleClass", "small");
		bar.add(status, BorderLayout.CENTER);
		return bar;
	}

	public void setStatus(String text) {
		status.setText(text);
	}

	public void showSystemMessage(String text) {
		chatPanel.appendSystem(text);
	}

	/** The venue is up: enable the venue actions and connect the first chat. */
	public void venueReady(EmbeddedVenue venue, ChatSession session, Identity identity) {
		this.venue = venue;
		this.identity = identity;
		openVenueItem.setEnabled(true);
		switchUserItem.setEnabled(true);
		updateStatus();
		chatPanel.setSession(session);
		chatPanel.appendSystem("Venue ready at " + venue.url() + ". You are " + identity.label()
			+ ", chatting with agent '" + session.config().agentId() + "' via " + session.config().llmOperation() + ".");
	}

	/** The acting user changed: rebind the chat and note it. */
	public void userChanged(ChatSession session, Identity identity) {
		this.identity = identity;
		updateStatus();
		chatPanel.setSession(session);
		chatPanel.appendSystem("Now chatting as " + identity.label() + ".");
	}

	private void updateStatus() {
		if (venue == null) return;
		String who = (identity != null) ? identity.label() + "   ·   " : "";
		setStatus(who + venue.name() + "   ·   " + venue.url() + "   ·   " + venue.did());
		setTitle((identity != null) ? BrightSide.APP_NAME + " — " + identity.label() : BrightSide.APP_NAME);
	}

	public void venueFailed(Throwable t) {
		setStatus("Venue failed to start");
		chatPanel.appendError("The embedded venue failed to start: " + t
			+ "\nSee the log in " + AppConfig.HOME.resolve("logs") + " for details.");
	}

	/** Bring the window back from the tray or from behind other windows. */
	public void showAndFocus() {
		setVisible(true);
		setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
		toFront();
		requestFocus();
		chatPanel.focusInput();
	}

	private void showAbout() {
		JOptionPane.showMessageDialog(this,
			BrightSide.APP_NAME + "\nA Covia desktop companion: an embedded Covia venue with a chat window.\n\nhttps://covia.ai",
			"About " + BrightSide.APP_NAME, JOptionPane.INFORMATION_MESSAGE, new ImageIcon(Icons.icon(64)));
	}
}
