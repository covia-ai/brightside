package brightside.ui.binding;

import java.awt.Component;
import java.awt.Frame;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.MissingValueException;

/**
 * Keeps views up to date with the lattice components they show. A view wraps
 * its domain component with one call, {@link #watch}, and is told on the event
 * thread whenever the component's value changes.
 *
 * <p>One thread serves every watch. Each pass reads the value of every watch
 * whose view is on screen and hands the changes to the event thread in one
 * batch; the next pass starts a full period after the last one finished, so a
 * slow pass stretches the cycle rather than piling passes up. Values are
 * compared as cells, which is cheap. A component with no value, such as a
 * record that has gone, tells the view nothing: it keeps what it showed.
 *
 * <p>A watch exists only while its view is on screen: it joins when Swing says
 * the view is showing and leaves when it is not, whether the page changed, the
 * view was replaced or the window went to the tray or closed. While the window
 * is minimised the watch stays but reads nothing. Each batch checks every
 * watch's view again on the event thread, so a missed Swing event costs at
 * most one pass. Closing a watch ({@link Watch#close}) ends it for good: a view
 * given another component closes the old watch.
 */
public final class Watcher {

	private static final Logger log = LoggerFactory.getLogger(Watcher.class);

	/** How long after one pass the next starts. */
	static final long PERIOD_MILLIS = 1000;

	private static final Watcher SHARED = startShared();

	private final Set<Watch<?>> live = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService thread; // null when a test drives the passes
	final Predicate<Component> showing;
	final Predicate<Component> minimised;

	/**
	 * @param thread    the thread passes and refreshes run on, or null for a
	 *                  test that calls {@link #pass} itself
	 * @param showing   whether a view is on screen
	 * @param minimised whether a view's window is minimised
	 */
	Watcher(ScheduledExecutorService thread, Predicate<Component> showing, Predicate<Component> minimised) {
		this.thread = thread;
		this.showing = Objects.requireNonNull(showing);
		this.minimised = Objects.requireNonNull(minimised);
	}

