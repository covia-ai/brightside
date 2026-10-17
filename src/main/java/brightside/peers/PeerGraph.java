package brightside.peers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a source knows about other nodes at one moment: an immutable
 * snapshot, looked up by DID. Read-only to the map; how the map draws it is
 * {@link MapSettings}', held apart.
 */
public final class PeerGraph {

	public static final PeerGraph EMPTY = new PeerGraph(List.of());

	private final Map<String, Node> byDid = new LinkedHashMap<>();

	/** A later node with the same DID replaces an earlier one. */
	public PeerGraph(List<Node> nodes) {
		for (Node n : nodes) byDid.put(n.did(), n);
	}

	public List<Node> nodes() {
		return List.copyOf(byDid.values());
	}

	public Node get(String did) {
		return (did == null) ? null : byDid.get(did);
	}

	/** The nodes that belong to {@code did}: a person's or venue's agents, say. */
	public List<Node> partsOf(String did) {
		List<Node> out = new ArrayList<>();
		for (Node n : byDid.values()) if (did.equals(n.belongsTo())) out.add(n);
		return out;
	}

	/** Your own venue, the sun of the map: the venue whose relation is {@link Node#SELF}, or null. */
	public Node home() {
		for (Node n : byDid.values()) if (n.is(Node.VENUE) && Node.SELF.equals(n.relation())) return n;
		return null;
	}

	/** The node {@code n} belongs to, if the graph has it. */
	public Node owner(Node n) {
		return get(n.belongsTo());
	}

	public int size() {
		return byDid.size();
	}
}
