package brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Vectors;

/** Selection and asynchronous delivery, without constructing any UI or venue. */
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
			SwingUtilities.invokeAndWait(() -> {
				feed.select("bb");
				for (int i = 0; i < 20; i++) feed.refresh();
			});
			release.countDown();
			SessionFeed.Update update = next(updates);
			assertEquals("bb", update.selectedId());
			assertEquals("bb", update.selected().sessionId());
			assertEquals(2, reads.get());
			assertNull(updates.poll(200, TimeUnit.MILLISECONDS));
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
			SwingUtilities.invokeAndWait(() -> feed.select("aa"));
			assertEquals("aa", next(updates).selectedId());
			SwingUtilities.invokeAndWait(() -> feed.select(null));
			assertNull(next(updates).selected());
			current.set(record("old turn finished"));
			SwingUtilities.invokeAndWait(feed::refresh);
			SessionFeed.Update update = next(updates);
			assertNull(update.selectedId());
			assertNull(update.selected());
			assertEquals(2, update.sessions().size());
			SwingUtilities.invokeAndWait(() -> feed.select("removed"));
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
			SwingUtilities.invokeAndWait(() -> feed.select("aa"));
			next(updates);
			SwingUtilities.invokeAndWait(feed::refresh);
			assertNull(updates.poll(200, TimeUnit.MILLISECONDS));
			SwingUtilities.invokeAndWait(() -> feed.select("aa"));
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
			SwingUtilities.invokeAndWait(() -> feed.select("aa"));
			assertEquals(0, reads.get());
			visible.set(true);
			SwingUtilities.invokeAndWait(feed::refresh);
			assertTrue(reading.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(feed::close);
			release.countDown();
			SwingUtilities.invokeAndWait(feed::refresh);
			assertNull(updates.poll(400, TimeUnit.MILLISECONDS));
			assertEquals(1, reads.get());
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(feed::close);
		}
	}

	private static SessionFeed.Update next(BlockingQueue<SessionFeed.Update> updates) throws InterruptedException {
		SessionFeed.Update update = updates.poll(5, TimeUnit.SECONDS);
		assertNotNull(update, "the current selection must be delivered");
		return update;
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
}
