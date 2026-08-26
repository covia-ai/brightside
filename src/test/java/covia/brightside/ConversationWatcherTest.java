package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;

/**
 * The watcher is a value compare over a supplier: it must fire only when the
 * supplied value differs from the last one shown, never on an equal re-read,
 * and never while inactive. Headless-safe — Swing timers and workers need no
 * display.
 */
class ConversationWatcherTest {

	@Test
	void firesOnlyWhenTheValueChanges() throws Exception {
		ACell v1 = Maps.of("sessions", 1L);
		AtomicReference<ACell> current = new AtomicReference<>(v1);
		AtomicInteger fired = new AtomicInteger();
		AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
		ConversationWatcher w = new ConversationWatcher(current::get, v1, () -> true, record -> {
			fired.incrementAndGet();
			latch.get().countDown();
		});

		// Same value as the baseline (a structurally equal re-read): no refresh.
		current.set(Maps.of("sessions", 1L));
		SwingUtilities.invokeAndWait(w::checkNow);
		assertFalse(latch.get().await(600, TimeUnit.MILLISECONDS), "equal value must not fire");
		assertEquals(0, fired.get());

		// A different value: exactly one refresh, and it becomes the new baseline.
		current.set(Maps.of("sessions", 2L));
		SwingUtilities.invokeAndWait(w::checkNow);
		assertTrue(latch.get().await(5, TimeUnit.SECONDS), "changed value fires");
		assertEquals(1, fired.get());

		latch.set(new CountDownLatch(1));
		SwingUtilities.invokeAndWait(w::checkNow);
		assertFalse(latch.get().await(600, TimeUnit.MILLISECONDS), "unchanged since last fire");
		assertEquals(1, fired.get());

		// A failed read (null) is "try again next tick", not a change.
		current.set(null);
		SwingUtilities.invokeAndWait(w::checkNow);
		assertFalse(latch.get().await(600, TimeUnit.MILLISECONDS));
		assertEquals(1, fired.get());
	}

	@Test
	void doesNothingWhileInactive() throws Exception {
		AtomicReference<ACell> current = new AtomicReference<>(Maps.of("a", 1L));
		AtomicInteger reads = new AtomicInteger();
		CountDownLatch fired = new CountDownLatch(1);
		ConversationWatcher w = new ConversationWatcher(() -> {
			reads.incrementAndGet();
			return current.get();
		}, null, () -> false, record -> fired.countDown());
		SwingUtilities.invokeAndWait(w::checkNow);
		assertFalse(fired.await(400, TimeUnit.MILLISECONDS));
		assertEquals(0, reads.get(), "no read while the chat isn't showing");
	}
}
