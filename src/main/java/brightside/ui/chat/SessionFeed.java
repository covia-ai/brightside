package brightside.ui.chat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.SwingWorker;
import javax.swing.Timer;

import brightside.SessionHistory;
import convex.core.data.ACell;

/**
 * Live data for one session-view binding. All methods and deliveries run on
 * the event thread; lattice reads and projections run in a single worker.
 * Selection changes invalidate pending results. Closing is permanent.
 */
public final class SessionFeed implements AutoCloseable {

	public record Update(ACell record, List<SessionHistory.Session> sessions,
			String selectedId, SessionHistory.Snapshot selected, boolean active) {
	}

	private final Supplier<ACell> read;
	private final BooleanSupplier visible;
	private final Consumer<Update> changed;
	private final Timer timer;
	private SwingWorker<Update, Void> worker;
	/** Completes on the event thread once no read is in flight and none is pending. */
	private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
	private ACell lastRecord;
	private String selectedId;
	private long generation;
	private boolean pending;
	private boolean closed;

	public SessionFeed(Supplier<ACell> read, BooleanSupplier visible, Consumer<Update> changed) {
		this.read = read;
		this.visible = visible;
		this.changed = changed;
		// Live events trigger immediate reads; polling covers out-of-band edits.
		timer = new Timer(15_000, e -> refresh());
	}

	/** Shows a session; the returned future settles as {@link #refresh()} describes. */
	public CompletableFuture<Void> select(String sessionId) {
		if (closed) return idle;
		timer.start();
		selectedId = sessionId;
		generation++;
		lastRecord = null;
		return refresh();
	}

	/**
	 * Coalesce event bursts, with a final read after any work already in
	 * flight. The returned future completes on the event thread once the feed
	 * is quiet again — every read this and any coalesced calls asked for has
	 * finished and delivered what it had to — so a caller can follow a refresh
	 * to its end without guessing at timings.
	 */
	public CompletableFuture<Void> refresh() {
		if (closed || !visible.getAsBoolean()) return idle;
		if (worker != null) {
			pending = true;
			return idle;
		}
		pending = false;
		if (idle.isDone()) idle = new CompletableFuture<>();
		CompletableFuture<Void> settled = idle;
		long expected = generation;
		String sid = selectedId;
		ACell baseline = lastRecord;
		worker = new SwingWorker<>() {
			@Override
			protected Update doInBackground() {
				ACell record = read.get();
				if (record == null || Objects.equals(record, baseline)) return null;
				SessionHistory.Snapshot selected = sid == null ? null : SessionHistory.snapshotOf(record, sid);
				// A removed session must never fall back to a different conversation.
				if (selected != null && !sid.equals(selected.sessionId())) selected = null;
				return new Update(record, SessionHistory.sessionsOf(record), sid, selected,
					SessionHistory.isSessionActive(record, sid));
			}

			@Override
			protected void done() {
				worker = null;
				try {
					Update update = get();
					if (!closed && expected == generation && update != null) {
						lastRecord = update.record();
						changed.accept(update);
					}
				} catch (Exception e) {
					// A failed read keeps the current view; the next event or tick retries.
				} finally {
					if (pending) refresh();
					// A follow-up read carries the same future on; none, and it is settled.
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
		generation++;
		timer.stop();
		if (worker != null) worker.cancel(true);
	}
}
