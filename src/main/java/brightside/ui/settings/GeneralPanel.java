package brightside.ui.settings;

import java.util.stream.IntStream;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Sizes;
import brightside.StoreSpace;
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

		/** How long a pause after speaking sends the message, in seconds; 0 for only "Go" or Enter. */
		void setSendAfterPause(int seconds);

		/** Compact the data store the next time Brightside starts, or not. */
		void setCompactAtNextStart(boolean value);

		/** Delete the originals earlier repairs kept, once the owner confirms. */
		void removeKeptOriginals();

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
	/** The pauses offered, in seconds, before "never". */
	private static final int[] PAUSES = { 2, 3, 5, 8 };
	private final JComboBox<Pause> pause = new JComboBox<>();
	private final JCheckBox readAloud = new JCheckBox("Read replies aloud");
	private final JLabel readAloudNote = Labels.small(
		"Off: right-click a reply and choose Read aloud to hear just that one. Esc stops it.");
	private final JLabel storeSize = new JLabel(" ");
	private final JCheckBox compact = new JCheckBox("Compact it the next time Brightside starts");
	private final JLabel keptNote = Labels.small(" ");
	private final JPanel keptRow;
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
		pause.addActionListener(e -> {
			if (!syncing && pause.getSelectedItem() instanceof Pause p) host.setSendAfterPause(p.seconds());
		});
		group("Voice");
		addField("Send when I pause",
			"While the microphone is on, a pause this long after you speak sends what you said. Saying “Go” or pressing Enter always sends.",
			pause);
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

		compact.setOpaque(false);
		compact.setToolTipText("Starting takes a little longer that once; nothing in use is lost");
		compact.addActionListener(e -> {
			if (!syncing) host.setCompactAtNextStart(compact.isSelected());
		});
		group("Data store");
		addValueRow("On disk",
			"Your conversations, memory, skills, assistants and personal drives. The store only grows: compacting drops what it no longer refers to.",
			storeSize);
		addSpan(compact);
		addSpan(keptNote);
		keptRow = buttons();
		action(keptRow, "Remove kept files…", "Delete the files kept from earlier repairs; this can't be undone",
			host::removeKeptOriginals);
		keptNote.setVisible(false); // until refreshStore finds any
		keptRow.setVisible(false);

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

	/**
	 * Refreshes the voice preferences. A pause set by hand to one not offered is
	 * shown among them, in its place.
	 */
	public void refreshVoice(boolean readRepliesAloud, int sendAfterPause) {
		syncing = true;
		readAloud.setSelected(readRepliesAloud);
		DefaultComboBoxModel<Pause> pauses = new DefaultComboBoxModel<>();
		IntStream.concat(IntStream.of(PAUSES), IntStream.of(sendAfterPause)).filter(s -> s > 0).distinct().sorted()
			.forEach(s -> pauses.addElement(new Pause(s)));
		pauses.addElement(new Pause(0));
		pauses.setSelectedItem(new Pause(sendAfterPause));
		pause.setModel(pauses);
		syncing = false;
	}

	/**
	 * Refreshes the data store's size and whether compaction is asked for, and
	 * offers to remove the originals repairs kept when there are any. Null when
	 * the store is not a file, as in a test venue.
	 */
	public void refreshStore(StoreSpace.Report report) {
		syncing = true;
		compact.setSelected(report != null && report.compactAtNextStart());
		syncing = false;
		compact.setEnabled(report != null);
		storeSize.setText((report != null) ? Sizes.of(report.storeBytes()) : "Not kept on disk");
		int kept = (report != null) ? report.keptOriginals().size() : 0;
		if (kept > 0) {
			keptNote.setText(((kept == 1) ? "A file kept from an earlier repair takes "
				: kept + " files kept from earlier repairs take ") + Sizes.of(report.keptBytes()) + ".");
		}
		keptNote.setVisible(kept > 0);
		keptRow.setVisible(kept > 0);
	}

	/** A choice of how long a pause after speaking sends the message; 0 never does. */
	private record Pause(int seconds) {
		@Override
		public String toString() {
			if (seconds == 0) return "Never: I'll say “Go”";
			return "After " + seconds + ((seconds == 1) ? " second" : " seconds");
		}
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
