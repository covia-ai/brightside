package brightside.peers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sample data the owner can hide: the example homes on the Peers map until
 * connecting to others ships. While hidden it reports nothing, so the map
 * shows only what is real.
 */
public final class ExamplesSource implements PeerSource {

	private final PeerSource samples;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean shown;

	public ExamplesSource(PeerSource samples, boolean shown) {
		this.samples = samples;
		this.shown = shown;
	}

	public boolean shown() {
		return shown;
	}

	/** Shows or hides the examples, telling whoever follows this source. */
	public void setShown(boolean shown) {
		if (this.shown == shown) return;
		this.shown = shown;
		for (Runnable r : listeners) r.run();
	}

	@Override
	public PeerGraph graph() {
		return shown ? samples.graph() : PeerGraph.EMPTY;
	}

	@Override
	public boolean sample() {
		return shown && samples.sample();
	}

	@Override
	public void refresh() {
		samples.refresh();
	}

	@Override
	public AutoCloseable onChange(Runnable changed) {
		listeners.add(changed);
		AutoCloseable inner = samples.onChange(() -> {
			if (shown) changed.run();
		});
		return () -> {
			listeners.remove(changed);
			inner.close();
		};
	}

	/** The examples source among {@code sources}, if any, for the map's toggle. */
	public static ExamplesSource find(PeerSource source) {
		return (source instanceof Holder h) ? h.examples() : null;
	}

	/** A combined source that remembers which part holds the examples. */
	public interface Holder extends PeerSource {
		ExamplesSource examples();
	}

	/** {@code examples} joined with {@code others}, as {@link PeerSource#combined}, keeping hold of the examples. */
	public static Holder with(ExamplesSource examples, PeerSource... others) {
		PeerSource[] all = new PeerSource[others.length + 1];
		all[0] = examples;
		System.arraycopy(others, 0, all, 1, others.length);
		PeerSource joined = PeerSource.combined(all);
		return new Holder() {
			@Override
			public ExamplesSource examples() {
				return examples;
			}

			@Override
			public PeerGraph graph() {
				return joined.graph();
			}

			@Override
			public boolean sample() {
				return joined.sample();
			}

			@Override
			public void refresh() {
				joined.refresh();
			}

			@Override
			public AutoCloseable onChange(Runnable changed) {
				return joined.onChange(changed);
			}
		};
	}
}
