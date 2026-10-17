package brightside.peers;

import java.util.List;
import java.util.function.Supplier;

/**
 * Peer data read on demand: {@link #refresh()} asks {@code read} afresh, and
 * {@link #graph()} answers from the last read, so the map can call it as often
 * as it likes. A read that fails leaves the last answer standing.
 */
public final class LiveSource implements PeerSource {

	private final Supplier<List<Node>> read;
	private volatile PeerGraph graph = PeerGraph.EMPTY;

	public LiveSource(Supplier<List<Node>> read) {
		this.read = read;
	}

	@Override
	public PeerGraph graph() {
		return graph;
	}

	@Override
	public void refresh() {
		try {
			List<Node> nodes = read.get();
			if (nodes != null) graph = new PeerGraph(nodes);
		} catch (RuntimeException e) {
			// The last answer stands; the map is a view, never the reason something fails.
		}
	}
}
