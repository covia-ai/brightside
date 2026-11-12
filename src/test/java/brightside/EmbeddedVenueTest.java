package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.etch.EtchStore;

/**
 * Boots a real venue server from BrightSide's default config (temp store,
 * ephemeral port) — the launch itself, so this venue is the test's own.
 */
class EmbeddedVenueTest {

	@TempDir
	Path home;

	@Test
	void aNewerStoreIsRecognisedThroughTheLaunchFailure() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			var config = SharedVenue.config(directory.path())
				.assoc(covia.venue.Config.STORE, convex.core.data.Strings.create(directory.path().resolve("venue.etch").toString()));
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config)) {
				venue.engine().getVenueState().cursor().path(covia.lattice.Covia.VERSION)
					.set(convex.core.data.prim.CVMLong.create(covia.venue.VenueState.CURRENT_VERSION + 1));
			}
			Exception failure = assertThrows(Exception.class, () -> EmbeddedVenue.launch(config));
			assertTrue(EmbeddedVenue.isNewerStore(failure));
			assertFalse(EmbeddedVenue.isNewerStore(new IllegalStateException("another failure")));
		}
	}

	/**
	 * Exit closes the venue on its own thread, and the JVM's shutdown hook closes
	 * it again; Convex's Etch hook then closes the store file. So a second close
	 * must not return while the first is still saving, or that hook closes the
	 * store beneath the save. The first close is held inside an adapter's close,
	 * before the engine's final flush.
	 */
	@Test
	void aSecondCloseWaitsUntilTheFirstHasClosedTheStore() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path file = directory.path().resolve("venue.etch");
			var config = SharedVenue.config(directory.path())
				.assoc(covia.venue.Config.STORE, convex.core.data.Strings.create(file.toString()));
			EtchStore store = EtchStore.create(file.toFile());
			EmbeddedVenue venue = EmbeddedVenue.launch(config, store, null);
			HeldClose held = new HeldClose();
			venue.engine().registerAdapter(held);
			venue.brightside().saveModel(MODEL);

			Thread first = new Thread(venue::close, "first-close");
			first.start();
			held.entered.await();

			// As at JVM shutdown: Tau's hook closes the venue, then Convex's closes the store.
			AtomicBoolean secondReturned = new AtomicBoolean();
			AtomicBoolean storeClosedOnReturn = new AtomicBoolean();
			Thread second = new Thread(() -> {
				venue.close();
				secondReturned.set(true);
				storeClosedOnReturn.set(isClosed(store));
				store.close(); // as Convex's Etch hook closes the file
			}, "second-close");
			second.start();
			// Hold the first close until the second has either finished or is waiting for it.
			while (second.isAlive() && (secondReturned.get() || second.getState() != Thread.State.WAITING)) {
				Thread.onSpinWait();
			}
			held.release.countDown();
			first.join();
			second.join();
			assertTrue(storeClosedOnReturn.get(), "close returns only once the store is closed");

			try (EmbeddedVenue reopened = EmbeddedVenue.launch(config)) {
				assertEquals(MODEL, reopened.brightside().model(), "a write made just before close survives it");
			}
		}
	}

	private static final String MODEL = "v/test/ops/taskllm";

	/** A hash the store holds nothing for, so reading it goes to the file. */
	private static final convex.core.data.Hash UNSTORED = convex.core.data.Hash.wrap(new byte[] {
		0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a,
		0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a, 0x5a });

	/** Whether the store's file is closed: a read of it then fails. */
	private static boolean isClosed(EtchStore store) {
		try {
			store.refForHash(UNSTORED);
			return false;
		} catch (convex.core.exceptions.StoreException e) {
			return true;
		}
	}

	/** An adapter whose close waits, holding the venue's close before its final flush. */
	private static final class HeldClose extends covia.adapter.AAdapter implements AutoCloseable {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public String getName() {
			return "heldclose";
		}

		@Override
		public String getDescription() {
			return "Holds the venue's close";
		}

		@Override
		public CompletableFuture<ACell> invokeFuture(covia.venue.RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException());
		}

		@Override
		public void close() throws InterruptedException {
			entered.countDown();
			release.await();
		}
	}

	@Test
	void launchesServesAndCloses() throws Exception {
		EmbeddedVenue v = EmbeddedVenue.launch(SharedVenue.config(home));
		try {
			int port = v.port();
			assertTrue(port > 0, "port 0 asks the server to pick one, and it reports the port it picked");
			assertNotNull(v.did());
			assertEquals(AppConfig.DEFAULT_VENUE_NAME, v.name());
			assertEquals("http://127.0.0.1:" + port + "/", v.url());

			// A named local user (u:tester) chats against the live venue...
			String userDID = Identity.of("tester").userDID(v.did());
			ChatSession chat = new ChatSession(v.clientAs(userDID), new AppConfig.Chat("bs-venue",
				AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo."), "Tester");
			assertTrue(chat.send("ping").text().contains("ping"));

			// ...and the HTTP surface is listening on loopback.
			HttpResponse<Void> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(v.url())).GET().build(),
				HttpResponse.BodyHandlers.discarding());
			assertTrue(response.statusCode() < 500, "status " + response.statusCode());
		} finally {
			v.close();
		}
		v.close(); // idempotent
	}
}
