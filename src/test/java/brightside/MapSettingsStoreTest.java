package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.peers.MapSettings;
import brightside.peers.MapSettingsStore;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * Peers map settings persist in Brightside's adapter state on the venue:
 * per person, out of their own namespace, and without job records.
 */
class MapSettingsStoreTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void settingsPersistPerPersonWithoutJobRecords() {
		EmbeddedVenue venue = shared.venue();
		String alice = Identity.of("map-alice").userDID(venue.did());
		String bob = Identity.of("map-bob").userDID(venue.did());
		MapSettingsStore aliceStore = venue.mapSettings(alice);
		assertEquals(MapSettings.DEFAULT, aliceStore.load(), "nothing stored yet");

		long before = RecordedJobs.of(venue, alice);
		MapSettings arranged = MapSettings.DEFAULT
			.withRingRadius(1, 10)
			.withPlacement("did:example:alice", new MapSettings.Orbit(6, 0.4))
			.withView(new MapSettings.View(0.5, true));
		aliceStore.save(arranged);
		assertEquals(before, RecordedJobs.of(venue, alice), "arranging the map leaves no job record");

		assertEquals(arranged, venue.mapSettings(alice).load(), "read back through a fresh store");
		assertEquals(MapSettings.DEFAULT, venue.mapSettings(bob).load(), "each person has their own");

		// Kept with the venue's adapter records, not in the person's own namespace.
		assertNull(venue.resolve(alice, "w/peers-map"));
		assertNull(RT.getIn(venue.resolve(alice, "w"), "adapters"));
		covia.venue.AdapterState state = venue.engine().adapterState("brightside");
		assertEquals(arranged, MapSettings.fromCell(state.read(state.userPath(
			Strings.create(alice), BrightsideAdapter.MAP_SETTINGS))));
	}

	@Test
	void agentsReadTheirOwnersMapButCannotChangeIt() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String dora = Identity.of("map-dora").userDID(venue.did());
		String eve = Identity.of("map-eve").userDID(venue.did());
		MapSettings arranged = MapSettings.DEFAULT.withRingRadius(0, 12);
		venue.mapSettings(dora).save(arranged);

		assertEquals(arranged, MapSettings.fromCell(venue.resolve(dora, "x/brightside/peers-map")));
		assertNull(venue.resolve(eve, "x/brightside/peers-map"), "another person's view is their own");

		covia.grid.Job write = venue.clientAs(dora).invoke("v/ops/covia/write", Maps.of(
			"path", "x/brightside/peers-map", "value", MapSettings.DEFAULT.toCell())).get(5, TimeUnit.SECONDS);
		Exception refused = assertThrows(Exception.class, () -> write.future().get(5, TimeUnit.SECONDS));
		assertTrue(String.valueOf(refused.getMessage()).contains(covia.adapter.AAdapter.LATTICE_READ_ONLY), refused.toString());
		assertEquals(arranged, venue.mapSettings(dora).load());
	}

	@Test
	void aDeletedPersonsMapGoesWithThem() {
		EmbeddedVenue venue = shared.venue();
		String carol = Identity.of("map-carol").userDID(venue.did());
		venue.engine().getVenueState().users().ensure(Strings.create(carol));
		venue.mapSettings(carol).save(MapSettings.DEFAULT.withRingRadius(1, 8));
		covia.venue.Engine engine = venue.engine();
		engine.deleteUser(engine.venueContext(), Strings.create(carol));

		covia.venue.AdapterState state = engine.adapterState("brightside");
		assertNull(state.read(state.userPath(Strings.create(carol), "")));
	}
}
