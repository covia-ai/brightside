package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.grid.Job;
import covia.venue.Config;

/**
 * Compaction is asked for, and the next launch does it once, on a real venue
 * over a real store: what a value replaced leaves behind is gone afterwards,
 * and what is in use is still there. The originals repairs keep are listed
 * and removed only when asked.
 */
class StoreSpaceTest {

	@Test
	void compactionIsAskedForAndTheNextStartDoesItOnce() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path store = directory.path().resolve("venue.etch");
			AMap<AString, ACell> config = SharedVenue.config(directory.path()).assoc(Config.STORE, Strings.create(store.toString()));
			StoreSpace space = StoreSpace.of(new Config(config));
			assertNotNull(space);
			AString owner;

			// A value saved, then replaced at a later launch: Etch keeps the first
			// in the file until the store is collected.
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config)) {
				owner = Strings.create(Identity.of("compacting").userDID(venue.did()));
				BrightsideAdapter adapter = (BrightsideAdapter) venue.engine().getAdapter("brightside");
				adapter.saveMapSettings(owner, Blob.createRandom(new Random(1), 8 << 20));
			}
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config)) {
				BrightsideAdapter adapter = (BrightsideAdapter) venue.engine().getAdapter("brightside");
				adapter.saveMapSettings(owner, Strings.create("kept"));
			}

			space.compactAtNextStart(true);
			assertTrue(space.report().compactAtNextStart());
			assertTrue(space.takeRequest(), "the next launch compacts");
			assertFalse(space.compactAtNextStart(), "and the request is spent before it");
			assertFalse(space.takeRequest(), "so the launch after it does not compact again");

			StoreSpace.Compacted compacted = space.compact(new Config(config));
			assertTrue(compacted.collected(), compacted.summary());
			assertTrue(compacted.before() > 8 << 20, compacted.summary());
			assertTrue(compacted.before() - compacted.after() >= 8 << 20, "the replaced value is gone: " + compacted.summary());
			assertTrue(compacted.notice().contains("down from"), compacted.notice());
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config, compacted.store(), null)) {
				BrightsideAdapter adapter = (BrightsideAdapter) venue.engine().getAdapter("brightside");
				assertEquals(Strings.create("kept"), adapter.mapSettings(owner), "what is in use survives");
			}
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config)) {
				BrightsideAdapter adapter = (BrightsideAdapter) venue.engine().getAdapter("brightside");
				assertEquals(Strings.create("kept"), adapter.mapSettings(owner), "and is there at the next start");
			}
		}
	}

	@Test
	void anEncryptedStoreIsCompactedWithItsKey() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path store = directory.path().resolve("venue.etch");
			AMap<AString, ACell> config = StoreRepairTest.venueConfig(store, "00".repeat(32));
			EtchStore written = EtchStore.create(store.toFile(), new Config(config).getEtchConfig());
			written.setRootData(Blob.createRandom(new Random(2), 4 << 20));
			written.flush();
			written.setRootData(Strings.create("kept"));
			written.close();

			StoreSpace.Compacted compacted = new StoreSpace(store).compact(new Config(config));
			try (EtchStore running = compacted.store()) {
				assertTrue(compacted.collected() && compacted.after() < compacted.before() / 4, compacted.summary());
				assertEquals(Strings.create("kept"), running.getRootData());
			}
			try (EtchStore reopened = EtchStore.create(store.toFile(), new Config(config).getEtchConfig())) {
				assertEquals(Strings.create("kept"), reopened.getRootData(), "readable with the store's key");
			}
		}
	}

	@Test
	void aWithdrawnRequestLeavesTheLaunchAlone() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			StoreSpace space = new StoreSpace(directory.path().resolve("venue.etch"));
			space.compactAtNextStart(true);
			space.compactAtNextStart(true);
			space.compactAtNextStart(false);
			assertFalse(space.takeRequest());
		}
	}

	@Test
	void onlyTheOriginalsRepairsKeptAreRemoved() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path store = directory.path().resolve("venue.etch");
			Files.write(store, new byte[100]);
			Files.write(directory.path().resolve("venue.etch" + StoreRepair.KEPT + "20260905-174556"), new byte[300]);
			Files.write(directory.path().resolve("venue.etch" + StoreRepair.KEPT + "20261016-132043"), new byte[200]);
			// Etch's own collection files and anything else beside the store are not the owner's copies.
			Files.write(directory.path().resolve("venue.etch~1"), new byte[10]);
			Files.write(directory.path().resolve("venue.etch.checkpoint-1-x.etch"), new byte[10]);
			Files.write(directory.path().resolve("other.etch" + StoreRepair.KEPT + "20261016-132043"), new byte[10]);
			StoreSpace space = new StoreSpace(store);

			StoreSpace.Report report = space.report();
			assertEquals(100, report.storeBytes());
			assertEquals(List.of("venue.etch.unclean-20260905-174556", "venue.etch.unclean-20261016-132043"),
				report.keptOriginals().stream().map(StoreSpace.KeptOriginal::name).toList());
			assertEquals(500, report.keptBytes());

			assertEquals(2, space.removeKeptOriginals().size());
			assertTrue(space.report().keptOriginals().isEmpty());
			assertTrue(Files.exists(store) && Files.exists(directory.path().resolve("venue.etch~1"))
				&& Files.exists(directory.path().resolve("venue.etch.checkpoint-1-x.etch"))
				&& Files.exists(directory.path().resolve("other.etch" + StoreRepair.KEPT + "20261016-132043")));
		}
	}

	@Test
	void theStorageOperationIsTheOperatorsAndOdinsToRun() throws Exception {
		assertTrue(Odin.VENUE_OPERATIONS.contains("v/ops/brightside/storage"));
		assertFalse(Odin.VENUE_OPERATIONS.contains("v/ops/venue/gc"), "whose restart would reach the lifecycle");
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path store = directory.path().resolve("venue.etch");
			AMap<AString, ACell> config = SharedVenue.config(directory.path()).assoc(Config.STORE, Strings.create(store.toString()));
			try (EmbeddedVenue venue = EmbeddedVenue.launch(config)) {
				Files.write(directory.path().resolve("venue.etch" + StoreRepair.KEPT + "20261016-132043"), new byte[200]);
				var operator = venue.clientAs(venue.did());

				ACell reported = run(operator, Maps.empty());
				assertFalse(RT.bool(RT.getIn(reported, "compactAtNextStart")));
				assertEquals(1L, RT.count(RT.getIn(reported, "keptOriginals")));

				ACell asked = run(operator, Maps.of("compact", CVMBool.TRUE));
				assertTrue(RT.bool(RT.getIn(asked, "compactAtNextStart")));
				assertTrue(new StoreSpace(store).compactAtNextStart(), "the launch sees the request");

				ACell removed = run(operator, Maps.of("removeKeptOriginals", CVMBool.TRUE));
				assertEquals(1L, RT.count(RT.getIn(removed, "removed")));
				assertEquals(0L, RT.count(RT.getIn(removed, "keptOriginals")));

				String userDID = Identity.of("not-the-operator").userDID(venue.did());
				Job denied = venue.clientAs(userDID).invoke("v/ops/brightside/storage", Maps.of("compact", CVMBool.FALSE))
					.get(5, TimeUnit.SECONDS);
				assertThrows(Exception.class, () -> denied.future().get(5, TimeUnit.SECONDS));
				assertTrue(new StoreSpace(store).compactAtNextStart(), "a user cannot withdraw it either");
			}
		}
	}

	private static ACell run(covia.grid.Venue operator, ACell input) throws Exception {
		Job job = operator.invoke("v/ops/brightside/storage", input).get(5, TimeUnit.SECONDS);
		return job.future().get(5, TimeUnit.SECONDS);
	}
}
