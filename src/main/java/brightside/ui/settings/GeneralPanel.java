package brightside.ui.settings;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * The everyday application actions, account controls and desktop
 * preferences, a card per section: a row of buttons in each, each button's
 * tooltip saying what it does. Nothing to save, so no action bar.
 */
@SuppressWarnings("serial")
public final class GeneralPanel extends SettingsPage {

	/** Actions supplied by the application window/controller. */
	public interface Host {
		void newChat();

		void refreshConversations();

		void changeName();

		void logout();

		void setKeepInTray(boolean value);

		void setMinimiseToTray(boolean value);

		void hideToTray();

		void openDashboard();

		void openConfigFile();

		void openLogsFolder();

		void showAbout();

		void quit();
	}

	private final Host host;
	private final JButton newChat;
	private final JButton refresh;
	private final JButton changeName;
	private final JButton logout;
	private final JCheckBox keepInTray = new JCheckBox("Keep running in the tray when I close the window");
	private final JCheckBox minimiseToTray = new JCheckBox("Send Brightside to the tray when minimised");
	private final JLabel trayNote = Labels.small(" ");
	private final JButton hideToTray;
	private final JButton dashboard;
	private boolean syncing;

	public GeneralPanel(Host host) {
		super(null);
		this.host = host;

		group("Chat");
		JPanel chat = buttons();
		newChat = action(chat, "New chat", "Open a clean Home chat; a conversation starts only when you send",
			host::newChat);
		refresh = action(chat, "Refresh conversations", "Check the current agent's conversations for changes now",
			host::refreshConversations);

		group("Account");
		JPanel account = buttons();
		changeName = action(account, "Change name…", "Change how Brightside addresses you; your identity stays the same",
			host::changeName);
		logout = action(account, "Lock", "Lock Brightside and ask for the passphrase again; it keeps running meanwhile",
			host::logout);

		keepInTray.setOpaque(false);
		minimiseToTray.setOpaque(false);
		keepInTray.addActionListener(e -> {
			if (!syncing) host.setKeepInTray(keepInTray.isSelected());
		});
		minimiseToTray.addActionListener(e -> {
			if (!syncing) host.setMinimiseToTray(minimiseToTray.isSelected());
		});
		group("System tray");
		addSpan(keepInTray);
		addSpan(minimiseToTray);
		trayNote.setVisible(false); // until refresh says the tray is missing
		addSpan(trayNote);
		JPanel tray = buttons();
		hideToTray = action(tray, "Hide to tray", "Hide this window and keep Brightside running", host::hideToTray);

		group("Advanced");
		JPanel advanced = buttons();
		dashboard = action(advanced, "Open dashboard", "The embedded venue's web dashboard, in your browser",
			host::openDashboard);
		action(advanced, "Open settings file", "Brightside's config.json, in its associated editor", host::openConfigFile);
		action(advanced, "Open logs folder", "The local log files, for diagnostics", host::openLogsFolder);
		action(advanced, "About", "Version, local address and technical identity", host::showAbout);

		group("Application");
		JPanel app = buttons();
		JButton quit = action(app, "Quit Brightside", "Save everything and stop Brightside completely", host::quit);
		Styles.classes(quit, Styles.ERROR);
	}

	/** Refreshes enablement and preferences whenever Settings becomes visible. */
	public void refresh(boolean trayAvailable, boolean keepOpen, boolean minimise,
			boolean loggedIn, boolean venueReady) {
		syncing = true;
		keepInTray.setSelected(keepOpen);
		minimiseToTray.setSelected(minimise);
		syncing = false;

		newChat.setEnabled(loggedIn);
		refresh.setEnabled(loggedIn);
		changeName.setEnabled(loggedIn);
		logout.setEnabled(loggedIn);
		dashboard.setEnabled(venueReady);
		keepInTray.setEnabled(trayAvailable);
		minimiseToTray.setEnabled(trayAvailable);
		hideToTray.setEnabled(trayAvailable);
		// Says why the tray controls are disabled; nothing to say when they work.
		trayNote.setText("The system tray isn't available on this desktop.");
		trayNote.setVisible(!trayAvailable);
	}

	/** The row the current section's buttons go in. */
	private JPanel buttons() {
		JPanel row = new JPanel(new MigLayout("insets 0, gap 8", "", ""));
		row.setOpaque(false);
		addSpanLeft(row);
		return row;
	}

	private static JButton action(JPanel row, String text, String tooltip, Runnable action) {
		JButton button = Buttons.plain(text);
		button.setToolTipText(tooltip);
		button.addActionListener(e -> action.run());
		row.add(button);
		return button;
	}
}
