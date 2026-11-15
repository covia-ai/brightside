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
		List<HomePeers.Agent> agents = venue.agentsForMap(did, null);
		assertEquals(before, RecordedJobs.of(venue, did), "drawing the map leaves no job record");

		HomePeers.Agent helper = agents.stream().filter(a -> a.id().equals("helper")).findFirst().orElseThrow();
		assertEquals("Helper", helper.name(), "named as its config names it");
		assertEquals("SLEEPING", helper.state(), "a new agent is asleep until it has work");
	}

	@Test
	void theStandardAssistantIsTheAppUntilItIsNamed() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("map-standard").userDID(venue.did());
		venue.clientAs(did).invoke("v/ops/agent/create", Maps.of("agentId", "Standard")).get(30, TimeUnit.SECONDS)
			.future().get(30, TimeUnit.SECONDS);
		venue.clientAs(did).invoke("v/ops/agent/create", Maps.of("agentId", "other")).get(30, TimeUnit.SECONDS)
			.future().get(30, TimeUnit.SECONDS);

		List<HomePeers.Agent> agents = venue.agentsForMap(did, "Standard");
		assertEquals(BrightSide.APP_NAME, nameOf(agents, "Standard"), "the unnamed standard assistant goes by the app's name");
		assertEquals("other", nameOf(agents, "other"), "any other unnamed agent by its id");

		venue.clientAs(did).invoke("v/ops/agent/update", Maps.of("agentId", "Standard",
			"config", Maps.of("name", "Freya"))).get(30, TimeUnit.SECONDS).future().get(30, TimeUnit.SECONDS);
		assertEquals("Freya", nameOf(venue.agentsForMap(did, "Standard"), "Standard"), "a name given to it wins");
	}

	private static String nameOf(List<HomePeers.Agent> agents, String id) {
		return agents.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow().name();
	}
}
