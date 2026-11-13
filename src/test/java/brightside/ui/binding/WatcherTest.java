package brightside.ui.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.Root;

/**
 * The watcher reads a component only while its view is on screen, tells the
 * view of each change once, and lets go of a view that leaves the screen,
 * whether or not Swing said so. A test drives the passes, and decides what is
 * on screen and what is minimised; nothing waits on the clock.
 */
class WatcherTest {

	/** A lattice component over a root cursor, counting its reads. */
	private static final class Counter extends ALatticeComponent<ACell> {
		final AtomicInteger reads = new AtomicInteger();
		final AtomicBoolean broken = new AtomicBoolean();

		Counter(Root<ACell> root) {
			super(root);
		}

		@Override
		public ACell value() {
			reads.incrementAndGet();
			if (broken.get()) throw new IllegalStateException("unreadable");
			return super.value();
		}
	}

	private final Set<Component> onScreen = ConcurrentHashMap.newKeySet();
	private final Set<Component> minimised = ConcurrentHashMap.newKeySet();
	private final Watcher watcher = new Watcher(null, onScreen::contains, minimised::contains);
	private final Root<ACell> root = Root.create(CVMLong.create(1));
	private final Counter component = new Counter(root);
	private final JPanel view = new JPanel();
	private final List<ACell> seen = new ArrayList<>();

	private Watcher.Watch<ACell> watch() throws Exception {
		List<Watcher.Watch<ACell>> made = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> made.add(watcher.add(view, component, seen::add)));
		return made.get(0);
	}

	/** Puts the view on or off screen and tells it, as Swing does. */
	private void show(boolean showing) throws Exception {
		if (showing) onScreen.add(view);
		else onScreen.remove(view);
		SwingUtilities.invokeAndWait(() -> {
			HierarchyEvent e = new HierarchyEvent(view, HierarchyEvent.HIERARCHY_CHANGED, view, null,
				HierarchyEvent.SHOWING_CHANGED);
			for (HierarchyListener l : view.getHierarchyListeners()) l.hierarchyChanged(e);
		});
		settle();
	}

	private void pass() throws Exception {
		watcher.pass();
		settle();
	}

	/** Lets the event thread run what the watcher handed it. */
	private static void settle() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	@Test
	void aViewOffScreenIsNeverRead() throws Exception {
		watch();
		pass();
		assertEquals(0, watcher.live());
		assertEquals(0, component.reads.get());
		assertTrue(seen.isEmpty());
	}

	@Test
	void aViewOnScreenHearsEachChangeOnce() throws Exception {
		watch();
		show(true);
		assertEquals(1, watcher.live());
		assertEquals(List.of(CVMLong.create(1)), seen, "the value as it comes on screen");
		pass();
		assertEquals(1, seen.size(), "an unchanged value is not told again");
		root.set(CVMLong.create(2));
		pass();
		pass();
		assertEquals(List.of(CVMLong.create(1), CVMLong.create(2)), seen);
	}

	@Test
	void leavingTheScreenStopsTheReads() throws Exception {
		watch();
		show(true);
		show(false);
		assertEquals(0, watcher.live());
		int reads = component.reads.get();
		root.set(CVMLong.create(2));
		pass();
		assertEquals(reads, component.reads.get(), "nothing is read off screen");
		show(true);
		assertEquals(CVMLong.create(2), seen.get(seen.size() - 1), "coming back reads it at once");
	}

	@Test
	void aMissedSwingEventCostsOnePass() throws Exception {
		watch();
		show(true);
		onScreen.remove(view); // gone, and Swing never said
		root.set(CVMLong.create(2));
		pass();
		assertEquals(0, watcher.live(), "the pass found it off screen and let go");
		assertEquals(1, seen.size(), "and told it nothing");
	}

	@Test
	void aMinimisedWindowPausesTheWatch() throws Exception {
		watch();
		show(true);
		minimised.add(view);
		pass(); // finds it minimised
		int reads = component.reads.get();
		root.set(CVMLong.create(2));
		pass();
		assertEquals(reads, component.reads.get(), "nothing is read while minimised");
		assertEquals(1, watcher.live(), "but the watch stays");
		minimised.remove(view);
		pass(); // finds it restored
		pass();
		assertEquals(CVMLong.create(2), seen.get(seen.size() - 1));
	}

	@Test
	void closingEndsTheWatchForGood() throws Exception {
		Watcher.Watch<ACell> watch = watch();
		show(true);
		SwingUtilities.invokeAndWait(watch::close);
		assertEquals(0, watcher.live());
		assertEquals(0, view.getHierarchyListeners().length, "it let go of the view");
		show(true);
		assertEquals(0, watcher.live(), "coming back on screen does not revive it");
	}

	@Test
	void aValueThatGoesLeavesTheViewAsItWas() throws Exception {
		watch();
		show(true);
		root.set(null);
		pass();
		assertEquals(List.of(CVMLong.create(1)), seen, "nothing told when the value goes");
		root.set(CVMLong.create(3));
		pass();
		assertEquals(List.of(CVMLong.create(1), CVMLong.create(3)), seen, "and the value once it is back");
	}

	@Test
	void aComponentWithNoValueTellsNothing() throws Exception {
		root.set(null);
		watch();
		show(true);
		assertEquals(1, watcher.live());
		assertTrue(seen.isEmpty());
	}

	@Test
	void aFailedReadKeepsTheLastValue() throws Exception {
		watch();
		show(true);
		component.broken.set(true);
		root.set(CVMLong.create(2));
		try (var logs = brightside.ExpectedLogs.warn(Watcher.class, "Could not read a watched lattice component")) {
			pass();
		}
		assertEquals(List.of(CVMLong.create(1)), seen, "nothing told while it cannot be read");
		component.broken.set(false);
		pass();
		assertEquals(List.of(CVMLong.create(1), CVMLong.create(2)), seen, "and the change once it can");
	}
}
