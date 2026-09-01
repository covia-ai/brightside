package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Config;

/**
 * What a screen reads leaves nothing behind: lattice paths resolve straight
 * from the in-process engine, and a computed read such as the Discord bot's
 * status runs as a transient job — no record in the user's namespace.
 */
class InProcessReadsTest {

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
		assertEquals(List.of("v/ops/moltbook/read-post", "v/ops/moltbook/read/post"),
			BrightSide.toolPathCandidates("moltbook_read_post"));
		// The first candidate that resolves carries the human name the bubble shows.
		ACell asset = venue.resolve(did, BrightSide.toolPathCandidates("moltbook_home").get(0));
		assertEquals(Strings.create("Moltbook home"), RT.getIn(asset, "name"));
	}

	@Test
	void discordBotStatusIsAReadWithNoJobRecord() throws Exception {
		String did = Identity.of("watcher").userDID(venue.did());
		long before = RecordedJobs.of(venue, did);
		assertNull(Discord.status(venue.clientAs(did), null), "no bot configured reports null");
		assertEquals(before, RecordedJobs.of(venue, did), "the read leaves no job record");
	}
}
