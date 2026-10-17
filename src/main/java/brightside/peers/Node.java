package brightside.peers;

import java.util.List;

/**
 * Another participant as a source reports it: a venue, a user on a venue, an
 * agent of a user. Deliberately little is assumed, because the network's
 * records will change: the DID is the one stable key, and everything a card
 * might show is an open list of {@link Fact}s.
 *
 * <p>{@code kind}, {@code relation} and {@code status} are open strings;
 * the constants below are the ones Brightside draws specially, and anything
 * else is shown plainly. {@code belongsTo} is the DID of the node this one is
 * part of (a user's venue, an agent's user), or null.
 *
 * <p>The key need not be a bare DID: a part of a principal the network knows
 * by one DID, such as a venue's operator, which acts as the venue itself, is
 * keyed by a DID URL under it ({@code <venueDID>#operator}).
 */
public record Node(String did, String name, String kind, String relation, String status, String belongsTo,
		List<Fact> facts) {

	/** Kinds: venues are stars, users are planets, agents are moons. */
	public static final String VENUE = "venue", PERSON = "person", AGENT = "agent";

	/**
	 * Relations to you. Your own venue is {@code self}; on it, you are
	 * {@code self}, its operator {@code operator} and anyone else a
	 * {@code member}. Elsewhere, users are people you trust, services you use
	 * or known; venues are linked to you, listed in a directory, or unknown. An
	 * agent has no relation, except your main assistant, which is {@code main}.
	 */
	public static final String SELF = "self", OPERATOR = "operator", MEMBER = "member", TRUSTED = "trusted",
		USES = "uses", KNOWN = "known", LINKED = "linked", LISTED = "listed", UNKNOWN = "unknown", MAIN = "main";

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
