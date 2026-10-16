package brightside.peers;

import static brightside.peers.Node.Fact.list;
import static brightside.peers.Node.Fact.of;

import java.util.ArrayList;
import java.util.List;

/**
 * Made-up peers for the Peers preview, until venues meet venues: your
 * devices, people you trust, services you use and venues you know of, their
 * agents, the venues they live on, and a scatter of venues you don't know.
 * DIDs use the W3C {@code did:example} method, so none can be mistaken for a
 * real one. Nothing here is read from, or sent to, any network.
 */
public final class SamplePeerSource implements PeerSource {

	private static final String P = "did:example:";

	private final PeerGraph graph = new PeerGraph(nodes());

	@Override
	public PeerGraph graph() {
		return graph;
	}

	@Override
	public boolean sample() {
		return true;
	}

	private static List<Node> nodes() {
		List<Node> n = new ArrayList<>();

		// Your devices.
		n.add(node("laptop", "Laptop", Node.DEVICE, Node.SELF, Node.CONNECTED, null,
			of("Address", "this is you too"), of("Reach", "Same network · 3 ms"), of("How you met", "Linked 2 Sep"),
			list("What they can do", "Everything: it is you"), list("Shared with them", "Memory", "Conversations", "Skills")));
		n.add(node("phone", "Phone", Node.DEVICE, Node.SELF, Node.IDLE, null,
			of("Address", "this is you too"), of("Reach", "Via relay · 48 ms"), of("How you met", "Linked 19 Sep"),
			list("What they can do", "Everything: it is you"), list("Shared with them", "Memory", "Conversations")));

		// People you trust, their agents, and the venues they live on.
		n.add(node("alice", "Alice", Node.PERSON, Node.TRUSTED, Node.CONNECTED, "alice-venue",
			of("Address", "alice.brightside"), of("Reach", "Direct · 22 ms"), of("How you met", "Met 12 Aug, introduced by Sam"),
			list("What they can do", "Read the Household space", "Ask your assistant about free time", "Message you freely"),
			list("Shared with them", "Household", "Holiday 2027")));
		n.add(agent("alice-assistant", "Alice's assistant", Node.CONNECTED, "alice"));
		n.add(agent("alice-recipes", "Recipes", Node.IDLE, "alice"));
		n.add(venue("alice-venue", "Alice's Brightside", Node.LINKED, Node.CONNECTED, "Alice's own venue; you're on her People orbit", 5, 4));

		n.add(node("sam", "Sam", Node.PERSON, Node.TRUSTED, Node.IDLE, "sam-venue",
			of("Address", "sam.brightside"), of("Reach", "Direct · 61 ms"), of("How you met", "Met 3 Jul"),
			list("What they can do", "Read the Climbing space", "Message you freely"), list("Shared with them", "Climbing")));
		n.add(agent("sam-assistant", "Sam's assistant", Node.IDLE, "sam"));
		n.add(venue("sam-venue", "Sam's Brightside", Node.LINKED, Node.IDLE, "Sam's own venue", 4, 2));

		n.add(node("priya", "Priya", Node.PERSON, Node.TRUSTED, Node.OFFLINE, "priya-venue",
			of("Address", "priya.brightside"), of("Reach", "Last seen 4 days ago"), of("How you met", "Met 28 Sep"),
			list("What they can do", "Message you, once you reply")));
		n.add(agent("priya-assistant", "Priya's assistant", Node.OFFLINE, "priya"));
		n.add(venue("priya-venue", "Priya's Brightside", Node.LINKED, Node.OFFLINE, "Priya's own venue, last seen 4 days ago", 3, 1));

		// Services you use.
		n.add(node("transcriber", "Transcriber", Node.SERVICE, Node.USES, Node.CONNECTED, "transcribe-venue",
			of("Address", "transcribe.agents"), of("Reach", "Direct · 35 ms"), of("How you met", "Hired 1 Oct · £0.02 a minute"),
			list("What they can do", "Take audio you send it", "Charge up to £2 a week")));
		n.add(agent("whisper", "Whisper large", Node.CONNECTED, "transcriber"));
		n.add(agent("speakers", "Speaker split", Node.CONNECTED, "transcriber"));
		n.add(agent("summary", "Summary", Node.IDLE, "transcriber"));
		n.add(venue("transcribe-venue", "Transcribe Agents", Node.LINKED, Node.CONNECTED,
			"A service venue: transcription, speaker separation, summaries", 7, 12));

		n.add(node("convex-peer", "Convex peer", Node.SERVICE, Node.USES, Node.CONNECTED, "protonet",
			of("Address", "peer.convex.live"), of("Reach", "Direct · 90 ms"), of("How you met", "Since setup"),
			list("What they can do", "Answer queries", "Carry your signed transactions")));
		n.add(venue("protonet", "Convex Protonet", Node.LINKED, Node.CONNECTED,
			"The public Convex network: peers that agree on shared state", 11, 0));

		// Known, not connected.
		n.add(node("research-guild", "Research guild", Node.SERVICE, Node.KNOWN, Node.NOT_CONNECTED, "research-venue",
			of("Address", "research.guild"), of("Reach", "Not connected"), of("How you met", "Vouched for by Alice"),
			list("What they can do", "Nothing yet")));
		n.add(venue("research-venue", "Research guild", Node.LISTED, Node.NOT_CONNECTED,
			"Agents that research for hire; vouched for by Alice", 8, 9));
		n.add(node("contract-reviewer", "Contract reviewer", Node.PERSON, Node.KNOWN, Node.NOT_CONNECTED, "review-venue",
			of("Address", "review.legal"), of("Reach", "Not connected"), of("How you met", "Found in the directory"),
			list("What they can do", "Nothing yet")));
		n.add(venue("review-venue", "Contract reviewers", Node.LISTED, Node.NOT_CONNECTED,
			"Legal review agents, found in the directory", 5, 6));

		// Venues nobody has introduced you to.
		for (int i = 1; i <= 8; i++) n.add(new Node(P + "venue-" + i, null, Node.VENUE, Node.UNKNOWN, Node.NOT_CONNECTED, null, List.of()));
		return n;
	}

	private static Node node(String id, String name, String kind, String relation, String status, String belongsTo,
			Node.Fact... facts) {
		return new Node(P + id, name, kind, relation, status, belongsTo == null ? null : P + belongsTo, List.of(facts));
	}

	private static Node agent(String id, String name, String status, String belongsTo) {
		return node(id, name, Node.AGENT, null, status, belongsTo);
	}

	private static Node venue(String id, String name, String relation, String status, String about, int peers, int agents) {
		return node(id, name, Node.VENUE, relation, status, null, of("About", about), of("Peers", String.valueOf(peers)),
			of("Agents", String.valueOf(agents)));
	}
}
