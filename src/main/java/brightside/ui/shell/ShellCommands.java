package brightside.ui.shell;

import static brightside.BrightSide.APP_NAME;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.KeyStroke;

/**
 * Everything the window can do, registered once: the menu bar, the command
 * palette, and the reachability tests all read the same {@link Commands}.
 * Moved pages answer to their old names in the palette (Secrets, Access,
 * Jobs, Schedule, Model). The window implements {@link Actions}; tests pass
 * a recording one.
 */
public final class ShellCommands {

	/** Settings pages, by the names the Settings screen uses. */
	public enum Page {
		PROFILE("Identity"), GENERAL("General"), THEME("Theme"), MODEL("Model"), INTEGRATIONS("Integrations"),
		SCHEDULE("Schedule"), JOBS("Jobs"), SECRETS("Secrets"), VAULT("Vault"), ACCESS("Access");

		public final String label;

		Page(String label) {
			this.label = label;
		}
	}

	/** What the commands do, implemented by the window. */
	public interface Actions {
		void go(Destination d);

		void settings(Page page);

		void openSettings();

		void newChat();

		void newAgent();

		void refresh();

		boolean hasTray();

		void hideToTray();

		void lock();

		void quit();

		void about();

		void openPalette();

		void toggleRail();

		void toggleChatsList();

		void setDensity(boolean compact);

		void setMode(boolean dark);

		void setShell(boolean rail);

		void openDashboard();

		void openSettingsFile();

		void openLogsFolder();

		void copyLocalAddress();

		void copyMyIdentity();

		void showShortcuts();

		/** The open chat's inspect views: Overview, Context, and Agent. */
		void inspect();

		/** The open chat's Context → Raw. */
		void inspectRaw();
	}

	public static final String TAU = APP_NAME, FILE = "File", EDIT = "Edit", VIEW = "View", DEVELOPER = "Developer",
		HELP = "Help";
	/** The menus in order. */
	public static final String[] MENUS = { TAU, FILE, EDIT, VIEW, DEVELOPER, HELP };

	private ShellCommands() {
	}

	public static Commands build(Actions a) {
		int cmd = Keys.menu();
		boolean mac = Keys.mac();
		Commands c = new Commands();
		String advanced = "Settings → General → Advanced";

		// The app.
		c.add("about", "About " + APP_NAME, advanced, TAU, null, a::about);
		c.add("settings", "Settings…", "The gear at the foot of the sidebar", TAU,
			KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, cmd), a::openSettings, "Preferences");
		c.add("lock", "Lock", "Settings → General → Account", TAU, KeyStroke.getKeyStroke(KeyEvent.VK_L, cmd), a::lock);
		if (a.hasTray()) {
			c.add("hide", "Hide to tray", "Settings → General → System tray", TAU,
				KeyStroke.getKeyStroke(KeyEvent.VK_H, cmd), a::hideToTray);
		}
		c.add("quit", "Quit " + APP_NAME, "Settings → General → Application", TAU,
			KeyStroke.getKeyStroke(KeyEvent.VK_Q, cmd), a::quit, "Exit");

		// File.
		c.add("new-chat", "New chat", "Home", FILE, KeyStroke.getKeyStroke(KeyEvent.VK_N, cmd), a::newChat,
			"New conversation");
		c.add("new-agent", "New agent", "Agents → + Agent", FILE, null, a::newAgent, "+ Agent", "Create an agent");
		c.add("schedule", "Schedule…", "Settings → Schedule", FILE, null, () -> a.settings(Page.SCHEDULE),
			"Schedules", "New event");
		c.add("add-folder", "Add a folder or drive", "Files", FILE, null, () -> a.go(Destination.FILES),
			"Personal drive", "Folder");
		c.add("refresh", "Refresh", "Everywhere", FILE, KeyStroke.getKeyStroke(KeyEvent.VK_R, cmd), a::refresh);

		// Edit.
		c.add("copy-identity", "Copy my identity", "The identity menu at the foot of the sidebar", EDIT, null,
			a::copyMyIdentity, "Copy my DID", "DID");

