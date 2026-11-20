package brightside.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JPopupMenu;

import org.junit.jupiter.api.Test;

import brightside.ui.shell.ShellCommands.Page;

/**
 * Nothing reachable today is lost: one test per row of the reachability
 * table. Each drives the new paths (the sidebar or Settings path, the menu
 * and the palette, which share one registry) and asserts the target the
 * window is asked to open. Headless: the window's side is a recording
 * {@link ShellCommands.Actions}, and the sidebar is built without a frame.
 */
class ReachabilityTest {

	private final List<String> calls = new ArrayList<>();
	private final Commands commands = ShellCommands.build(new Recorder());

	@Test
	void homeChat() {
		assertRuns("go:home", "go HOME");
		assertRuns("new-chat", "newChat");
		assertKey("new-chat", KeyEvent.VK_N);
		assertPalette("Home", "go:home");
		assertInMenu("new-chat", ShellCommands.FILE);
	}

	@Test
	void chatsListAndAgentsColumn() {
		assertRuns("toggle-chats", "toggleChatsList");
		assertKey("toggle-chats", KeyEvent.VK_BACK_SLASH);
		assertRuns("go:agents", "go AGENTS");
		assertPalette("Sessions", "go:agents", "toggle-chats");
		assertPalette("Chats", "toggle-chats");
		assertInMenu("toggle-chats", ShellCommands.VIEW);
	}

	@Test
	void newAgentDialog() {
		assertRuns("new-agent", "newAgent");
		assertPalette("New agent", "new-agent");
		assertInMenu("new-agent", ShellCommands.FILE);
	}

	@Test
	void sessionViewTabsAndAgentInfo() {
		// The Chat, Overview, Context and Agent tabs live with the conversation on Agents until Phase 3.
		assertPalette("Agent info", "go:agents");
	}

	@Test
	void filesSkillsPeersInbox() {
		assertRuns("go:files", "go FILES");
		assertRuns("add-folder", "go FILES");
		assertRuns("go:skills", "go SKILLS");
		assertRuns("go:peers", "go PEERS");
		assertPalette("Constellation", "go:peers");
		assertRuns("go:inbox", "go INBOX");
		assertPalette("Inbox", "go:inbox");
	}

	@Test
	void settingsShellAndEveryPage() {
		assertRuns("settings", "openSettings");
		assertKey("settings", KeyEvent.VK_COMMA);
		for (Page p : Page.values()) {
			assertRuns("settings:" + p.name().toLowerCase(), "settings " + p);
			assertPalette(p.label, "settings:" + p.name().toLowerCase());
		}
	}

	@Test
	void identityGeneralAndDeveloper() {
		assertRuns("copy-identity", "copyMyIdentity");
		assertRuns("lock", "lock");
		assertKey("lock", KeyEvent.VK_L);
		assertRuns("quit", "quit");
		assertKey("quit", KeyEvent.VK_Q);
		for (String id : List.of("dashboard", "settings-file", "logs", "copy-address", "copy-did")) {
			assertInMenu(id, ShellCommands.DEVELOPER);
		}
		assertRuns("dashboard", "openDashboard");
		assertRuns("settings-file", "openSettingsFile");
		assertRuns("logs", "openLogsFolder");
		assertRuns("copy-address", "copyLocalAddress");
		assertRuns("about", "about");
		assertInMenu("about", ShellCommands.TAU);
	}

	@Test
	void themeModelIntegrationsSchedule() {
		assertRuns("theme-dark", "setMode true");
		assertRuns("theme-light", "setMode false");
		assertPalette("Model", "settings:model");
		assertPalette("Discord", "integrations-discord");
		assertPalette("Moltbook", "integrations-moltbook");
		assertRuns("integrations-discord", "settings INTEGRATIONS");
		assertRuns("schedule", "settings SCHEDULE");
		assertInMenu("schedule", ShellCommands.FILE);
	}

	@Test
	void movedPagesAnswerToTheirOldNames() {
		assertRuns("go:jobs", "go JOBS");
		assertPalette("Jobs", "go:jobs", "settings:jobs");
		assertRuns("go:vault", "go VAULT");
		assertPalette("Secrets", "settings:secrets", "go:vault");
		assertPalette("Access", "settings:access", "go:vault");
		assertPalette("Vault", "go:vault", "settings:vault");
		assertRuns("new-access", "settings ACCESS");
	}

	@Test
	void globalShortcutsKeptAndShownInTheMenus() {
		for (String id : List.of("new-chat", "refresh", "quit")) {
			assertNotNull(commands.get(id).menu(), id + " is in a menu");
		}
		assertKey("refresh", KeyEvent.VK_R);
		assertKey("hide", KeyEvent.VK_H);
		assertKey("palette", KeyEvent.VK_K);
	}

	@Test
	void everyDestinationHasItsShortcutAndMenuItem() {
		for (Destination d : Destination.values()) {
			Commands.Command c = commands.get("go:" + d.name().toLowerCase());
			assertNotNull(c, d.name());
			assertEquals(KeyEvent.VK_0 + (d.key - '0'), c.key().getKeyCode(), d.name());
			assertEquals(ShellCommands.VIEW, c.menu());
		}
		assertEquals(Destination.values().length,
			Arrays.stream(Destination.values()).map(d -> d.key).distinct().count(), "shortcut digits are distinct");
	}

