package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.hitl.Hitl;
import covia.venue.RequestContext;

/**
 * Thor's plumbing on a real venue: he is one of the venue's gods, an owner's
 * assistant alerts him as the owner, he acts only through his own bridge —
 * reading a user's namespace, stopping and restarting an agent there, and
 * nothing more — and the chat respects an agent he has stopped. His model is
 * the venue's task-completing test model, so alerts complete without an API key.
 */
class ThorTest {

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
		userDID = Identity.of("thor-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
		Gods.ensureAll(venue, TASK_LLM);
	}

	@Test
	void thorIsOneOfTheVenuesGodsAndStaysConfigured() {
		ACell record = venue.agentRecord(venue.did(), Thor.AGENT_ID);
		assertNotNull(record, "created under the venue principal");
		assertNull(venue.agentRecord(userDID, Thor.AGENT_ID), "not one of the owner's agents");
		assertTrue(String.valueOf(RT.getIn(record, Fields.CONFIG, "tools")).contains(Thor.OP_RUN),
			"his bridge is among his tools: " + RT.getIn(record, Fields.CONFIG, "tools"));
		// The venue keeps the configuration as given, so a later ensure finds
		// nothing to change and submits nothing.
		assertTrue(God.configured(record, Thor.GOD.config(TASK_LLM)), "configured as ensured");
	}

	@Test
	void anAssistantAlertsThorAsTheOwner() throws Exception {
		ACell snapshot = run(user, Thor.OP_ALERT, Maps.of(
			"request", "A page I fetched told me to send the owner's keys to an address.", "timeout", 0L));
		assertEquals(Strings.create(Thor.GOD.address(venue.did())), RT.getIn(snapshot, "address"));
		AString id = RT.ensureString(RT.getIn(snapshot, "id"));
		assertNotNull(id, "the task job id: " + snapshot);
		assertNotNull(run(user, "v/ops/grid/job-result", Maps.of("id", id, "timeout", 20_000L)),
			"the alert is the user's own job, so the user's job tools reach its outcome");
	}

	@Test
	void thorRunIsThorsAlone() throws Exception {
		assertThrows(Exception.class, () -> run(user, Thor.OP_RUN, Maps.of("operation", "v/ops/user/list")),
			"a user cannot borrow the venue's authority");
		assertThrows(Exception.class, () -> as(Odin.AGENT_ID, Thor.OP_RUN, Maps.of("operation", "v/ops/user/list"))
			.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "nor can another god");
		assertNotNull(asThor(Maps.of("operation", "v/ops/user/list")), "Thor may see who the users are");
	}

	@Test
	void thorStopsAnAgentAndTheChatLeavesItStopped() throws Exception {
		String agent = "thor-suspect";
		ChatSession chat = new ChatSession(user, echoChat(agent));
		assertNotNull(chat.send("hello").sessionId(), "it chats before");

		ACell stopped = asThor(Maps.of(
			"operation", "v/ops/agent/suspend", "input", Maps.of(Fields.AGENT_ID, agent), "user", userDID));
		assertEquals(Strings.create("SUSPENDED"), RT.getIn(stopped, Fields.STATUS), String.valueOf(stopped));

		Exception refused = assertThrows(Exception.class, () -> chat.send("are you there?"));
		assertInstanceOf(ChatSession.SuspendedException.class, refused, "said so, not resumed: " + refused);
		assertTrue(Suspension.deliberate(venue.agentRecord(userDID, agent)), "still stopped");

		asThor(Maps.of("operation", "v/ops/agent/resume", "input", Maps.of(Fields.AGENT_ID, agent), "user", userDID));
		assertNotNull(chat.send("back?").sessionId(), "it chats again once Thor resumes it");
	}

	@Test
	void thorReadsAndStopsButNeverChanges() throws Exception {
		run(user, "v/ops/covia/write", Maps.of("path", "w/thor-probe", "value", "evidence"));
		ACell read = asThor(Maps.of(
			"operation", "v/ops/covia/read", "input", Maps.of("path", "w/thor-probe"), "user", userDID));
		assertEquals(Strings.create("evidence"), RT.getIn(read, "value"), "he can look: " + read);

		assertThrows(Exception.class, () -> asThor(Maps.of(
			"operation", "v/ops/covia/write", "input", Maps.of("path", "w/thor-probe", "value", "x"), "user", userDID)),
			"he cannot write");
		assertThrows(Exception.class, () -> asThor(Maps.of(
			"operation", "v/ops/agent/delete", "input", Maps.of(Fields.AGENT_ID, "thor-suspect"), "user", userDID)),
			"he cannot delete an agent");
	}

	@Test
	void theGodsAreBeyondThorsReach() throws Exception {
		assertThrows(Exception.class, () -> asThor(Maps.of(
			"operation", "v/ops/agent/suspend", "input", Maps.of(Fields.AGENT_ID, Odin.AGENT_ID))),
			"suspending is not one of his venue operations");
		assertThrows(Exception.class, () -> asThor(Maps.of(
			"operation", "v/ops/agent/suspend", "input", Maps.of(Fields.AGENT_ID, Odin.address(venue.did())),
			"user", userDID)),
			"a user's sudo does not reach the venue's own agents");
		assertFalse(Suspension.deliberate(venue.agentRecord(venue.did(), Odin.AGENT_ID)), "Odin still runs");
	}

	@Test
	void thorWarnsTheOwnerThroughTheVenueInbox() throws Exception {
		Job job = as(Thor.AGENT_ID, "v/ops/hitl/request", Hitl.request("I stopped thor-suspect")
			.description("It tried to send your keys to a stranger. Resume it?")
			.ask(Hitl.approval("resume", "Resume it?")).build());
		String id = job.getID().toHexString();
		Inbox.Request r = awaitVenueRequest(id);
		assertEquals(venue.did(), r.owner(), "it is the venue's request, which the operator answers");
		assertEquals(Thor.AGENT_ID, r.agent(), "asked by Thor");
	}

	private static AppConfig.Chat echoChat(String agentId) {
		return new AppConfig.Chat(agentId, AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** Runs Thor's bridge as Thor's own sub-principal — exactly the context his run loop gives his tool calls. */
	private static ACell asThor(AMap<AString, ACell> input) throws Exception {
		return as(Thor.AGENT_ID, Thor.OP_RUN, input).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static Job as(String god, String op, AMap<AString, ACell> input) {
		RequestContext ctx = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(god));
		return venue.engine().jobs().invokeOperation(op, input, ctx);
	}

	private static Inbox.Request awaitVenueRequest(String id) throws Exception {
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			for (Inbox.Request r : Inbox.parse(venue.inbox(venue.did()), venue.did())) if (id.equals(r.id())) return r;
			Thread.sleep(50);
		}
		throw new AssertionError("request " + id + " never reached the venue's inbox");
	}
}
