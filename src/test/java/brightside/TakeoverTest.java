package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Takeover;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;

/**
 * The takeover handshake against a <em>private</em> venue — Brightside's default,
 * where public access is disabled and the status endpoint answers {@code 401} to
 * strangers. A newcomer holding the same seed must still detect the instance,
 * derive its DID, get its shutdown request accepted, and see it go down; a
 * newcomer with a different seed must be refused.
 */
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
		int port = freePort();
		String seedHex = AKeyPair.generate().getSeed().toHexString();
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port))
			.assoc(Strings.create("seed"), Strings.create(seedHex));

		CountDownLatch asked = new CountDownLatch(1);
		AtomicReference<EmbeddedVenue> running = new AtomicReference<>();
		venue = EmbeddedVenue.launch(config, () -> {
			asked.countDown();
			running.get().close(); // what BrightSide.exit does: flush and go
		});
		running.set(venue);

		assertTrue(Takeover.isRunning(port), "a private venue is detected despite answering 401 to strangers");
		assertEquals(venue.did(), Takeover.venueDIDFor(seedHex),
			"the DID derived from the seed is the DID the venue presents");

		String strangerSeed = AKeyPair.generate().getSeed().toHexString();
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

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
