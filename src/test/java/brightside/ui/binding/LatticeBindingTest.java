package brightside.ui.binding;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;

/** Binding behaviour without constructing a screen. Futures and latches follow every asynchronous read. */
class LatticeBindingTest {

	@Test
	void immutableSnapshotsProjectOffThreadAndDeliverNullAsAValue() throws Exception {
		AtomicReference<ACell> current = new AtomicReference<>(Maps.of("n", 1));
		AtomicInteger projections = new AtomicInteger();
		List<ACell> shown = new ArrayList<>();
		var binding = new LatticeBinding<>(() -> {
			assertFalse(SwingUtilities.isEventDispatchThread());
			return current.get();
		}, value -> {
			assertFalse(SwingUtilities.isEventDispatchThread());
			projections.incrementAndGet();
			return value;
		}, () -> true, value -> {
			assertTrue(SwingUtilities.isEventDispatchThread());
			shown.add(value);
		}, error -> fail(error), 60_000);
		try {
			settle(binding::refresh);
			current.set(Maps.of("n", 1));
			settle(binding::refresh);
			assertEquals(1, projections.get(), "equal immutable values do not project again");
			current.set(null);
			settle(binding::refresh);
			settle(binding::refresh);
			assertEquals(2, shown.size());
			assertNull(shown.get(1), "absence clears the previous value");
			current.set(Maps.empty());
			settle(binding::refresh);
			assertEquals(Maps.empty(), shown.get(2), "empty and absent are distinct");
		} finally {
			SwingUtilities.invokeAndWait(binding::close);
		}
	}

	@Test
	void failedReadRetainsBaselineAndUnchangedRecoveryStillDelivers() throws Exception {
		AtomicBoolean failing = new AtomicBoolean();
		AtomicInteger deliveries = new AtomicInteger(), failures = new AtomicInteger();
		var binding = new LatticeBinding<>(() -> {
			if (failing.get()) throw new IllegalStateException("read failed");
			return Maps.of("n", 1);
		}, value -> value, () -> true, value -> deliveries.incrementAndGet(), error -> {
			assertTrue(SwingUtilities.isEventDispatchThread());
			assertInstanceOf(IllegalStateException.class, error);
			failures.incrementAndGet();
		}, 60_000);
		try {
			settle(binding::refresh);
			failing.set(true);
			settle(binding::refresh);
			settle(binding::refresh);
			assertEquals(1, deliveries.get());
			assertEquals(1, failures.get());
			failing.set(false);
			settle(binding::refresh);
			assertEquals(2, deliveries.get(), "the successful retry clears the failure state");
			settle(binding::refresh);
			assertEquals(2, deliveries.get());
		} finally {
			SwingUtilities.invokeAndWait(binding::close);
		}
	}

	@Test
	void refreshBurstsHaveOneTrailingReadAndOneSettledFuture() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		List<ACell> shown = new ArrayList<>();
		var binding = new LatticeBinding<>(() -> {
			int n = reads.incrementAndGet();
			if (n == 1) { entered.countDown(); await(release); }
			return Maps.of("n", n);
		}, value -> value, () -> true, shown::add, error -> fail(error), 60_000);
		try {
			CompletableFuture<Void> first = onEDT(binding::refresh);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int i = 0; i < 20; i++) assertSame(first, onEDT(binding::refresh));
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertEquals(2, reads.get());
			assertEquals(List.of(Maps.of("n", 1), Maps.of("n", 2)), shown);
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(binding::close);
		}
	}

	@Test
	void hidingDuringAReadDefersDeliveryAndClosingDiscardsOldBinding() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean visible = new AtomicBoolean(true);
		AtomicInteger reads = new AtomicInteger(), deliveries = new AtomicInteger();
		var binding = new LatticeBinding<>(() -> {
			if (reads.incrementAndGet() == 1) { entered.countDown(); await(release); }
			return Maps.of("n", 1);
		}, value -> value, visible::get, value -> deliveries.incrementAndGet(), error -> fail(error), 60_000);
		try {
			CompletableFuture<Void> first = onEDT(binding::refresh);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			visible.set(false);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			settle(binding::refresh);
			assertEquals(1, reads.get());
			assertEquals(0, deliveries.get());
			visible.set(true);
			settle(binding::refresh);
			assertEquals(1, deliveries.get(), "hidden results do not advance the displayed baseline");
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(binding::close);
		}
		settle(binding::start);
		assertEquals(2, reads.get(), "a disposed binding cannot restart");
	}

	@Test
	void replacingBindingDropsOldReadAndItsPendingRefresh() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger oldReads = new AtomicInteger();
		List<ACell> shown = new ArrayList<>();
		var old = new LatticeBinding<>(() -> {
			oldReads.incrementAndGet();
			entered.countDown(); await(release);
			return Maps.of("owner", "old");
		}, value -> value, () -> true, shown::add, error -> fail(error), 60_000);
		var next = new LatticeBinding<>(() -> Maps.of("owner", "new"), value -> value,
			() -> true, shown::add, error -> fail(error), 60_000);
		try {
			CompletableFuture<Void> first = onEDT(old::refresh);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			onEDT(old::refresh);
			SwingUtilities.invokeAndWait(old::close);
			settle(next::refresh);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertEquals(1, oldReads.get());
			assertEquals(List.of(Maps.of("owner", "new")), shown);
		} finally {
			release.countDown();
			SwingUtilities.invokeAndWait(old::close);
			SwingUtilities.invokeAndWait(next::close);
		}
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { throw new AssertionError(e); }
	}

	private static <T> T onEDT(Supplier<T> action) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> result.set(action.get()));
		return result.get();
	}

	private static void settle(Supplier<CompletableFuture<Void>> action) throws Exception {
		onEDT(action).get(5, TimeUnit.SECONDS);
	}
}
