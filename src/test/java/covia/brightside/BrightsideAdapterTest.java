package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import convex.core.data.prim.CVMBool;

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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * Boots a real venue via {@link EmbeddedVenue} (which registers both Brightside
 * adapters) and checks the {@code brightside:info} op and the seeded skillset.
 */
class BrightsideAdapterTest {

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
	void infoReportsAppAndVenue() throws Exception {
		Venue client = venue.clientAs(venue.did());
		Job job = client.invoke("v/ops/brightside/info", Maps.empty()).get(5, TimeUnit.SECONDS);
		ACell result = job.future().get(5, TimeUnit.SECONDS);
		assertNotNull(result);
		assertEquals(Strings.create("Brightside"), RT.getIn(result, "app"));
		assertEquals(Strings.create(venue.did()), RT.getIn(result, "did"));
		assertNotNull(RT.getIn(result, "version"), "reports a version");
		assertTrue(RT.getIn(result, "skills").toString().contains("introduction"));
	}

	@Test
	void shutdownIsVenueOperatorOnly() throws Exception {
		// The operator (a caller authenticated as the venue's own DID) is accepted.
		// This test venue is launched without a shutdown callback, so nothing exits.
		Venue operator = venue.clientAs(venue.did());
		Job accepted = operator.invoke("v/ops/brightside/shutdown", Maps.empty()).get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(accepted.future().get(5, TimeUnit.SECONDS), "accepted"));

		// A normal user is rejected.
		String userDID = Identity.of("intruder").userDID(venue.did());
		Job denied = venue.clientAs(userDID).invoke("v/ops/brightside/shutdown", Maps.empty())
			.get(5, TimeUnit.SECONDS);
		assertThrows(Exception.class, () -> denied.future().get(5, TimeUnit.SECONDS),
			"a non-operator cannot shut the process down");
	}

	@Test
	void seedsTheDefaultSkillset() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String path : new String[] {
			BrightsideSkillsAdapter.INTRODUCTION,
			BrightsideSkillsAdapter.SKILLS,
			BrightsideSkillsAdapter.SKILL_AUTHORING }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(path), ctx), "skill present: " + path);
		}
	}
}
