package brightside.peers;

import java.util.List;

/**
 * Another participant as a source reports it: a venue, an agent, one of your
 * own devices, a service. Deliberately little is assumed, because the
 * network's records will change: the DID is the one stable key, and
 * everything a card might show is an open list of {@link Fact}s.
 *
 * <p>{@code kind}, {@code relation} and {@code status} are open strings;
 * the constants below are the ones Brightside draws specially, and anything
 * else is shown plainly. {@code belongsTo} is the DID of the node this one is
 * part of (an agent's venue or person), or null.
 */
public record Node(String did, String name, String kind, String relation, String status, String belongsTo,
		List<Fact> facts) {

	/** Kinds. */
	public static final String VENUE = "venue", PERSON = "person", AGENT = "agent", DEVICE = "device",
		SERVICE = "service";

	/** Relations to you: the orbits by default, then those drawn far out. */
	public static final String SELF = "self", TRUSTED = "trusted", USES = "uses", KNOWN = "known",
		LINKED = "linked", LISTED = "listed", UNKNOWN = "unknown";

	/** Statuses. */
	public static final String CONNECTED = "connected", IDLE = "idle", OFFLINE = "offline",
		NOT_CONNECTED = "not-connected";

	public Node {
		if (did == null || did.isBlank()) throw new IllegalArgumentException("A node needs a DID");
		facts = (facts == null) ? List.of() : List.copyOf(facts);
	}

	/** The name to show: the node's own, else its DID. */
	public String label() {
		return (name != null && !name.isBlank()) ? name : did;
	}

	public boolean is(String kind) {
		return kind.equals(this.kind);
	}

	/**
	 * One thing a card can say about a node: a labelled value, or, for a
	 * {@code section}, a labelled list such as what the node may do.
	 */
	public record Fact(String label, List<String> values, boolean section) {

		public Fact {
			values = (values == null) ? List.of() : List.copyOf(values);
		}

		public static Fact of(String label, String value) {
			return new Fact(label, List.of(value), false);
		}

		public static Fact list(String label, String... values) {
			return new Fact(label, List.of(values), true);
		}

		/** The single value of a plain fact, or the items joined. */
		public String value() {
			return String.join(", ", values);
		}
	}
}
