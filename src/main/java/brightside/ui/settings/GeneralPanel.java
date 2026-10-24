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

		void setTakeOverWithoutAsking(boolean value);

		void setUnlockWithoutAsking(boolean value);

		/** Read each reply aloud, or not; turning it on may ask to download the voice first. */
		void setReadRepliesAloud(boolean value);

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
	private final JCheckBox takeOver = new JCheckBox("Take over a Brightside that is already running, without asking");
	private final JCheckBox unlock = new JCheckBox("Unlock with my stored passphrase, without asking");
	private final JLabel unlockNote = Labels.small("Store your passphrase on the unlock screen to use this.");
	private final JCheckBox readAloud = new JCheckBox("Read replies aloud");
	private final JLabel readAloudNote = Labels.small(
		"Off: right-click a reply and choose Read aloud to hear just that one. Esc stops it.");
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

		takeOver.setOpaque(false);
		unlock.setOpaque(false);
		takeOver.setToolTipText("At launch, it hands over to this one; nothing is lost");
		unlock.setToolTipText("At launch only: Lock still asks for the passphrase");
		takeOver.addActionListener(e -> {
			if (!syncing) host.setTakeOverWithoutAsking(takeOver.isSelected());
		});
		unlock.addActionListener(e -> {
			if (!syncing) host.setUnlockWithoutAsking(unlock.isSelected());
		});
		group("Startup");
		addSpan(takeOver);
		addSpan(unlock);
		unlockNote.setVisible(false); // until refreshStartup says nothing is stored
		addSpan(unlockNote);

		readAloud.setOpaque(false);
		readAloud.setToolTipText("Spoken on this computer: nothing it says leaves it");
		readAloud.addActionListener(e -> {
			if (!syncing) host.setReadRepliesAloud(readAloud.isSelected());
		});
		group("Voice");
		addSpan(readAloud);
		addSpan(readAloudNote);

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

	/**
	 * Refreshes the startup preferences. Unlocking without asking needs a
	 * passphrase stored on this computer; without one it is off and says why.
	 */
	public void refreshStartup(boolean takeOverWithoutAsking, boolean unlockWithoutAsking, boolean passphraseStored) {
		syncing = true;
		takeOver.setSelected(takeOverWithoutAsking);
		unlock.setSelected(passphraseStored && unlockWithoutAsking);
		syncing = false;
		unlock.setEnabled(passphraseStored);
		unlockNote.setVisible(!passphraseStored);
	}

	/** Refreshes the voice preferences. */
	public void refreshVoice(boolean readRepliesAloud) {
		syncing = true;
		readAloud.setSelected(readRepliesAloud);
		syncing = false;
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
