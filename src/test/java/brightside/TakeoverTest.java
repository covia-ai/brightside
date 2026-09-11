package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import brightside.EmbeddedVenue;
import brightside.Takeover;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * The takeover handshake against a <em>private</em> venue — Brightside's default,
 * where public access is disabled and the status endpoint answers {@code 401} to
 * strangers. A newcomer holding the same seed must still detect the instance,
 * derive its DID, learn what it is from {@code brightside:info}, get its
 * shutdown request accepted, and see it go down; a newcomer with a different
 * seed must be refused. The port going quiet is not the store being free, so
 * the waits on the process and on the store file's lock are checked too. This
 * venue is taken down by the test, so it is the test's own.
 */
@Execution(ExecutionMode.CONCURRENT)
class TakeoverTest {

	@TempDir
	Path home;

	private EmbeddedVenue venue;

	@AfterEach
	void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void takesOverAPrivateVenueWithTheSharedSeed() throws Exception {
		String seedHex = AKeyPair.generate().getSeed().toHexString();
		AMap<AString, ACell> config = SharedVenue.config(home)
			.assoc(Strings.create("seed"), Strings.create(seedHex));

		CountDownLatch asked = new CountDownLatch(1);
		AtomicReference<EmbeddedVenue> running = new AtomicReference<>();
		venue = EmbeddedVenue.launch(config, () -> {
			asked.countDown();
			running.get().close(); // what BrightSide.exit does: flush and go
		});
		running.set(venue);
		int port = venue.port();

		assertTrue(Takeover.isRunning(port), "a private venue is detected despite answering 401 to strangers");
		assertEquals(venue.did(), Takeover.venueDIDFor(seedHex),
			"the DID derived from the seed is the DID the venue presents");

		Takeover.Instance me = Takeover.identify(port, seedHex);
		assertEquals(BrightSide.APP_NAME, me.app(), "the instance says it is Brightside");
		assertEquals(venue.did(), me.did());
		assertEquals(ProcessHandle.current().pid(), me.pid(), "the instance names its process");
		assertEquals("temp", me.store(), "the instance names its store");
		assertNull(me.storePath(), "a temp store is no path to wait on");

		String strangerSeed = AKeyPair.generate().getSeed().toHexString();
		assertThrows(Takeover.Refused.class, () -> Takeover.identify(port, strangerSeed),
			"a different identity is told so, in the owner's words");
		assertThrows(Exception.class,
			() -> Takeover.requestShutdown(port, Takeover.venueDID(port), strangerSeed),
			"a different identity cannot shut the instance down");
		assertTrue(Takeover.isRunning(port), "a refused request leaves the instance running");
		assertEquals(1, asked.getCount(), "the shutdown callback never ran for the stranger");

		Takeover.requestShutdown(port, Takeover.venueDID(port), seedHex);
		assertTrue(asked.await(10, TimeUnit.SECONDS), "the running instance was asked to shut down");
		assertTrue(Takeover.waitUntilDown(port, 15_000), "the instance stopped answering");
		assertFalse(Takeover.isRunning(port));
	}

	@Test
	void waitsForTheStoreLockToLift() throws Exception {
		Path store = home.resolve("venue.etch");
		Files.write(store, new byte[] { 1, 2, 3 });
		try (FileChannel channel = FileChannel.open(store, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock lock = channel.lock();
			// A lock still held when the time is up is the answer, at once with no time to give.
			assertFalse(Takeover.waitUntilReleased(store, 0), "a locked store is not free");
			lock.release();
		}
		assertTrue(Takeover.waitUntilReleased(store, 1_000), "a released store is free");
		assertTrue(Takeover.waitUntilReleased(home.resolve("absent.etch"), 100), "no file, nothing to wait for");
		assertTrue(Takeover.waitUntilReleased(null, 100), "a temp store has no file");
	}

	@Test
	void waitsForTheProcessToExit() {
		assertFalse(Takeover.waitUntilExited(ProcessHandle.current().pid(), 0), "this process is still here");
		assertTrue(Takeover.waitUntilExited(0, 100), "an unknown pid is not waited on");
		assertTrue(Takeover.waitUntilExited(Long.MAX_VALUE, 100), "a pid that is not alive is not waited on");
	}
}
