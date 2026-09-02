package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Venue;
import covia.venue.Config;

/**
 * The inspector's Skills tab data: every skill of every skillset the agent's
 * record names, then the hierarchy those reveal, read in-process — with a
 * later namesake marked shadowed and a child listed under its parent.
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
	void listsEverySkillTheAgentCanDiscoverThenWhatTheyReveal() throws Exception {
		String did = Identity.of("skilled").userDID(venue.did());
		Venue client = venue.clientAs(did);
		new ChatSession(client, new AppConfig.Chat("skilled-agent", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant."), "Skilled").ensureAgent();
		// The user's own library comes first, so a skill of theirs shadows a shipped namesake.
		client.invoke("v/ops/covia/write", Maps.of("path", "w/skills/coding",
			"value", Maps.of("name", "coding", "description", "How this user likes code written.")))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);
		ACell record = venue.agentRecord(did, "skilled-agent");

		assertEquals(List.of(ChatSession.USER_SKILLSET, BrightsideSkillsAdapter.SKILLSET, ChatSession.VENUE_SKILLSET),
			SkillIndex.skillsetsOf(record), "the record's skillsets, in the order the index searches them");

		long jobsBefore = RecordedJobs.of(venue, did);
		List<SkillIndex.Skill> skills = SkillIndex.of(venue, did, record);
		assertEquals(jobsBefore, RecordedJobs.of(venue, did), "a lattice read: no job record");

		// Configured skillsets first, in order; the index keeps the first name.
		SkillIndex.Skill own = one(skills, "coding", ChatSession.USER_SKILLSET);
		SkillIndex.Skill shipped = one(skills, "coding", BrightsideSkillsAdapter.SKILLSET);
		assertFalse(own.shadowed());
		assertTrue(shipped.shadowed());
		assertTrue(skills.indexOf(own) < skills.indexOf(shipped), "skillset order is kept");
		assertTrue(one(skills, "skills", ChatSession.VENUE_SKILLSET).shadowed(),
			"Brightside's skills comes before the venue's");

		// A useful first load with a child for a sub-issue: the parent has the
		// tools, and the child is listed under the parent's own path, after the
		// configured skillsets, never at the top level.
		SkillIndex.Skill moltbook = one(skills, "moltbook", BrightsideSkillsAdapter.SKILLSET);
		assertTrue(moltbook.tools().contains("v/ops/moltbook/home"), "the first load is useful: " + moltbook.tools());
		assertEquals(List.of(BrightsideSkillsAdapter.MOLTBOOK_SETUP), moltbook.children());
		SkillIndex.Skill setup = one(skills, "moltbook-setup", BrightsideSkillsAdapter.MOLTBOOK);
		assertTrue(setup.tools().contains("v/ops/moltbook/register"));
		assertFalse(setup.shadowed());
		assertTrue(skills.indexOf(setup) > skills.indexOf(moltbook));
		assertTrue(skills.stream().noneMatch(s -> "moltbook-setup".equals(s.name())
			&& BrightsideSkillsAdapter.SKILLSET.equals(s.skillset())));

		// The venue's entry points reveal their families in turn.
		SkillIndex.Skill agents = one(skills, "agents", ChatSession.VENUE_SKILLSET);
		assertEquals(List.of("v/skills/agents"), agents.children());
		assertTrue(skills.stream().noneMatch(s -> "agents".equals(s.name()) && !s.equals(agents)),
			"the same skill at its family address is listed once, not as a shadow");

		// Reveals across libraries: automation points at the venue's tasks
		// skill, research at its http skill.
		assertFalse(one(skills, "tasks", "v/skills/agents").tools().isEmpty());
		assertTrue(one(skills, "http", "v/skills/ops-tools").tools().contains("v/ops/http/get"));
	}

	private static SkillIndex.Skill one(List<SkillIndex.Skill> skills, String name, String skillset) {
		return skills.stream().filter(s -> name.equals(s.name()) && skillset.equals(s.skillset()))
			.findFirst().orElseThrow(() -> new AssertionError(name + " under " + skillset + " is listed: " + skills));
	}
}
