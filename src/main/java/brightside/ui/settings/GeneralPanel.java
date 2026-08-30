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
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/** The everyday application actions, account controls and desktop preferences. */
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
	private final JLabel trayNote = Labels.small(" ");
	private final JButton hideToTray;
	private final JButton dashboard;
	private boolean syncing;

	public GeneralPanel(Host host) {
		super(new BorderLayout());
		this.host = host;
		JPanel form = new JPanel(new MigLayout(
			"insets 24 28 20 28, fillx, wrap 2", "[grow,fill]16[]", ""));

		form.add(SelectableText.description("Everyday application controls, desktop behaviour and advanced local tools."),
			"span 2, growx, wmin 0, gapbottom 14");

		addSection(form, "Chat", false);
		newChat = addAction(form, "New chat", "Open a clean Home chat. A session starts only after you send.",
			"New chat", host::newChat);
		refresh = addAction(form, "Refresh conversations", "Check the current agent record for changes now.",
			"Refresh", host::refreshConversations);

		addSection(form, "Account", true);
		changeName = addAction(form, "Your name", "Change how Brightside addresses you without changing your identity.",
			"Change name…", host::changeName);
		logout = addAction(form, "Lock Brightside", "Log out this user while the local Brightside service keeps running.",
			"Log out", host::logout);

		addSection(form, "System tray", true);
		keepInTray.setOpaque(false);
		minimiseToTray.setOpaque(false);
		keepInTray.addActionListener(e -> {
			if (!syncing) host.setKeepInTray(keepInTray.isSelected());
		});
		minimiseToTray.addActionListener(e -> {
			if (!syncing) host.setMinimiseToTray(minimiseToTray.isSelected());
		});
		form.add(keepInTray, "span 2, growx");
		form.add(minimiseToTray, "span 2, growx");
		form.add(trayNote, "span 2, growx, gapbottom 4");
		hideToTray = addAction(form, "Hide now", "Keep Brightside running and hide this window.",
			"Hide to tray", host::hideToTray);

		addSection(form, "Advanced", true);
		dashboard = addAction(form, "Local dashboard", "Open the embedded venue dashboard in your browser.",
			"Open dashboard", host::openDashboard);
		addAction(form, "Configuration", "Open Brightside's local settings file in its associated editor.",
			"Open settings file", host::openConfigFile);
		addAction(form, "Logs", "Open the local log folder for diagnostics.",
			"Open logs folder", host::openLogsFolder);
		addAction(form, "About Brightside", "Version, local endpoint and technical identity information.",
			"About", host::showAbout);

		addSection(form, "Application", true);
		JButton quit = addAction(form, "Quit Brightside", "Flush local state and stop Brightside completely.",
			"Quit", host::quit);
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
		trayNote.setText(trayAvailable ? " " : "The system tray isn't available on this desktop.");
	}

	private static void addSection(JPanel form, String text, boolean separated) {
		JLabel heading = Labels.section(text);
		if (separated) heading.setBorder(Borders.hairlineTop());
		form.add(heading, "span 2, growx, gaptop " + (separated ? 14 : 0) + ", gapbottom 6");
	}

	private static JButton addAction(JPanel form, String title, String detail, String buttonText, Runnable action) {
		JPanel copy = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", ""));
		copy.setOpaque(false);
		copy.add(Labels.heading(title), "growx");
		copy.add(SelectableText.description(detail).small(), "growx");

		JButton button = Buttons.plain(buttonText);
		button.addActionListener(e -> action.run());
		form.add(copy, "growx, wmin 0, gaptop 3, gapbottom 5");
		form.add(button, "aligny center, wmin 120, gapbottom 5");
		return button;
	}
}