	@Test
	void everyMenuCommandIsInAKnownMenu() {
		List<String> menus = Arrays.asList(ShellCommands.MENUS);
		for (Commands.Command c : commands.all()) {
			if (c.menu() != null) assertTrue(menus.contains(c.menu()), c.id() + " in " + c.menu());
			assertFalse(c.where() == null || c.where().isBlank(), c.id() + " says where it lives");
		}
	}

	@Test
	void theSidebarHasEveryDestinationAndSettings() {
		List<String> went = new ArrayList<>();
		Rail rail = new Rail(new Rail.Host() {
			@Override public void go(Destination d) { went.add(d.name()); }
			@Override public void openSettings() { went.add("settings"); }
			@Override public void fillIdentityMenu(JPopupMenu menu) { }
		});
		for (Destination d : Destination.values()) {
			assertNotNull(rail.item(d), d.name());
			assertTrue(rail.item(d).getAccessibleContext().getAccessibleName().startsWith(d.label));
			rail.item(d).doClick();
			assertEquals(d, rail.active());
		}
		rail.settingsButton().doClick();
		assertEquals("settings", went.get(went.size() - 1));
		assertEquals(Destination.values().length + 1, went.size());
		// Collapsed, each label moves to the tooltip and the accessible name.
		rail.setCollapsed(true);
		for (Destination d : Destination.values()) {
			assertEquals(null, rail.item(d).getText());
			assertTrue(rail.item(d).getToolTipText().startsWith(d.label));
			assertTrue(rail.item(d).getAccessibleContext().getAccessibleName().startsWith(d.label));
		}
		// Public and private screens say so in words, never by colour alone.
		assertTrue(rail.item(Destination.RECORD).getAccessibleContext().getAccessibleName().contains("Private"));
		assertTrue(rail.item(Destination.FEED).getAccessibleContext().getAccessibleName().contains("Public"));
		rail.setInboxCount(3);
		assertTrue(rail.item(Destination.INBOX).getAccessibleContext().getAccessibleName().contains("3 waiting"));
	}

	@Test
	void theStatusSlotKeepsToTheGlyphOnQuietScreens() {
		StatusSlot slot = new StatusSlot();
		assertFalse(slot.showsMoreThanGlyph());
		slot.setQuietScreenOpen(true);
		assertTrue(slot.quietScreenOpen());
		assertFalse(slot.showsMoreThanGlyph());
		slot.setState(StatusSlot.State.WAITING, "1 decision");
		assertEquals("Waiting on you · 1 decision", slot.text());
		slot.setCollapsed(true);
		assertTrue(slot.getToolTipText().contains("Waiting on you · 1 decision"));
	}

	private void assertRuns(String id, String call) {
		Commands.Command c = commands.get(id);
		assertNotNull(c, "no command " + id);
		calls.clear();
		c.action().run();
		assertEquals(List.of(call), calls, id);
	}

	private void assertKey(String id, int keyCode) {
		Commands.Command c = commands.get(id);
		assertNotNull(c, id);
		assertNotNull(c.key(), id + " has a shortcut");
		assertEquals(keyCode, c.key().getKeyCode(), id);
		assertEquals(Keys.menu(), c.key().getModifiers() & Keys.menu(), id + " uses the menu shortcut key");
	}

	private void assertInMenu(String id, String menu) {
		assertEquals(menu, commands.get(id).menu(), id);
	}

	/** The palette finds {@code query} and one of {@code ids} is among the first few rows. */
	private void assertPalette(String query, String... ids) {
		List<Commands.Command> hits = commands.search(query);
		List<String> top = hits.stream().limit(5).map(Commands.Command::id).toList();
		for (String id : ids) {
			if (top.contains(id)) return;
		}
		throw new AssertionError("palette \"" + query + "\" top rows " + top + " miss " + Arrays.toString(ids));
	}

	private final class Recorder implements ShellCommands.Actions {
		@Override public void go(Destination d) { calls.add("go " + d); }
		@Override public void settings(Page page) { calls.add("settings " + page); }
		@Override public void openSettings() { calls.add("openSettings"); }
		@Override public void newChat() { calls.add("newChat"); }
		@Override public void newAgent() { calls.add("newAgent"); }
		@Override public void refresh() { calls.add("refresh"); }
		@Override public boolean hasTray() { return true; }
		@Override public void hideToTray() { calls.add("hideToTray"); }
		@Override public void lock() { calls.add("lock"); }
		@Override public void quit() { calls.add("quit"); }
		@Override public void about() { calls.add("about"); }
		@Override public void openPalette() { calls.add("openPalette"); }
		@Override public void toggleRail() { calls.add("toggleRail"); }
		@Override public void toggleChatsList() { calls.add("toggleChatsList"); }
		@Override public void setDensity(boolean compact) { calls.add("setDensity " + compact); }
		@Override public void setMode(boolean dark) { calls.add("setMode " + dark); }
		@Override public void setShell(boolean rail) { calls.add("setShell " + rail); }
		@Override public void openDashboard() { calls.add("openDashboard"); }
		@Override public void openSettingsFile() { calls.add("openSettingsFile"); }
		@Override public void openLogsFolder() { calls.add("openLogsFolder"); }
		@Override public void copyLocalAddress() { calls.add("copyLocalAddress"); }
		@Override public void copyMyIdentity() { calls.add("copyMyIdentity"); }
		@Override public void showShortcuts() { calls.add("showShortcuts"); }
	}
}
