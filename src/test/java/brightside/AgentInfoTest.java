package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AgentInfo;
import brightside.AppConfig;
import brightside.BrightSide;
import brightside.BrightsideSkillsAdapter;
import brightside.EmbeddedVenue;
import brightside.Identity;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;

/**
 * Mechanism test for the agent info screen and agent deletion: what
 * {@link AgentInfo} reports comes from a real record, and {@code agent:delete}
 * as Brightside calls it removes the record, not just marks it terminated.
 */
class AgentInfoTest {

	private static final long TIMEOUT_SECONDS = 30;

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;
	private static Venue client;
	private static String userDID;

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
		userDID = Identity.of("info-test").userDID(venue.did());
		client = venue.clientAs(userDID);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void summaryComesFromTheRecordAndDeletionRemovesIt() throws Exception {
		String agentId = "Bob";
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
			"llmOperation", AppConfig.ECHO_LLM_OPERATION,
			"systemPrompt", "You are Bob.",
			"defaultTools", true,
			"tools", Vectors.of(Strings.create("v/ops/memory")),
			"skillsets", Vectors.of(Strings.create("w/skills")),
			Fields.LOADS, Maps.of(BrightsideSkillsAdapter.SKILLS,
				Maps.of("skill", true, "budget", 8000L, "label", "Skills")));
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config));

		AgentInfo.Summary info = AgentInfo.load(client, venue.agentRecord(userDID, agentId),
			agentId, userDID, "Info Test", false);
		assertNotNull(info);
		assertEquals(agentId, info.id());
		assertEquals(agentId, info.name(), "no config.name: the id is the name");
		assertEquals(userDID + ":g:" + agentId, info.did());
		assertEquals("SLEEPING", info.status());
		assertNull(info.error());
		assertEquals(AppConfig.ECHO_LLM_OPERATION, info.model());
		assertEquals(AppConfig.DEFAULT_OPERATION, info.operation());
		assertEquals("You are Bob.", info.systemPrompt());
		assertTrue(info.defaultTools());
		assertEquals(List.of("v/ops/memory"), info.tools());
		assertEquals(List.of("w/skills"), info.skillsets());
		assertEquals(List.of(new AgentInfo.Pin(BrightsideSkillsAdapter.SKILLS, "Skills", "skill", 8000L)), info.pins());
		assertTrue(info.unavailable().isEmpty(), "unavailable: " + info.unavailable());
		assertEquals(0, info.conversations());
		assertEquals(0, info.lastActive());
		assertTrue(listedAgents().contains(agentId));

		BrightSide.deleteAgent(client, agentId);

		assertNull(venue.agentRecord(userDID, agentId), "the record is removed, not merely terminated");
		assertNull(AgentInfo.load(client, null, agentId, userDID, "Info Test", false));
		assertFalse(listedAgents().contains(agentId));
	}

	private static List<String> listedAgents() throws Exception {
		List<String> ids = new ArrayList<>();
		if (RT.getIn(run("v/ops/agent/list", Maps.empty()), "agents") instanceof AVector<?> agents) {
			for (long i = 0; i < agents.count(); i++) {
				ids.add(String.valueOf(RT.getIn((ACell) agents.get(i), Fields.AGENT_ID)));
			}
		}
		return ids;
	}

	private static ACell run(String operation, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(operation, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
