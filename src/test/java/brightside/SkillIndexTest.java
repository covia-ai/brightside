package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Venue;
import covia.venue.Config;

/**
 * The inspector's Skills tab data: every skill of every skillset the agent's
 * record names, read in-process, with a later namesake marked shadowed.
 */
class SkillIndexTest {

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
	void listsEverySkillTheAgentCanDiscoverInSkillsetOrder() throws Exception {
		String did = Identity.of("skilled").userDID(venue.did());
		Venue client = venue.clientAs(did);
		new ChatSession(client, new AppConfig.Chat("skilled-agent", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant."), "Skilled").ensureAgent();
		ACell record = venue.agentRecord(did, "skilled-agent");

		assertEquals(List.of(ChatSession.USER_SKILLSET, BrightsideSkillsAdapter.SKILLSET, ChatSession.VENUE_SKILLSET),
			SkillIndex.skillsetsOf(record), "the record's skillsets, in the order the index searches them");

		long jobsBefore = RecordedJobs.of(venue, did);
		List<SkillIndex.Skill> skills = SkillIndex.of(venue, did, record);
		assertEquals(jobsBefore, RecordedJobs.of(venue, did), "a lattice read: no job record");

		SkillIndex.Skill moltbook = skills.stream()
			.filter(s -> "moltbook".equals(s.name()) && BrightsideSkillsAdapter.SKILLSET.equals(s.skillset()))
			.findFirst().orElseThrow(() -> new AssertionError("Brightside's moltbook skill is listed: " + skills));
		assertEquals(BrightsideSkillsAdapter.SKILLSET + "/moltbook", moltbook.path());
		// A router: no tools of its own, and the children it reveals — the ones
		// whose tools wait for a load — are named, not listed at the top level.
		assertTrue(moltbook.tools().isEmpty(), "a router grants nothing itself: " + moltbook.tools());
		assertEquals(List.of(BrightsideSkillsAdapter.MOLTBOOK_ACTIVITY, BrightsideSkillsAdapter.MOLTBOOK_SETUP),
			moltbook.children(), "what it reveals");
		assertTrue(skills.stream().noneMatch(s -> "moltbook-activity".equals(s.name())),
			"a child is not discoverable until its parent is loaded");
		assertFalse(moltbook.shadowed());
		assertTrue(moltbook.description() != null && !moltbook.description().isBlank());

		// Children of both kinds are reported: the venue's entry points reveal
		// whole skillsets, Brightside's routers individual skills.
		SkillIndex.Skill agents = skills.stream()
			.filter(s -> "agents".equals(s.name()) && ChatSession.VENUE_SKILLSET.equals(s.skillset()))
			.findFirst().orElseThrow();
		assertEquals(List.of("v/skills/agents"), agents.children());

		// Brightside's own "skills" comes before the venue's, so the venue's is shadowed — as the index dedups.
		SkillIndex.Skill own = skills.stream()
			.filter(s -> "skills".equals(s.name()) && BrightsideSkillsAdapter.SKILLSET.equals(s.skillset()))
			.findFirst().orElseThrow();
		SkillIndex.Skill venues = skills.stream()
			.filter(s -> "skills".equals(s.name()) && ChatSession.VENUE_SKILLSET.equals(s.skillset()))
			.findFirst().orElseThrow();
		assertFalse(own.shadowed());
		assertTrue(venues.shadowed());
		assertTrue(skills.indexOf(own) < skills.indexOf(venues), "skillset order is kept");

		// The user's own skillset is empty for a fresh user and simply contributes nothing.
		assertTrue(skills.stream().noneMatch(s -> ChatSession.USER_SKILLSET.equals(s.skillset())));
	}
}
