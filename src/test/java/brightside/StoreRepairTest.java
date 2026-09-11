package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.etch.EtchStore;
import covia.venue.Config;

/**
 * A store left open by a crash is refused by Etch v3, and the repair puts a
 * fresh, cleanly closed copy in its place with the last synced state intact
 * and the old file kept beside it. The crash is real: a child JVM writes,
 * syncs, writes again and halts without closing.
 */
class StoreRepairTest {

	/** Writes to the store and dies without closing it — what a crash or a stopped launch leaves behind. */
	public static final class Crash {
		public static void main(String[] args) throws Exception {
			Config c = new Config(venueConfig(Path.of(args[0]), args[1]));
			EtchStore store = EtchStore.create(new File(c.getStore()), c.getEtchConfig());
			store.setRootData(Strings.create("saved"));
			store.flush(); // a clean checkpoint: the header says so
			store.setRootData(Strings.create("unsaved")); // the header now says open
			Runtime.getRuntime().halt(0); // no close, no shutdown hooks
		}
	}

	static AMap<AString, ACell> venueConfig(Path store, String keyHex) {
		return Maps.of(
			Config.STORE, store.toString(),
			"etch", Maps.of("version", 3L, "cipher", "chacha20", "key", keyHex));
	}

	@Test
	void repairsAStoreLeftOpenByACrashAndKeepsTheOldFile() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path store = directory.path().resolve("venue.etch");
			byte[] key = new byte[32];
			new SecureRandom().nextBytes(key);
			String keyHex = HexFormat.of().formatHex(key);
			AMap<AString, ACell> venueConfig = venueConfig(store, keyHex);
			crash(store, keyHex);

			// Etch refuses the file, in the words the launch looks for.
			IOException refused = assertThrows(IOException.class,
				() -> EtchStore.create(store.toFile(), new Config(venueConfig).getEtchConfig()).close());
			assertTrue(StoreRepair.isUncleanClose(new RuntimeException("Failed to create venue engine", refused)),
				refused.getMessage());
			assertFalse(StoreRepair.isUncleanClose(new IOException("File lock failed on venue.etch")));

			StoreRepair.Outcome outcome = StoreRepair.repair(venueConfig);
			assertEquals(store.toAbsolutePath(), outcome.store());
			assertTrue(Files.isRegularFile(outcome.kept()), "the old file is kept beside the store");
			assertTrue(Files.size(outcome.kept()) > 0);

			// The fresh store opens normally, cleanly closed, with the last synced root.
			try (EtchStore repaired = EtchStore.create(store.toFile(), new Config(venueConfig).getEtchConfig())) {
				ACell root = repaired.refForHash(repaired.getRootHash()).getValue();
				assertEquals(Strings.create("saved"), root);
			}
			try (EtchStore again = EtchStore.create(store.toFile(), new Config(venueConfig).getEtchConfig())) {
				assertEquals(Strings.create("saved"), again.refForHash(again.getRootHash()).getValue());
			}
		}
	}

	/** Runs {@link Crash} in its own JVM, so the halt is a real one. */
	private static void crash(Path store, String keyHex) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
			Crash.class.getName(), store.toString(), keyHex).inheritIO().start();
		assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the crashing writer finished");
		assertEquals(0, p.exitValue(), "the writer halted as intended");
		assertTrue(Files.size(store) > 0, "the crash left a store behind");
	}
}
