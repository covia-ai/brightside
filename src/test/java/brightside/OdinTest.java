package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.Inbox;
import brightside.Odin;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.hitl.Hitl;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * Odin's plumbing on a real venue: he is the operator's agent, an owner's
 * assistant reaches him as the owner, he acts only through his own bridge and
 * only within its allowlists — as the venue or, by sudo, inside a user's
 * namespace — and what he asks his owner lands in the venue's inbox, which the
 * operator answers. His model is the venue's task-completing test model, so
 * requests complete without an API key.
 */
class OdinTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given — the venue's own test model. */
	private static final String TASK_LLM = "v/test/ops/taskllm";

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port));
		venue = EmbeddedVenue.launch(config);
		userDID = Identity.of("owner").userDID(venue.did());
		user = venue.clientAs(userDID);
		Odin.ensure(venue, TASK_LLM);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void odinBelongsToTheVenueAndEnsureIsIdempotent() throws Exception {
		assertNotNull(venue.agentRecord(venue.did(), Odin.AGENT_ID), "created under the venue principal");
		assertNull(venue.agentRecord(userDID, Odin.AGENT_ID), "not one of the owner's agents");

		Odin.ensure(venue, TASK_LLM); // the update path, on an existing agent
		ACell record = venue.agentRecord(venue.did(), Odin.AGENT_ID);
		assertTrue(String.valueOf(RT.getIn(record, Fields.CONFIG, "tools")).contains(Odin.OP_RUN),
			"his bridge is among his tools: " + RT.getIn(record, Fields.CONFIG, "tools"));
		assertEquals(Strings.create(TASK_LLM), RT.getIn(record, Fields.CONFIG, "llmOperation"));
	}

	@Test
	void anAssistantAsksOdinAsTheOwner() throws Exception {
		// Synchronous: the bridge submits the request as the user and returns Odin's outcome.
		ACell outcome = run(user, Odin.OP_ASK, Maps.of("request", "Please enable the example adapter."));
		assertNotNull(outcome, "Odin answered");

		// Asynchronous: the snapshot names Odin and a job the user can follow themselves.
		ACell snapshot = run(user, Odin.OP_ASK, Maps.of("request", "And the other one.", "timeout", 0L));
		assertEquals(Strings.create(Odin.address(venue.did())), RT.getIn(snapshot, "address"));
		AString id = RT.ensureString(RT.getIn(snapshot, "id"));
		assertNotNull(id, "the task job id: " + snapshot);
		assertNotNull(run(user, "v/ops/grid/job-result", Maps.of("id", id, "timeout", 20_000L)),
			"the task is the user's own job, so the user's job tools reach its result");
	}

	@Test
	void odinRunIsOdinOnlyAndAllowlisted() throws Exception {
		assertThrows(Exception.class, () -> run(user, Odin.OP_RUN, Maps.of("operation", "v/ops/venue/adapters")),
			"a user cannot borrow the venue's authority");
		assertNotNull(asOdin(Odin.OP_RUN, Maps.of("operation", "v/ops/venue/adapters")),
			"Odin may run a listed venue operation");
		assertThrows(Exception.class, () -> asOdin(Odin.OP_RUN, Maps.of("operation", "v/ops/venue/restart")),
			"an unlisted venue operation is refused");
	}

	@Test
	void odinSudoActsInsideTheUsersNamespace() throws Exception {
		run(user, "v/ops/covia/write", Maps.of("path", "w/odin-probe", "value", "hello"));

		ACell read = asOdin(Odin.OP_RUN, Maps.of(
			"operation", "v/ops/covia/read",
			"input", Maps.of("path", "w/odin-probe"),
			"user", userDID));
		assertEquals(Strings.create("hello"), RT.getIn(read, "value"), "read the user's value on their behalf: " + read);

		assertThrows(Exception.class, () -> asOdin(Odin.OP_RUN, Maps.of(
			"operation", "v/ops/venue/adapters", "user", userDID)),
			"a venue operation is not a namespace operation");
		assertThrows(Exception.class, () -> asOdin(Odin.OP_RUN, Maps.of(
			"operation", "v/ops/covia/read", "input", Maps.of("path", "w/odin-probe"),
			"user", "did:key:z6MkSomeoneElse:u:them")),
			"sudo is limited to this venue's own users");
	}

	@Test
	void odinAsksHisOwnerThroughTheVenueInbox() throws Exception {
		Job job = odinJob("v/ops/hitl/request", Hitl.request("Enable the example adapter?")
			.description("Brightside asked for it; it widens what the assistant can reach.")
			.ask(Hitl.approval("ok", "Allow?")).build());
		String id = job.getID().toHexString();

		Inbox.Request r = awaitVenueRequest(id);
		assertEquals(venue.did(), r.owner(), "it is the venue's request");
		assertEquals(Odin.AGENT_ID, r.agent(), "asked by Odin");
		assertTrue(Inbox.merge(Inbox.parse(venue.inbox(userDID), userDID),
			Inbox.parse(venue.inbox(venue.did()), venue.did())).stream().anyMatch(x -> id.equals(x.id())),
			"the merged Inbox shows it beside the owner's own");

		Inbox.Answer yes = new Inbox.Answer(Map.of("ok", true), Map.of(), List.of(), null);
		assertThrows(Exception.class, () -> Inbox.answer(user, id, yes), "the named user cannot answer the venue's request");
		Inbox.answer(venue.operator(), id, yes);
		assertEquals(CVMBool.TRUE, RT.getIn(job.future().get(10, TimeUnit.SECONDS), "answers", "ok"),
			"the operator's answer completes Odin's request");
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** Invokes as Odin's own sub-principal — exactly the context his run loop gives his tool calls. */
	private static ACell asOdin(String op, AMap<AString, ACell> input) throws Exception {
		return odinJob(op, input).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static Job odinJob(String op, AMap<AString, ACell> input) {
		RequestContext odin = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Odin.AGENT_ID));
		return venue.engine().jobs().invokeOperation(op, input, odin);
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
