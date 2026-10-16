package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.peers.MapSettings;
import brightside.peers.MapSettingsStore;
import convex.core.lang.RT;

/**
 * Peers map settings persist in Brightside's adapter workspace on the venue:
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
			.withOrbitRadius(1, 0.6)
			.withPlacement("did:example:alice", new MapSettings.OnOrbit(1.2, 0.4))
			.withView(new MapSettings.View(0.5, true));
		aliceStore.save(arranged);
		assertEquals(before, RecordedJobs.of(venue, alice), "arranging the map leaves no job record");

		assertEquals(arranged, venue.mapSettings(alice).load(), "read back through a fresh store");
		assertEquals(MapSettings.DEFAULT, venue.mapSettings(bob).load(), "each person has their own");

		// Kept with the venue's adapter records, not in the person's own namespace.
		assertNull(venue.resolve(alice, "w/peers-map"));
		assertNull(RT.getIn(venue.resolve(alice, "w"), "adapters"));
		assertEquals(arranged, MapSettings.fromCell(venue.resolve(venue.did(),
			"w/adapters/brightside/users/" + alice + "/" + BrightsideAdapter.MAP_SETTINGS)));
	}
}
