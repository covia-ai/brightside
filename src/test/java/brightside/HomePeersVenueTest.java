package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.peers.HomePeers;
import convex.core.data.Maps;
import covia.grid.Job;

/**
 * The Peers map's home system read from a real venue: a user's agents, with
 * their names and run states, read in-process without leaving job records.
 */
class HomePeersVenueTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void yourAgentsAreReadLiveWithoutJobRecords() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("map-home").userDID(venue.did());
		Job job = venue.clientAs(did).invoke("v/ops/agent/create", Maps.of(
			"agentId", "helper",
			"config", Maps.of("name", "Helper"))).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);

		long before = RecordedJobs.of(venue, did);
		List<HomePeers.Agent> agents = venue.agentsForMap(did);
		assertEquals(before, RecordedJobs.of(venue, did), "drawing the map leaves no job record");

		HomePeers.Agent helper = agents.stream().filter(a -> a.id().equals("helper")).findFirst().orElseThrow();
		assertEquals("Helper", helper.name(), "named as its config names it");
		assertEquals("SLEEPING", helper.state(), "a new agent is asleep until it has work");
	}
}