		// View: every destination, then the panes and looks.
		for (Destination d : Destination.values()) {
			c.add("go:" + d.name().toLowerCase(), d.label, "Sidebar", VIEW,
				KeyStroke.getKeyStroke(KeyEvent.VK_0 + (d.key - '0'), cmd), () -> a.go(d), aliases(d));
		}
		c.add("toggle-rail", "Sidebar: collapse or expand", "View", VIEW,
			KeyStroke.getKeyStroke(KeyEvent.VK_S, mac ? cmd | InputEvent.CTRL_DOWN_MASK : InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
			a::toggleRail, "Collapse sidebar", "Expand sidebar");
		c.add("toggle-chats", "Chats list", "Home and Agents", VIEW, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SLASH, cmd),
			a::toggleChatsList, "Sessions", "Conversations");
		c.add("density-comfortable", "Density: Comfortable", "Settings → Theme", VIEW, null, () -> a.setDensity(false));
		c.add("density-compact", "Density: Compact", "Settings → Theme", VIEW, null, () -> a.setDensity(true));
		c.add("theme-dark", "Theme: Dark", "Settings → Theme", VIEW, null, () -> a.setMode(true));
		c.add("theme-light", "Theme: Light", "Settings → Theme", VIEW, null, () -> a.setMode(false));
		c.add("shell-rail", "Layout: Sidebar", "Settings → Theme", VIEW, null, () -> a.setShell(true));
		c.add("shell-classic", "Layout: Bottom bar", "Settings → Theme", VIEW, null, () -> a.setShell(false),
			"Classic layout");
		c.add("inspect", "Inspect this chat", "Home → the chat's Overview, Context, and Agent tabs", VIEW,
			KeyStroke.getKeyStroke(KeyEvent.VK_I, cmd), a::inspect, "Overview", "Context", "Agent info", "Inspector");
		c.add("inspect-raw", "Show Context → Raw", "Home → the chat's Context tab", DEVELOPER,
			KeyStroke.getKeyStroke(KeyEvent.VK_I, cmd | InputEvent.SHIFT_DOWN_MASK), a::inspectRaw, "Raw");
		c.add("palette", "Command palette", "The search in the status strip", VIEW,
			KeyStroke.getKeyStroke(KeyEvent.VK_K, cmd), a::openPalette, "Search");

		// Developer: all already in Settings → General → Advanced, which stays.
		c.add("dashboard", "Open dashboard in browser", advanced, DEVELOPER, null, a::openDashboard, "Dashboard");
		c.add("settings-file", "Open settings file", advanced, DEVELOPER, null, a::openSettingsFile, "config.json");
		c.add("logs", "Open logs folder", advanced, DEVELOPER, null, a::openLogsFolder, "Logs");
		c.add("copy-address", "Copy local address", "The This computer chip in the status strip", DEVELOPER, null,
			a::copyLocalAddress, "Address", "URL");
		c.add("copy-did", "Copy my DID", "Settings → Identity → Advanced", DEVELOPER, null, a::copyMyIdentity);

		// Settings pages, by their names, including the moved ones' old names.
		for (Page p : Page.values()) {
			c.add("settings:" + p.name().toLowerCase(), "Settings: " + p.label, "Settings → " + p.label, null, null,
				() -> a.settings(p), p.label);
		}
		c.add("new-access", "New access token", "Settings → Access", null, null, () -> a.settings(Page.ACCESS),
			"Token", "MCP token");
		c.add("integrations-discord", "Discord", "Settings → Integrations", null, null,
			() -> a.settings(Page.INTEGRATIONS));
		c.add("integrations-moltbook", "Moltbook", "Settings → Integrations", null, null,
			() -> a.settings(Page.INTEGRATIONS));

		// Help.
		c.add("shortcuts", "Keyboard shortcuts", "Help", HELP, null, a::showShortcuts, "Keys");
		return c;
	}

	private static String[] aliases(Destination d) {
		return switch (d) {
			case AGENTS -> new String[] { "Roster", "Add an agent" };
			case JOBS -> new String[] { "Settings Jobs", "Tasks" };
			case VAULT -> new String[] { "Secrets", "Access", "Passphrase" };
			case PEERS -> new String[] { "Constellation", "Map" };
			case INBOX -> new String[] { "Requests", "Approvals" };
			default -> new String[0];
		};
	}
}
