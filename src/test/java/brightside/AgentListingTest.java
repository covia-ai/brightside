package brightside;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;

/**
 * The read behind the agents pane: an agent created through the venue's own
 * {@code agent:create} operation — exactly what the assistant's agent tools
 * call — appears in the in-process {@code g/} listing the pane is built from.
 */
class AgentListingTest {

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port));
		venue = EmbeddedVenue.launch(config);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void anAgentCreatedByToolsAppearsInTheUsersListing() throws Exception {
		String did = Identity.of("owner").userDID(venue.did());
		Venue user = venue.clientAs(did);
		Job job = user.invoke("v/ops/agent/create", Maps.of(
			"agentId", "Amy",
			"config", Maps.of("name", "Amy"))).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);

		AMap<AString, ACell> agents = venue.agents(did);
		assertNotNull(agents, "the agents map reads in-process");
		assertNotNull(agents.get(Strings.create("Amy")),
			"the created agent is in the listing the pane reads");
		assertNotNull(venue.agentRecord(did, "Amy"), "its record reads in-process");
	}
}
