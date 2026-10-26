package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * What a screen reads leaves nothing behind: lattice paths resolve straight
 * from the in-process engine, and a computed read such as the Discord bot's
 * status runs as a transient job — no record in the user's namespace.
 */
class InProcessReadsTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void toolNamesResolveToCatalogueLabels() {
		String did = Identity.of("labeller").userDID(venue.did());
		assertEquals(List.of("v/ops/moltbook/read-post", "v/ops/moltbook/read/post"),
			brightside.ui.chat.ActivityLabels.toolPathCandidates("moltbook_read_post"));
		// The first candidate that resolves carries the human name the bubble shows.
		ACell asset = venue.resolve(did, brightside.ui.chat.ActivityLabels.toolPathCandidates("moltbook_home").get(0));
		assertEquals(Strings.create("Moltbook home"), RT.getIn(asset, "name"));
	}

	@Test
	void telegramRunsInProcessFromBrightsidesOwnBuild() throws Exception {
		var adapter = venue.engine().getAdapter("telegram");
		assertEquals(covia.adapter.telegram.TelegramAdapter.class, adapter.getClass());
		assertEquals(EmbeddedVenue.class.getClassLoader(), adapter.getClass().getClassLoader(),
			"the module on Brightside's classpath, not a jar loaded beside it");
		String did = Identity.of("telegram-watcher").userDID(venue.did());
		ACell bots = venue.clientAs(did).run("v/ops/telegram/bots", convex.core.data.Maps.empty())
			.get(30, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(0, RT.count(RT.getIn(bots, "bots")), "idle until a bot is created");
	}

	@Test
	void discordBotStatusIsAReadWithNoJobRecord() throws Exception {
		String did = Identity.of("watcher").userDID(venue.did());
		long before = RecordedJobs.of(venue, did);
		assertNull(Discord.status(venue.clientAs(did)), "no bot configured reports null");
		assertEquals(before, RecordedJobs.of(venue, did), "the read leaves no job record");
	}
}
