package brightside;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Config;

/**
 * Live adapter state reads without a job: visiting Settings polls the Discord
 * adapter's bot status through {@link EmbeddedVenue#invokeAdapterDirect}, which
 * calls the adapter in-process — nothing goes through the JobManager, so a
 * screen visit writes nothing durable.
 */
class DirectAdapterReadTest {

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
	void toolNamesResolveToCatalogueLabels() {
		String did = Identity.of("labeller").userDID(venue.did());
		org.junit.jupiter.api.Assertions.assertEquals(
			java.util.List.of("v/ops/moltbook/read-post", "v/ops/moltbook/read/post"),
			BrightSide.toolPathCandidates("moltbook_read_post"));
		// The first candidate that resolves carries the human name the bubble shows.
		ACell asset = venue.resolve(did, BrightSide.toolPathCandidates("moltbook_home").get(0));
		org.junit.jupiter.api.Assertions.assertEquals(Strings.create("Moltbook home"),
			RT.getIn(asset, "name"));
	}

	@Test
	void discordBotStatusReadsInProcess() throws Exception {
		String did = Identity.of("watcher").userDID(venue.did());
		ACell out = venue.invokeAdapterDirect("discord:bots", did, Maps.empty(), 10);
		assertTrue(RT.getIn(out, "bots") instanceof AVector<?> bots && bots.isEmpty(),
			"a fresh user has no bots, and the read needs no job");
		assertNull(Discord.status(venue, did, null), "no bot configured reports null");
	}
}
