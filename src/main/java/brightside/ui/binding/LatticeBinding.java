package brightside.ui.binding;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.SwingWorker;
import javax.swing.Timer;

import convex.core.data.ACell;

/**
 * One live binding from an immutable domain snapshot to a Swing view.
 * Reads and projections run off the event thread; lifecycle and delivery run
 * on it. Refresh bursts coalesce into one trailing read. Closing is permanent:
 * replace the binding when its venue or principal changes.
 *
 * <p>Null is a value (for example, a missing entity), never a failed read.
 * Failures retain the last delivered snapshot and are reported separately.
 * A successful retry delivers even an unchanged value, so the view can clear
 * its failure state. Hidden views neither start reads nor receive results.
 */
public final class LatticeBinding<V extends ACell, T> implements AutoCloseable {

	private record Update<V, T>(V value, T projected) {}

	private final Supplier<V> read;
	private final Function<V, T> project;
	private final BooleanSupplier active;
	private final Consumer<T> changed;
	private final Consumer<Throwable> failed;
	private final Timer timer;
	private SwingWorker<Update<V, T>, Void> worker;
	private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
	private V lastValue;
	private boolean delivered, retry, pending, closed;

	public LatticeBinding(Supplier<V> read, Function<V, T> project, BooleanSupplier active,
			Consumer<T> changed, Consumer<Throwable> failed, int intervalMillis) {
		this.read = Objects.requireNonNull(read);
		this.project = Objects.requireNonNull(project);
		this.active = Objects.requireNonNull(active);
		this.changed = Objects.requireNonNull(changed);
		this.failed = Objects.requireNonNull(failed);
		timer = new Timer(intervalMillis, e -> refresh());
	}

	/** Start periodic observation and request the first snapshot immediately. */
	public CompletableFuture<Void> start() {
		if (!closed) timer.start();
		return refresh();
	}

	/**
	 * Request a snapshot, including one after any read already in flight.
	 * Completes on the event thread when all coalesced work has settled;
	 * read failures go to the failure callback. No clock-based wait is needed.
	 */
	public CompletableFuture<Void> refresh() {
		if (closed || !active.getAsBoolean()) return idle;
		if (worker != null) {
			pending = true;
			return idle;
		}
		pending = false;
		if (idle.isDone()) idle = new CompletableFuture<>();
		CompletableFuture<Void> settled = idle;
		V baseline = lastValue;
		boolean force = !delivered || retry;
		worker = new SwingWorker<>() {
			@Override
			protected Update<V, T> doInBackground() {
				V value = read.get();
				if (!force && Objects.equals(value, baseline)) return null;
				return new Update<>(value, project.apply(value));
			}

			@Override
			protected void done() {
				worker = null;
				try {
					Update<V, T> update;
					try {
						update = get();
					} catch (ExecutionException e) {
						if (!closed && active.getAsBoolean()) {
							boolean report = !retry;
							retry = true;
							if (report) failed.accept(e.getCause());
						}
						return;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					if (!closed && active.getAsBoolean() && update != null) {
						lastValue = update.value();
						delivered = true;
						retry = false;
						changed.accept(update.projected());
					}
				} finally {
					if (pending) refresh();
					if (worker == null) settled.complete(null);
				}
			}
		};
		worker.execute();
		return settled;
	}

	@Override
	public void close() {
		closed = true;
		pending = false;
		timer.stop();
		// Let an in-flight read settle, but never deliver it to the old view.
	}
}
