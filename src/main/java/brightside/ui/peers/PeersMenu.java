package brightside.ui.peers;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import brightside.peers.HomePeers;
import brightside.peers.MapLayout;
import brightside.peers.Node;
import brightside.peers.PeerGraph;
import brightside.ui.components.Lucide;
import brightside.ui.components.Theme;

/**
 * The Peers map's right-click menu, by what was clicked: chat with your own
 * users and their agents (switching who you act as when that is needed),
 * switch user, visit a body with others round it, follow a body with the
 * view, put a moved body back, copy a DID; and, on the sky, the views and
 * the map's own controls.
 */
final class PeersMenu {

	private final SolarMap map;
	private final PeerGraph graph;
	private final PeersScreen.Actions actions;

	PeersMenu(SolarMap map, PeerGraph graph, PeersScreen.Actions actions) {
		this.map = map;
		this.graph = graph;
		this.actions = actions;
	}

	/** The planet of whoever you are acting as on your venue: its operator, or the named user; null if not drawn. */
	static String actingPlanet(PeerGraph graph, boolean operator) {
		Node home = graph.home();
		if (home == null) return null;
		String relation = operator ? Node.OPERATOR : Node.SELF;
		for (Node n : graph.partsOf(home.did())) if (relation.equals(n.relation())) return n.did();
		return null;
	}

	/**
	 * The menu for {@code did}, or for the sky when null. What it offers
	 * follows from the data, not from where the body sits: chat as one of your
	 * own users or with one of their agents, visit anything with bodies round
	 * it, and track, put back or copy anything.
	 */
	JPopupMenu build(String did) {
		JPopupMenu m = new JPopupMenu();
		MapLayout.Body b = (did == null) ? null : map.body(did);
		if (b == null) {
			sky(m);
			return m;
		}
		Node n = b.node;
		Node owner = graph.owner(n);
		Boolean operator = principal(n), ownerOperator = (owner == null) ? null : principal(owner);
		boolean any = false;
		if (operator != null) {
			// One of your own users: chat as it, or become it.
			m.add(item(operator ? "Chat as " + n.label() : "Chat", "messages-square", () -> actions.chat(operator, null)));
			if (operator != actions.actingAsOperator()) {
				m.add(item("Switch to " + n.label(), "repeat", () -> actions.actAs(operator)));
			}
			any = true;
		} else if (ownerOperator != null && n.is(Node.AGENT)) {
			// One of their agents: chat with it, as whoever owns it.
			String as = (ownerOperator != actions.actingAsOperator()) ? " as " + owner.label() : "";
			m.add(item("Chat with " + n.label() + as, "messages-square", () -> actions.chat(ownerOperator, agentId(n))));
			any = true;
		} else if (n.is(Node.PERSON)) {
			JMenuItem message = item("Message", "messages-square", () -> {
			});
			message.setEnabled(false);
			message.setToolTipText("Arrives with federation; this is a preview");
			m.add(message);
			any = true;
		}
		if (!b.children.isEmpty()) {
			m.add(item("Visit", "telescope", () -> map.visit(n.did())));
			any = true;
		}
		if (any) m.addSeparator();
		track(m, n);
		putBack(m, n);
		if (ownerOperator != null && n.is(Node.AGENT)) copy(m, "Copy agent id", agentId(n));
		else copy(m, "Copy DID", operator == Boolean.TRUE ? owner.did() : n.did());
		return m;
	}

	private void sky(JPopupMenu m) {
		m.add(item("Home", "house", map::home));
		m.add(item("Whole universe", "telescope", map::universe));
		String you = map.acting();
		if (map.tracking() != null) {
			m.add(item("Stop tracking", "orbit", () -> map.track(null)));
		} else if (you != null) {
			m.add(item("Track your planet", "orbit", () -> map.track(you)));
		}
		m.addSeparator();
		m.add(item(map.isPaused() ? "Set turning" : "Hold still", map.isPaused() ? "play" : "circle-pause",
			map::togglePaused));
		JMenuItem reset = item("Reset layout", "rotate-ccw", map::resetLayout);
		reset.setEnabled(map.rearranged());
		m.add(reset);
	}

	/**
	 * For a user on your own venue, which principal it is: true for the
	 * operator, false for the named user; null for anyone else.
	 */
	private Boolean principal(Node n) {
		Node home = graph.home();
		if (home == null || !home.did().equals(n.belongsTo())) return null;
		if (Node.OPERATOR.equals(n.relation())) return true;
		if (Node.SELF.equals(n.relation())) return false;
		return null;
	}

	/** An agent's id from its key, {@code <ownerDID>/g/<id>} ({@link HomePeers}). */
	private static String agentId(Node agent) {
		int at = agent.did().lastIndexOf("/g/");
		return (at < 0) ? agent.did() : agent.did().substring(at + 3);
	}

	private void track(JPopupMenu m, Node n) {
		JCheckBoxMenuItem t = new JCheckBoxMenuItem("Track", n.did().equals(map.tracking()));
		t.setIcon(Lucide.icon("orbit", 14, Theme::muted));
		t.setToolTipText("Keep " + n.label() + " in view as it moves; pan to hold it somewhere else");
		t.addActionListener(e -> map.track(t.isSelected() ? n.did() : null));
		m.add(t);
	}

	private void putBack(JPopupMenu m, Node n) {
		if (!map.placed(n.did())) return;
		m.add(item("Put back", "rotate-ccw", () -> map.unplace(n.did())));
	}

	private static void copy(JPopupMenu m, String label, String text) {
		m.add(item(label, "copy",
			() -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null)));
	}

	private static JMenuItem item(String text, String icon, Runnable action) {
		JMenuItem i = new JMenuItem(text, Lucide.icon(icon, 14, Theme::muted));
		i.addActionListener(e -> action.run());
		return i;
	}
}
