package brightside.peers;

import java.util.ArrayList;
import java.util.List;

import brightside.peers.Node.Fact;

/**
 * Your own venue as peer data: the venue, the sun; its operator, the admin;
 * you; and each one's agents. Built from what the embedded venue already
 * knows, so the home system is live while other venues are still samples.
 *
 * <p>The operator acts as the venue itself and has the venue's DID, which
 * the venue's own node already holds, so the admin's planet is keyed by a
 * DID URL under it, {@code <venueDID>#operator}, and gives the real DID as a
 * fact. Agents are keyed by their owner's DID and id, {@code <ownerDID>/g/<id>}.
 */
public final class HomePeers {

	/** The admin's key under the venue's DID. */
	public static final String OPERATOR_FRAGMENT = "#operator";

	/** An agent as the venue lists it: its id, its display name, and its run state. */
	public record Agent(String id, String name, String state) {
	}

	private HomePeers() {
	}

	/**
	 * @param operatorAgents the operator's agents, Odin among them
	 * @param userDID        your DID on the venue, or null before it exists
	 * @param mainAgent      the id of your main assistant among {@code userAgents}, or null
	 */
	public static List<Node> of(String venueDID, String venueName, String operatorName, List<Agent> operatorAgents,
			String userDID, String userName, List<Agent> userAgents, String mainAgent) {
		List<Node> n = new ArrayList<>();
		n.add(new Node(venueDID, venueName, Node.VENUE, Node.SELF, Node.CONNECTED, null,
			List.of(Fact.of("About", "This venue, running inside Brightside"))));
		String admin = venueDID + OPERATOR_FRAGMENT;
		n.add(new Node(admin, operatorName, Node.PERSON, Node.OPERATOR, Node.CONNECTED, venueDID,
			List.of(Fact.of("Role", "Runs the venue"), Fact.of("DID", venueDID))));
		agents(n, venueDID, admin, operatorAgents, null);
		if (userDID != null) {
			n.add(new Node(userDID, userName, Node.PERSON, Node.SELF, Node.CONNECTED, venueDID,
				List.of(Fact.of("Role", "Owner"), Fact.of("DID", userDID))));
			agents(n, userDID, userDID, userAgents, mainAgent);
		}
		return n;
	}

	private static void agents(List<Node> n, String ownerDID, String planet, List<Agent> agents, String main) {
		for (Agent a : agents) {
			String relation = a.id().equals(main) ? Node.MAIN : null;
			n.add(new Node(ownerDID + "/g/" + a.id(), a.name(), Node.AGENT, relation, status(a.state()), planet,
				List.of(Fact.of("Id", a.id()))));
		}
	}

	/** An agent's run state as a status: running is connected, asleep idle, suspended offline. */
	static String status(String state) {
		if (state == null) return Node.IDLE;
		return switch (state) {
			case "RUNNING" -> Node.CONNECTED;
			case "SUSPENDED", "TERMINATED" -> Node.OFFLINE;
			default -> Node.IDLE;
		};
	}
}