	private static Watcher startShared() {
		ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "brightside-watcher");
			t.setDaemon(true);
			return t;
		});
		Watcher watcher = new Watcher(thread, Component::isShowing, Watcher::inMinimisedWindow);
		thread.scheduleWithFixedDelay(watcher::pass, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
		return watcher;
	}

	private static boolean inMinimisedWindow(Component view) {
		return SwingUtilities.getWindowAncestor(view) instanceof Frame frame
			&& (frame.getExtendedState() & Frame.ICONIFIED) != 0;
	}

	/**
	 * Calls {@code changed} on the event thread with {@code component}'s value
	 * while {@code view} is on screen: first when it comes on screen, then
	 * whenever the value changes. While the component has no value it is not
	 * called, so the view keeps what it last showed. Call on the event thread.
	 */
	public static <V extends ACell> Watch<V> watch(Component view, ALatticeComponent<V> component,
			Consumer<? super V> changed) {
		return SHARED.add(view, component, changed);
	}

	/**
	 * As {@link #watch(Component, ALatticeComponent, Consumer)} for a view that
	 * reads its component itself as it paints: a change revalidates and
	 * repaints it.
	 */
	public static Watch<?> watch(Component view, ALatticeComponent<?> component) {
		return SHARED.add(view, component, value -> {
			view.revalidate();
			view.repaint();
		});
	}

	/**
	 * {@code component}'s value, or null where it has none: a component
	 * bound to a place nothing has been written to, such as a record that has
	 * gone, reports that by throwing.
	 */
	public static <V extends ACell> V valueOf(ALatticeComponent<V> component) {
		try {
			return component.value();
		} catch (MissingValueException e) {
			return null;
		}
	}

	<V extends ACell> Watch<V> add(Component view, ALatticeComponent<V> component, Consumer<? super V> changed) {
		Watch<V> watch = new Watch<>(this, view, component, changed);
		view.addHierarchyListener(watch.listener);
		if (showing.test(view)) join(watch);
		return watch;
	}

	/** How many watches are live: their views are on screen. */
	int live() {
		return live.size();
	}

	/** Event thread: the watch's view has come on screen; read it now. */
	void join(Watch<?> watch) {
		if (watch.closed || !live.add(watch)) return;
		watch.active = !minimised.test(watch.view);
		refresh(watch);
	}

	void leave(Watch<?> watch) {
		live.remove(watch);
	}

	/** Reads {@code watch} now, on the watcher's thread, rather than at the next pass. */
	void refresh(Watch<?> watch) {
		if (thread == null) post(List.of(watch.sample()));
		else thread.execute(() -> post(List.of(watch.sample())));
	}

	/** One pass over every live watch. On the watcher's thread, or a test's. */
	void pass() {
		try {
			List<Runnable> work = new ArrayList<>(live.size());
			for (Watch<?> watch : live) work.add(watch.sample());
			post(work);
		} catch (Throwable t) {
			// A pass must never end the thread that serves every view.
			log.warn("A watcher pass failed", t);
		}
	}

	private static void post(List<Runnable> work) {
		if (work.isEmpty()) return;
		SwingUtilities.invokeLater(() -> {
			for (Runnable step : work) step.run();
		});
	}

	/**
	 * One view's watch on one component. Ends when closed; leaves and rejoins
	 * the watcher as the view leaves and comes back on screen.
	 */
	public static final class Watch<V extends ACell> implements AutoCloseable {
		private final Watcher watcher;
		final Component view;
		private final ALatticeComponent<V> component;
		private final Consumer<? super V> changed;
		final HierarchyListener listener;
		/** False while the view's window is minimised: nothing is read. */
		volatile boolean active = true;
		volatile boolean closed;
		private volatile V last;
		private volatile boolean delivered;
		private boolean failing; // the watcher's thread only

		Watch(Watcher watcher, Component view, ALatticeComponent<V> component, Consumer<? super V> changed) {
			this.watcher = watcher;
			this.view = Objects.requireNonNull(view);
			this.component = Objects.requireNonNull(component);
			this.changed = Objects.requireNonNull(changed);
			this.listener = e -> {
				if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
				if (watcher.showing.test(view)) watcher.join(this);
				else watcher.leave(this);
			};
		}

		/** Reads the value now rather than at the next pass, as after an action on it. */
		public void refresh() {
			if (!closed && watcher.live.contains(this)) watcher.refresh(this);
		}

		/** Ends the watch for good. Event thread. */
		@Override
		public void close() {
			closed = true;
			watcher.leave(this);
			view.removeHierarchyListener(listener);
		}

		/**
		 * On the watcher's thread: reads the value if the view is in a window
		 * that is not minimised, and returns what the event thread does next.
		 */
		Runnable sample() {
			if (closed || !active) return this::check;
			V value;
			try {
				value = valueOf(component);
			} catch (RuntimeException e) {
				if (!failing) log.warn("Could not read a watched lattice component; showing its last value", e);
				failing = true;
				return this::check;
			}
			failing = false;
			if (value == null || (delivered && Objects.equals(value, last))) return this::check;
			return () -> {
				if (check()) deliver(value);
			};
		}

		/**
		 * Event thread: whether the view still wants values. One no longer on
		 * screen leaves the watcher; one in a minimised window pauses.
		 */
		private boolean check() {
			if (closed) return false;
			if (!watcher.showing.test(view)) {
				watcher.leave(this);
				return false;
			}
			active = !watcher.minimised.test(view);
			return active;
		}

		private void deliver(V value) {
			if (delivered && Objects.equals(value, last)) return;
			last = value;
			delivered = true;
			try {
				changed.accept(value);
			} catch (RuntimeException e) {
				log.warn("A view failed to take a new lattice value", e);
			}
		}
	}
}
