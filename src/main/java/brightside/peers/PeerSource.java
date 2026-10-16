package brightside.peers;

/**
 * Where peer data comes from: samples today, then the services Brightside
 * talks to, then the P2P network (docs/PEERS.md). A source only reports; it
 * never sees or stores how the map draws what it reports.
 */
public interface PeerSource {

	/** What is known now. Cheap to call; never null. */
	PeerGraph graph();

	/** Whether this is sample data, which the map labels as a preview. */
	default boolean sample() {
		return false;
	}

	/**
	 * Calls {@code changed} whenever {@link #graph()} would answer differently,
	 * on any thread; close the result to stop. A source that never changes
	 * needs nothing more.
	 */
	default AutoCloseable onChange(Runnable changed) {
		return () -> {
		};
	}
}
