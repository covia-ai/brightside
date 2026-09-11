package brightside;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.SwingWorker;
import javax.swing.Timer;

import convex.core.data.ACell;

/**
 * Watches a lattice value, for application-wide state such as the owner's inbox.
 *
 * <p>Immutable values are compared in-process without submitting a job.
 * Polling pauses while the window is hidden; stopping also invalidates reads
 * that are still in flight.
 *
 * <p>Runs on the Swing timer/event thread; the read itself is off the event
 * thread. Ticks never overlap.
 */
public final class LatticeWatcher {

	private static final int INTERVAL_MS = 2500;

	private final Supplier<ACell> readValue;
	private final BooleanSupplier active;
	private final Consumer<ACell> onChanged;
	private final Timer timer;

	private ACell lastValue;
	private boolean checking;
	private boolean stopped;
	private long generation;

	/**
	 * @param readValue    supplies the current lattice value (an in-process lattice
	 *                     read, not a job)
	 * @param initialValue the lattice value already shown (baseline for comparison), or null
	 * @param active       polls only while this returns true (e.g. window showing)
	 * @param onChanged    called on the event thread when the value changes
	 */
	public LatticeWatcher(Supplier<ACell> readValue, ACell initialValue,
			BooleanSupplier active, Consumer<ACell> onChanged) {
		this.readValue = readValue;
		this.lastValue = initialValue;
		this.active = active;
		this.onChanged = onChanged;
		this.timer = new Timer(INTERVAL_MS, e -> tick());
		this.timer.setRepeats(true);
	}

	public void start() {
		stopped = false;
		timer.start();
	}

	public void stop() {
		stopped = true;
		generation++;
		timer.stop();
	}

	/** Force an immediate check (e.g. a manual Refresh). */
	public void checkNow() {
		tick();
	}

	private void tick() {
		if (stopped || checking) return;
		if (active != null && !active.getAsBoolean()) return;
		checking = true;
		final ACell baseline = lastValue;
		final long expected = generation;
		new SwingWorker<ACell, Void>() {
			@Override
			protected ACell doInBackground() {
				ACell value = readValue.get();
				// Only surface the record when the agent value actually changed.
				return (value == null || equalValue(value, baseline)) ? null : value;
			}

			@Override
			protected void done() {
				checking = false;
				if (stopped || generation != expected) return;
				try {
					ACell value = get();
					if (value != null) {
						lastValue = value;
						onChanged.accept(value);
					}
				} catch (Exception ignored) {
					// a failed read just means "try again next tick"
				}
			}
		}.execute();
	}

	private static boolean equalValue(ACell a, ACell b) {
		return (a == b) || (a != null && a.equals(b));
	}
}
