package brightside.peers;

import static brightside.peers.Node.Fact.list;
import static brightside.peers.Node.Fact.of;

import java.util.ArrayList;
import java.util.List;

/**
 * Made-up peers for the Peers preview, until venues meet venues: other venues,
 * the users on them and their agents, and a scatter of venues you don't know.
 * With {@code withHome}, a made-up home venue too, with its admin and you;
 * otherwise the home system is a live source's. DIDs use the W3C
 * {@code did:example} method, so none can be mistaken for a real one. Nothing
 * here is read from, or sent to, any network.
 */
public final class SamplePeerSource implements PeerSource {

	private static final String P = "did:example:";

	private final PeerGraph graph;

	/** The other venues alone, to stand beside a live home system. */
	public SamplePeerSource() {
		this(false);
	}

	public SamplePeerSource(boolean withHome) {
		List<Node> n = new ArrayList<>();
		if (withHome) home(n);
		others(n);
		graph = new PeerGraph(n);
	}

	@Override
	public PeerGraph graph() {
		return graph;
	}

	@Override
	public boolean sample() {
		return true;
	}

	/** A home venue: its operator and its one user, each with agents. */
	private static void home(List<Node> n) {
		n.add(node("home", "Your Brightside", Node.VENUE, Node.SELF, Node.CONNECTED, null,
			of("About", "This venue, running inside Brightside")));
		n.add(node("home#operator", "Operator", Node.PERSON, Node.OPERATOR, Node.CONNECTED, "home",
			of("Role", "Runs the venue"), of("DID", P + "home")));
		n.add(agent("home-odin", "Odin", Node.CONNECTED, "home#operator"));
		n.add(node("home:u:robin", "Robin", Node.PERSON, Node.SELF, Node.CONNECTED, "home",
			of("Role", "Owner"), of("DID", P + "home:u:robin")));
		n.add(node("home-assistant", "Brightside", Node.AGENT, Node.MAIN, Node.CONNECTED, "home:u:robin"));
		n.add(agent("home-research", "Research", Node.IDLE, "home:u:robin"));
	}

	private static void others(List<Node> n) {
		// Venues of people you trust: each its owner's, with their agents.
		n.add(venue("alice-venue", "Alice's Brightside", Node.LINKED, Node.CONNECTED, "Alice's own venue", 2, 3));
		n.add(user("alice", "Alice", Node.TRUSTED, Node.CONNECTED, "alice-venue",
			of("Address", "alice.brightside"), of("Reach", "Direct · 22 ms"), of("How you met", "Met 12 Aug, introduced by Sam"),
			list("What they can do", "Read the Household space", "Ask your assistant about free time", "Message you freely"),
			list("Shared with them", "Household", "Holiday 2027")));
		n.add(agent("alice-assistant", "Alice's assistant", Node.CONNECTED, "alice"));
		n.add(agent("alice-recipes", "Recipes", Node.IDLE, "alice"));
		n.add(user("alice-ben", "Ben", Node.KNOWN, Node.IDLE, "alice-venue", of("How you met", "Alice's partner")));
		n.add(agent("ben-assistant", "Ben's assistant", Node.IDLE, "alice-ben"));

		n.add(venue("sam-venue", "Sam's Brightside", Node.LINKED, Node.IDLE, "Sam's own venue", 1, 1));
		n.add(user("sam", "Sam", Node.TRUSTED, Node.IDLE, "sam-venue",
			of("Address", "sam.brightside"), of("Reach", "Direct · 61 ms"), of("How you met", "Met 3 Jul"),
			list("What they can do", "Read the Climbing space", "Message you freely"), list("Shared with them", "Climbing")));
		n.add(agent("sam-assistant", "Sam's assistant", Node.IDLE, "sam"));

		n.add(venue("priya-venue", "Priya's Brightside", Node.LINKED, Node.OFFLINE, "Priya's own venue, last seen 4 days ago", 1, 1));
		n.add(user("priya", "Priya", Node.TRUSTED, Node.OFFLINE, "priya-venue",
			of("Address", "priya.brightside"), of("Reach", "Last seen 4 days ago"), of("How you met", "Met 28 Sep"),
			list("What they can do", "Message you, once you reply")));
		n.add(agent("priya-assistant", "Priya's assistant", Node.OFFLINE, "priya"));

		// A service venue: its operator offers the agents you use.
		n.add(venue("transcribe-venue", "Transcribe Agents", Node.LINKED, Node.CONNECTED,
			"A service venue: transcription, speaker separation, summaries", 6, 12));
		n.add(user("transcribe-venue#operator", "Transcribe", Node.USES, Node.CONNECTED, "transcribe-venue",
			of("Address", "transcribe.agents"), of("Reach", "Direct · 35 ms"), of("How you met", "Hired 1 Oct · £0.02 a minute"),
			list("What they can do", "Take audio you send it", "Charge up to £2 a week")));
		n.add(agent("whisper", "Whisper large", Node.CONNECTED, "transcribe-venue#operator"));
		n.add(agent("speakers", "Speaker split", Node.CONNECTED, "transcribe-venue#operator"));
		n.add(agent("summary", "Summary", Node.IDLE, "transcribe-venue#operator"));

		n.add(venue("protonet", "Convex Protonet", Node.LINKED, Node.CONNECTED,
			"The public Convex network: peers that agree on shared state", 11, 0));

		// Known, not connected.
		n.add(venue("research-venue", "Research guild", Node.LISTED, Node.NOT_CONNECTED,
			"Agents that research for hire; vouched for by Alice", 8, 9));
		n.add(user("research-venue#operator", "Research guild", Node.KNOWN, Node.NOT_CONNECTED, "research-venue",
			of("Address", "research.guild"), of("Reach", "Not connected"), of("How you met", "Vouched for by Alice"),
			list("What they can do", "Nothing yet")));
		n.add(venue("review-venue", "Contract reviewers", Node.LISTED, Node.NOT_CONNECTED,
			"Legal review agents, found in the directory", 5, 6));

		// Venues nobody has introduced you to.
		for (int i = 1; i <= 8; i++) n.add(new Node(P + "venue-" + i, null, Node.VENUE, Node.UNKNOWN, Node.NOT_CONNECTED, null, List.of()));
	}

	private static Node node(String id, String name, String kind, String relation, String status, String belongsTo,
			Node.Fact... facts) {
		return new Node(P + id, name, kind, relation, status, belongsTo == null ? null : P + belongsTo, List.of(facts));
	}

	private static Node user(String id, String name, String relation, String status, String venue, Node.Fact... facts) {
		return node(id, name, Node.PERSON, relation, status, venue, facts);
	}

	private static Node agent(String id, String name, String status, String belongsTo) {
		return node(id, name, Node.AGENT, null, status, belongsTo);
	}

	private static Node venue(String id, String name, String relation, String status, String about, int users, int agents) {
		return node(id, name, Node.VENUE, relation, status, null, of("About", about), of("Users", String.valueOf(users)),
			of("Agents", String.valueOf(agents)));
	}
}
