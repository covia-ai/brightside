package brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Vectors;

/**
 * Selection and asynchronous delivery, without constructing any UI or venue.
 * Each refresh is followed to its end through the future it returns, so
 * nothing here waits on a clock.
 */
@Execution(ExecutionMode.CONCURRENT)
class SessionFeedTest {

	private static ACell record(String text) {
		return Maps.of("sessions", Maps.of(
			"aa", session(text), "bb", session("second")));
	}

	private static ACell session(String text) {
		return Maps.of("frames", Vectors.of(Maps.of(
			"conversation", Vectors.of(Maps.of("role", "user", "content", text)))));
	}

	@Test
	void switchingDuringAReadDropsTheOldSelectionAndCoalescesEvents() throws Exception {
		CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		BlockingQueue<SessionFeed.Update> updates = new LinkedBlockingQueue<>();
		SessionFeed feed = new SessionFeed(() -> {
			assertFalse(SwingUtilities.isEventDispatchThread());
			if (reads.incrementAndGet() == 1) {
				reading.countDown();
				await(release);
			}
			return record("first");
		}, () -> true, update -> {
			assertTrue(SwingUtilities.isEventDispatchThread());
			updates.add(update);
		});
		try {
			SwingUtilities.invokeAndWait(() -> feed.select("aa"));
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			CompletableFuture<Void> quiet = onEventThread(() -> {
				feed.select("bb");
				CompletableFuture<Void> last = null;
				for (int i = 0; i < 20; i++) last = feed.refresh();
				return last;
			});
			release.countDown();
			quiet.get(5, TimeUnit.SECONDS);
			SessionFeed.Update update = next(updates);
			assertEquals("bb", update.selectedId());
			assertEquals("bb", update.selected().sessionId());
			assertEquals(2, reads.get(), "one read for the whole burst, after the one it overtook");
			assertNull(updates.poll(), "and one delivery");
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(feed::close);
		}
	}

	@Test
	void freshChatDoesNotFollowAnOldTurnOrFallBackToTheLatestSession() throws Exception {
		AtomicReference<ACell> current = new AtomicReference<>(record("first"));
		BlockingQueue<SessionFeed.Update> updates = new LinkedBlockingQueue<>();
		SessionFeed feed = new SessionFeed(current::get, () -> true, updates::add);
		try {
			settle(() -> feed.select("aa"));
			assertEquals("aa", next(updates).selectedId());
			settle(() -> feed.select(null));
			assertNull(next(updates).selected());
			current.set(record("old turn finished"));
			settle(feed::refresh);
			SessionFeed.Update update = next(updates);
			assertNull(update.selectedId());
			assertNull(update.selected());
			assertEquals(2, update.sessions().size());
			settle(() -> feed.select("removed"));
			assertNull(next(updates).selected());
		} finally {
			SwingUtilities.invokeAndWait(feed::close);
		}
	}

	@Test
	void selectionCanReconcileARecordAlreadyObservedWhileSending() throws Exception {
		BlockingQueue<SessionFeed.Update> updates = new LinkedBlockingQueue<>();
		SessionFeed feed = new SessionFeed(() -> record("finished"), () -> true, updates::add);
		try {
			settle(() -> feed.select("aa"));
			next(updates);
			settle(feed::refresh);
			assertNull(updates.poll(), "an unchanged record is not delivered again");
			settle(() -> feed.select("aa"));
			assertEquals("aa", next(updates).selectedId());
		} finally {
			SwingUtilities.invokeAndWait(feed::close);
		}
	}

	@Test
	void closingDropsAnInFlightReadAndPreventsFurtherReads() throws Exception {
		CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean visible = new AtomicBoolean(false);
		AtomicInteger reads = new AtomicInteger();
		BlockingQueue<SessionFeed.Update> updates = new LinkedBlockingQueue<>();
		SessionFeed feed = new SessionFeed(() -> {
			reads.incrementAndGet();
			reading.countDown();
			try { release.await(); } catch (InterruptedException ignored) { }
			return record("late");
		}, visible::get, updates::add);
		try {
			settle(() -> feed.select("aa"));
			assertEquals(0, reads.get(), "hidden: no read");
			visible.set(true);
			CompletableFuture<Void> inFlight = onEventThread(feed::refresh);
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(feed::close);
			release.countDown();
			inFlight.get(5, TimeUnit.SECONDS);
			settle(feed::refresh);
			assertNull(updates.poll(), "a read overtaken by close is not delivered");
			assertEquals(1, reads.get(), "no read after close");
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(feed::close);
		}
	}

	/** The delivery the feed has already made; the caller has settled the refresh that made it. */
	private static SessionFeed.Update next(BlockingQueue<SessionFeed.Update> updates) {
		SessionFeed.Update update = updates.poll();
		assertNotNull(update, "the current selection must be delivered");
		return update;
	}

	/** Runs a feed call on the event thread, as the app does, and follows it until the feed is quiet. */
	private static void settle(Supplier<CompletableFuture<Void>> action) throws Exception {
		onEventThread(action).get(5, TimeUnit.SECONDS);
	}

	private static CompletableFuture<Void> onEventThread(Supplier<CompletableFuture<Void>> action) throws Exception {
		AtomicReference<CompletableFuture<Void>> started = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> started.set(action.get()));
		return started.get();
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
}
