package brightside.peers;

import java.util.ArrayList;
import java.util.List;

/**
 * Where peer data comes from: samples today, then your own venue, the
 * services Brightside talks to and the P2P network (docs/PEERS.md). A source
 * only reports; it never sees or stores how the map draws what it reports.
 */
public interface PeerSource {

	/** What is known now. Cheap to call; never null. */
	PeerGraph graph();

	/** Whether this is sample data, which the map labels as a preview. */
	default boolean sample() {
		return false;
	}

	/**
	 * Reads afresh, for a source that reads on demand rather than being told;
	 * the map calls it when the tab opens. A source that never changes needs
	 * nothing more.
	 */
	default void refresh() {
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

	/**
	 * Several sources as one: their graphs joined, a later source's node
	 * replacing an earlier one's with the same DID; sample if any is.
	 */
	static PeerSource combined(PeerSource... sources) {
		List<PeerSource> all = List.of(sources);
		return new PeerSource() {
			@Override
			public PeerGraph graph() {
				List<Node> nodes = new ArrayList<>();
				for (PeerSource s : all) nodes.addAll(s.graph().nodes());
				return new PeerGraph(nodes);
			}

			@Override
			public boolean sample() {
				return all.stream().anyMatch(PeerSource::sample);
			}

			@Override
			public void refresh() {
				for (PeerSource s : all) s.refresh();
			}

			@Override
			public AutoCloseable onChange(Runnable changed) {
				List<AutoCloseable> subs = new ArrayList<>();
				for (PeerSource s : all) subs.add(s.onChange(changed));
				return () -> {
					for (AutoCloseable c : subs) c.close();
				};
			}
		};
	}
}
