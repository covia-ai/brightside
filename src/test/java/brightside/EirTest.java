package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.chat.AgentSetup;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * Eir's plumbing on a real venue: she is one of the venue's gods, she repairs
 * a user's agents, skills and data only through her own bridge — never their
 * secrets, their Inbox, access grants or the gods — every repair carries a
 * reason and is reported to Odin, and Tau asks her by itself when an
 * assistant stays stopped by a fault after resuming. Her model is the
 * venue's task-completing test model, so requests complete without an API key.
 */
class EirTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given — the venue's own test model. */
	private static final String TASK_LLM = "v/test/ops/taskllm";

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		userDID = Identity.of("eir-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
		Gods.ensureAll(venue, TASK_LLM);
	}

	@Test
	void eirIsOneOfTheVenuesGodsAndStaysConfigured() {
		ACell record = venue.agentRecord(venue.did(), Eir.AGENT_ID);
		assertNotNull(record, "created under the venue principal");
		assertTrue(String.valueOf(RT.getIn(record, Fields.CONFIG, "tools")).contains(Eir.OP_RUN),
			"her bridge is among her tools: " + RT.getIn(record, Fields.CONFIG, "tools"));
		assertTrue(God.configured(record, Eir.GOD.config(TASK_LLM)), "configured as ensured");
	}

	@Test
	void eirRunIsEirsAlone() throws Exception {
		assertThrows(Exception.class, () -> run(user, Eir.OP_RUN, Maps.of("operation", "v/ops/user/list")),
			"a user cannot borrow the venue's authority");
		assertThrows(Exception.class, () -> as(Thor.AGENT_ID, Eir.OP_RUN, Maps.of("operation", "v/ops/user/list"))
			.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "nor can another god");
		assertNotNull(asEir(Maps.of("operation", "v/ops/venue/adapters")), "Eir may read the venue's setup");
	}

	@Test
	void eirResumesAStoppedAgentAndTellsOdin() throws Exception {
		String agent = "eir-patient";
		ChatSession chat = new ChatSession(user, echoChat(agent));
		assertNotNull(chat.send("hello").sessionId(), "it chats before");
		run(user, "v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, agent));

		AMap<AString, ACell> resume = Maps.of("operation", "v/ops/agent/resume",
			"input", Maps.of(Fields.AGENT_ID, agent), "user", userDID);
		assertThrows(Exception.class, () -> asEir(resume), "no repair without a reason");
		ACell resumed = asEir(resume.assoc(Strings.create("reason"), Strings.create("The owner asked for it back.")));
		assertEquals(Strings.create(Odin.address(venue.did())), RT.getIn(resumed, "report", "address"),
			"the repair is reported to Odin: " + resumed);
		assertNotNull(chat.send("back?").sessionId(), "it chats again");
	}

	@Test
	void eirRepairsAUsersSkills() throws Exception {
		asEir(Maps.of("operation", "v/ops/covia/write", "user", userDID, "reason", "A stale copy shadowed the shipped skill.",
			"input", Maps.of("path", "w/skills/eir-probe", "value", Maps.of("name", "eir-probe"))));
		asEir(Maps.of("operation", "v/ops/brightside/delete-skill", "user", userDID,
			"reason", "The owner agreed to drop the stale copy.", "input", Maps.of("name", "eir-probe")));
		ACell read = asEir(Maps.of("operation", "v/ops/covia/read", "user", userDID,
			"input", Maps.of("path", "w/skills/eir-probe")));
		assertNull(RT.getIn(read, "value"), "the skill is gone: " + read);
	}

	@Test
	void anUpgradeBringsTausPartsUpToDateAndKeepsTheOwners() throws Exception {
		String agent = "eir-upgrade";
		new ChatSession(user, echoChat(agent)).ensureAgent();
		AMap<AString, ACell> narrowed = Maps.of("caps", Vectors.of(Maps.of("with", "w/", "can", "crud/read")));
		run(user, "v/ops/agent/update", Maps.of(Fields.AGENT_ID, agent, Fields.CONFIG,
			narrowed.assoc(Strings.create("tools"), Vectors.empty())
				.assoc(Strings.create("systemPrompt"), Strings.create("The owner's own words."))));

		AMap<AString, ACell> upgrade = Maps.of("operation", "v/ops/brightside/upgrade-agent",
			"input", Maps.of(Fields.AGENT_ID, agent), "user", userDID, "reason", "Tau's setup changed.");
		ACell first = asEir(upgrade);
		assertEquals(Strings.create("updated"), RT.getIn(first, "outcome"), String.valueOf(first));
		ACell config = RT.getIn(venue.agentRecord(userDID, agent), Fields.CONFIG);
		assertEquals(RT.getIn(AgentSetup.config(AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "", null,
			null, null, null), "tools"), RT.getIn(config, "tools"), "Tau's tools are back");
		assertEquals(Strings.create("The owner's own words."), RT.getIn(config, "systemPrompt"), "the prompt is kept");
		assertEquals(narrowed.get(Strings.create("caps")), RT.getIn(config, "caps"), "and so are the caps the owner set");

		assertEquals(Strings.create("unchanged"), RT.getIn(asEir(upgrade), "outcome"), "a second upgrade finds nothing to do");
	}

	@Test
	void anUpgradeRefusesAnAgentTauDidNotMake() throws Exception {
		run(user, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "eir-foreign",
			Fields.CONFIG, Maps.of("operation", AppConfig.DEFAULT_OPERATION)));
		assertThrows(Exception.class, () -> run(user, "v/ops/brightside/upgrade-agent",
			Maps.of(Fields.AGENT_ID, "eir-foreign")), "it has no Tau context load");
		assertNull(RT.getIn(venue.agentRecord(userDID, "eir-foreign"), Fields.CONFIG, "tools"), "and is left as it was");
	}

	@Test
	void eirNeverRevealsSecretsAnswersForTheOwnerOrGrants() {
		for (String op : new String[] { "v/ops/secret/extract", "v/ops/secret/set", "v/ops/hitl/respond",
			"v/ops/agent/create", "v/ops/agent/delete", "v/ops/agent/chat", "v/ops/scheduler/schedule" }) {
			assertThrows(Exception.class, () -> asEir(Maps.of("operation", op, "user", userDID,
				"input", Maps.empty(), "reason", "Testing the allowlist.")), op + " is not hers");
		}
		for (String op : new String[] { "v/ops/ucan/issue", "v/ops/secret/set", "v/ops/user/delete" }) {
			assertThrows(Exception.class, () -> asEir(Maps.of("operation", op,
				"input", Maps.empty(), "reason", "Testing the allowlist.")), op + " as the venue is not hers");
		}
	}

	@Test
	void theGodsAreBeyondEirsReach() throws Exception {
		assertThrows(Exception.class, () -> asEir(Maps.of("operation", "v/ops/agent/update",
			"input", Maps.of(Fields.AGENT_ID, Odin.AGENT_ID, Fields.CONFIG, Maps.empty()), "reason", "Testing her reach.")),
			"changing an agent is not one of her venue operations");
		assertThrows(Exception.class, () -> asEir(Maps.of("operation", "v/ops/agent/suspend",
			"input", Maps.of(Fields.AGENT_ID, Odin.address(venue.did())), "user", userDID, "reason", "Testing her reach.")),
			"a user's sudo does not reach the venue's own agents");
		assertFalse(Suspension.deliberate(venue.agentRecord(venue.did(), Odin.AGENT_ID)), "Odin still runs");
	}

	@Test
	void anAssistantStillFaultedAfterResumingIsHandedToEir() throws Exception {
		AppConfig.Chat broken = new AppConfig.Chat("eir-faulty", AppConfig.DEFAULT_OPERATION,
			"v/ops/no/such/model", "Echo the user.");
		ChatSession chat = new ChatSession(user, broken);
		assertThrows(Exception.class, () -> chat.send("boom"), "the broken model fails the turn");
		assertNull(chat.repair(), "a first failure is Tau's to cure: it resumes on the next send");

		assertThrows(Exception.class, () -> chat.send("again"), "resuming does not mend a broken model");
		assertNotNull(chat.repair(), "so Eir is asked");
		assertNotNull(chat.repair().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "and she answers");
	}

	@Test
	void aStartAsksEirOnlyWhenItFoundSomething() {
		AMap<AString, ACell> now = Maps.of("brightside", "2", "covia", "2");
		assertNull(Eir.afterStart(new Startup.Report(null, now, List.of()), null, userDID), "a first start");
		assertNull(Eir.afterStart(new Startup.Report(now, now, List.of()), null, userDID), "the same build again");
		Startup.Report stopped = new Startup.Report(now, now, List.of("Thor is stopped."));
		assertNotNull(Eir.afterStart(stopped, null, userDID), "a problem");
		assertNull(Eir.afterStart(stopped, Eir.problems(stopped), userDID),
			"but not the same problem again, as after a restart she brought about");
		assertNotNull(Eir.afterStart(new Startup.Report(now, now, List.of("Odin is stopped.")), Eir.problems(stopped), userDID),
			"a different one is new");
		assertNotNull(Eir.afterStart(new Startup.Report(Maps.of("brightside", "1", "covia", "1"), now, List.of()), null, userDID),
			"an upgrade");
		assertNotNull(Eir.afterStart(new Startup.Report(now, now, List.of(), true), null, userDID),
			"a changed agent setup on the same version");
	}

	private static AppConfig.Chat echoChat(String agentId) {
		return new AppConfig.Chat(agentId, AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** Runs Eir's bridge as Eir's own sub-principal — exactly the context her run loop gives her tool calls. */
	private static ACell asEir(AMap<AString, ACell> input) throws Exception {
		return as(Eir.AGENT_ID, Eir.OP_RUN, input).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static Job as(String god, String op, AMap<AString, ACell> input) {
		RequestContext ctx = GodContexts.of(venue, god);
		return venue.engine().jobs().invokeOperation(op, input, ctx);
	}
}
