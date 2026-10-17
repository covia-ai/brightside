package brightside.ui.peers;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import brightside.peers.HomePeers;
import brightside.peers.Node;
import brightside.peers.PeerGraph;
import brightside.ui.components.Lucide;
import brightside.ui.components.Theme;

/**
 * The Peers map's right-click menu, by what was clicked: chat with your own
 * users and their agents (switching who you act as when that is needed),
 * switch user, follow a body with the view, put a moved body back, visit a
 * venue, copy a DID; and, on the sky, the views and the map's own controls.
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

	JPopupMenu build(String did) {
		JPopupMenu m = new JPopupMenu();
		Node n = (did == null) ? null : graph.get(did);
		SolarMap.Body body = (n == null) ? null : map.bodyOf(did);
		if (body == null) {
			sky(m);
			return m;
		}
		switch (body) {
			case SUN -> {
				track(m, n);
				copy(m, "Copy DID", n.did());
			}
			case STAR -> {
				m.add(item("Visit", "telescope", () -> map.visit(n.did())));
				m.addSeparator();
				track(m, n);
				putBack(m, n);
				copy(m, "Copy DID", n.did());
			}
			case PLANET -> {
				Boolean operator = principal(n);
				if (operator != null) {
					m.add(item(operator ? "Chat as " + n.label() : "Chat", "messages-square",
						() -> actions.chat(operator, null)));
					if (operator != actions.actingAsOperator()) {
						m.add(item("Switch to " + n.label(), "repeat", () -> actions.actAs(operator)));
					}
					m.addSeparator();
				}
				track(m, n);
				putBack(m, n);
				copy(m, "Copy DID", operator == Boolean.TRUE ? graph.home().did() : n.did());
			}
			case MOON -> {
				Node owner = graph.owner(n);
				Boolean operator = (owner == null) ? null : principal(owner);
				if (operator != null) {
					String as = (operator != actions.actingAsOperator()) ? " as " + owner.label() : "";
					m.add(item("Chat with " + n.label() + as, "messages-square", () -> actions.chat(operator, agentId(n))));
					m.addSeparator();
				}
				track(m, n);
				putBack(m, n);
				copy(m, operator != null ? "Copy agent id" : "Copy DID", operator != null ? agentId(n) : n.did());
			}
			case FAR_PLANET -> {
				JMenuItem message = item("Message", "messages-square", () -> {
				});
				message.setEnabled(false);
				message.setToolTipText("Arrives with federation; this is a preview");
				m.add(message);
				m.addSeparator();
				track(m, n);
				copy(m, "Copy DID", n.did());
			}
		}
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
