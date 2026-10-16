package brightside.ui.settings;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * The everyday application actions, account controls and desktop
 * preferences: one row of buttons per section, each button's tooltip saying
 * what it does.
 */
@SuppressWarnings("serial")
public final class GeneralPanel extends JPanel {

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
	private final JLabel trayNote = Labels.small(" ", Styles.MUTED);
	private final JButton hideToTray;
	private final JButton dashboard;
	private boolean syncing;

	public GeneralPanel(Host host) {
		super(new BorderLayout());
		this.host = host;
		JPanel form = new JPanel(new MigLayout("insets 24 28 20 28, fillx, wrap 1", "[grow,fill]", ""));

		heading(form, "Chat", false);
		JPanel chat = buttons(form);
		newChat = action(chat, "New chat", "Open a clean Home chat; a conversation starts only when you send",
			host::newChat);
		refresh = action(chat, "Refresh conversations", "Check the current agent's conversations for changes now",
			host::refreshConversations);

		heading(form, "Account", true);
		JPanel account = buttons(form);
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
		heading(form, "System tray", true);
		form.add(keepInTray, "growx");
		form.add(minimiseToTray, "growx");
		trayNote.setVisible(false); // until refresh says the tray is missing
		form.add(trayNote, "growx, hidemode 3");
		JPanel tray = buttons(form);
		hideToTray = action(tray, "Hide to tray", "Hide this window and keep Brightside running", host::hideToTray);

		heading(form, "Advanced", true);
		JPanel advanced = buttons(form);
		dashboard = action(advanced, "Open dashboard", "The embedded venue's web dashboard, in your browser",
			host::openDashboard);
		action(advanced, "Open settings file", "Brightside's config.json, in its associated editor", host::openConfigFile);
		action(advanced, "Open logs folder", "The local log files, for diagnostics", host::openLogsFolder);
		action(advanced, "About", "Version, local address and technical identity", host::showAbout);

		heading(form, "Application", true);
		JPanel app = buttons(form);
		JButton quit = action(app, "Quit Brightside", "Save everything and stop Brightside completely", host::quit);
		Styles.classes(quit, Styles.ERROR);

		add(Scrolls.vertical(form), BorderLayout.CENTER);
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

	/** A section heading, ruled off from the section above unless it is the first. */
	private static void heading(JPanel form, String text, boolean separated) {
		JLabel heading = Labels.section(text);
		if (separated) heading.setBorder(Borders.hairlineTop());
		form.add(heading, "growx, gaptop " + (separated ? 16 : 0) + ", gapbottom 4");
	}

	/** The row a section's buttons go in. */
	private static JPanel buttons(JPanel form) {
		JPanel row = new JPanel(new MigLayout("insets 0, gap 8", "", ""));
		row.setOpaque(false);
		form.add(row, "gaptop 2");
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
