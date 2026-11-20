package brightside.ui.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.junit.jupiter.api.Test;

import brightside.ui.agents.AgentsScreen.Kind;
import brightside.ui.agents.AgentsScreen.Row;

/**
 * The Agents roster keeps every action today's agents column has (Open,
 * Agent info, Delete… except for the standard agent), offers every source an
 * agent can come from with the network ones marked as coming, and gives
 * outside agents their own actions. Headless: no window.
 */
class AgentsScreenTest {

	private final List<String> calls = new ArrayList<>();
	private final AgentsScreen screen = new AgentsScreen(new AgentsScreen.Host() {
		@Override public void open(Row a) { calls.add("open " + a.id()); }
		@Override public void info(Row a) { calls.add("info " + a.id()); }
		@Override public void delete(Row a) { calls.add("delete " + a.id()); }
		@Override public void createHere() { calls.add("create"); }
		@Override public void connectOutside() { calls.add("outside"); }
		@Override public void manageAccess(Row a) { calls.add("manage " + a.id()); }
		@Override public void copy(String text) { calls.add("copy " + text); }
	});

	private final Row tau = new Row("Tau", "Tau", Kind.YOURS, "This computer", "Ready", "Acts for you", null);
	private final Row scribe = new Row("scribe", "Scribe", Kind.YOURS, "This computer", "Ready", "Acts for you", null);
	private final Row relay = new Row("relay", "relay", Kind.OUTSIDE_MCP, "A local client", "Token active", "Read only", null);

	@Test
	void everyAgentShowsAndTheTabsFilter() {
		screen.setRows(List.of(tau, scribe, relay), "Tau");
		assertEquals(3, screen.visibleRows().size());
	}

	@Test
	void todaysAgentActionsAreKept() {
		screen.setRows(List.of(tau, scribe, relay), "Tau");
		JPopupMenu menu = screen.menuFor(scribe);
		assertEquals(List.of("Open", "Agent info", "Copy id", "Delete…"), labels(menu));
		assertFalse(item(screen.menuFor(tau), "Delete…").isEnabled(), "the standard agent can't be deleted");
		item(menu, "Open").doClick();
		item(menu, "Agent info").doClick();
		assertEquals(List.of("open scribe", "info scribe"), calls);
	}

	@Test
	void outsideAgentsAreManagedThroughTheirAccess() {
		screen.setRows(List.of(tau, relay), "Tau");
		JPopupMenu menu = screen.menuFor(relay);
		assertEquals(List.of("Manage its access", "Copy id"), labels(menu));
		item(menu, "Manage its access").doClick();
		assertEquals(List.of("manage relay"), calls);
	}

	@Test
	void addAnAgentOffersEverySourceAndMarksTheComingOnes() {
		JPopupMenu menu = screen.addMenu();
		List<String> l = labels(menu);
		assertEquals(4, l.size());
		assertTrue(item(menu, "Create here").isEnabled());
		assertTrue(l.get(1).startsWith("Outside · MCP"));
		assertFalse(((JMenuItem) menu.getComponent(2)).isEnabled());
		assertTrue(l.get(2).contains("coming"));
		assertFalse(((JMenuItem) menu.getComponent(3)).isEnabled());
		item(menu, "Create here").doClick();
		((JMenuItem) menu.getComponent(1)).doClick();
		assertEquals(List.of("create", "outside"), calls);
	}

	@Test
	void agentsWithAnIdentityShowAFingerprintAndCanCopyIt() {
		String did = "did:key:z6MkSample/g/scribe";
		Row withId = new Row("scribe", "Scribe", Kind.YOURS, "This computer", "Ready", "Acts for you", null, did);
		String fp = brightside.ui.components.IdentityMark.fingerprint(did);
		assertTrue(fp.matches("[0-9a-f]{4}…[0-9a-f]{3}"), fp);
		assertEquals(fp, brightside.ui.components.IdentityMark.fingerprint(did), "the same identity, the same fingerprint");
		assertTrue(!fp.equals(brightside.ui.components.IdentityMark.fingerprint(did + "x")));
		screen.setRows(List.of(withId), "Tau");
		item(screen.menuFor(withId), "Copy Agent ID (DID)").doClick();
		assertEquals(List.of("copy " + did), calls);
	}

	private static List<String> labels(JPopupMenu menu) {
		List<String> out = new ArrayList<>();
		for (var c : menu.getComponents()) if (c instanceof JMenuItem i) out.add(i.getText());
		return out;
	}

	private static JMenuItem item(JPopupMenu menu, String text) {
		for (var c : menu.getComponents()) if (c instanceof JMenuItem i && i.getText().equals(text)) return i;
		throw new AssertionError("no item " + text);
	}
}
