package covia.brightside;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.SwingWorker;
import javax.swing.Timer;

import convex.core.data.ACell;
import covia.grid.Venue;

/**
 * Watches the venue's agent value and refreshes the UI when it changes.
 *
 * <p>Change detection is a plain lattice value compare: each tick re-reads the
 * agent value and compares it with {@code .equals} to the last one shown. Since
 * lattice values are immutable and content-addressed, an unchanged conversation
 * yields an equal value (cheap), and any change — a new turn, an edit from
 * another client, an out-of-band agent update — yields a different one, so the
 * UI can refresh. Polling pauses while the window isn't showing.
 *
 * <p>Runs on the Swing timer/event thread; the read itself is off the event
 * thread. Ticks never overlap.
 */
public final class ConversationWatcher {

	private static final int INTERVAL_MS = 2500;

	private final Venue client;
	private final String agentId;
	private final BooleanSupplier active;
	private final Consumer<ACell> onChanged;
	private final Timer timer;

	private ACell lastValue;
	private boolean checking;

	/**
	 * @param initialValue the agent value already shown (baseline for comparison), or null
	 * @param active       polls only while this returns true (e.g. window showing)
	 * @param onChanged    called on the event thread with the new agent record when
	 *                     the value changes; the caller projects the switcher list
	 *                     and the currently-viewed session from it
	 */
	public ConversationWatcher(Venue client, String agentId, ACell initialValue,
			BooleanSupplier active, Consumer<ACell> onChanged) {
		this.client = client;
		this.agentId = agentId;
		this.lastValue = initialValue;
		this.active = active;
		this.onChanged = onChanged;
		this.timer = new Timer(INTERVAL_MS, e -> tick());
		this.timer.setRepeats(true);
	}

	public void start() {
		timer.start();
	}

	public void stop() {
		timer.stop();
	}

	/** Force an immediate check (e.g. a manual Refresh). */
	public void checkNow() {
		tick();
	}

	private void tick() {
		if (checking) return;
		if (active != null && !active.getAsBoolean()) return;
		checking = true;
		final ACell baseline = lastValue;
		new SwingWorker<ACell, Void>() {
			@Override
			protected ACell doInBackground() {
				ACell value = SessionHistory.readAgentValue(client, agentId);
				// Only surface the record when the agent value actually changed.
				return (value == null || equalValue(value, baseline)) ? null : value;
			}

			@Override
			protected void done() {
				checking = false;
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
