package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import brightside.LatticeWatcher;
import convex.core.data.ACell;
import convex.core.data.Maps;

/**
 * The watcher is a value compare over a supplier: it must fire only when the
 * supplied value differs from the last one shown, never on an equal re-read,
 * and never while inactive. Each check is followed to its end through the
 * future it returns, so nothing here waits on a clock. Headless-safe — Swing
 * timers and workers need no display.
 */
@Execution(ExecutionMode.CONCURRENT)
class LatticeWatcherTest {

	@Test
	void firesOnlyWhenTheValueChanges() throws Exception {
		ACell v1 = Maps.of("sessions", 1L);
		AtomicReference<ACell> current = new AtomicReference<>(v1);
		AtomicInteger fired = new AtomicInteger();
		LatticeWatcher w = new LatticeWatcher(current::get, v1, () -> true, record -> fired.incrementAndGet());

		// Same value as the baseline (a structurally equal re-read): no refresh.
		current.set(Maps.of("sessions", 1L));
		check(w);
		assertEquals(0, fired.get(), "equal value must not fire");

		// A different value: exactly one refresh, and it becomes the new baseline.
		current.set(Maps.of("sessions", 2L));
		check(w);
		assertEquals(1, fired.get(), "changed value fires");

		check(w);
		assertEquals(1, fired.get(), "unchanged since last fire");

		// A failed read (null) is "try again next tick", not a change.
		current.set(null);
		check(w);
		assertEquals(1, fired.get());
	}

	@Test
	void stopInvalidatesAnInFlightReadAndQueuedChecks() throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger delivered = new AtomicInteger();
		LatticeWatcher watcher = new LatticeWatcher(() -> {
			reads.incrementAndGet();
			reading.countDown();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
			return Maps.of("changed", true);
		}, null, () -> true, value -> delivered.incrementAndGet());
		try {
			CompletableFuture<Void> inFlight = onEventThread(watcher::checkNow);
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(watcher::stop);
			release.countDown();
			inFlight.get(5, TimeUnit.SECONDS);
			check(watcher);
			assertEquals(0, delivered.get(), "a read overtaken by stop is not delivered");
			assertEquals(1, reads.get(), "no check runs once stopped");
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(watcher::stop);
		}
	}

	@Test
	void doesNothingWhileInactive() throws Exception {
		AtomicReference<ACell> current = new AtomicReference<>(Maps.of("a", 1L));
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger fired = new AtomicInteger();
		LatticeWatcher w = new LatticeWatcher(() -> {
			reads.incrementAndGet();
			return current.get();
		}, null, () -> false, record -> fired.incrementAndGet());
		check(w);
		assertEquals(0, fired.get());
		assertEquals(0, reads.get(), "no read while the chat isn't showing");
	}

	/** One check, started on the event thread as the app starts it, followed to its end. */
	private static void check(LatticeWatcher w) throws Exception {
		onEventThread(w::checkNow).get(5, TimeUnit.SECONDS);
	}

	private static CompletableFuture<Void> onEventThread(Supplier<CompletableFuture<Void>> action) throws Exception {
		AtomicReference<CompletableFuture<Void>> started = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> started.set(action.get()));
		return started.get();
	}
}
