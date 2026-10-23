package brightside.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import brightside.AppConfig;
import brightside.BrightsideSkillsAdapter;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.SessionHistory;
import brightside.SharedVenue;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.adapter.TestAdapter;
import covia.adapter.agent.AbstractLLMAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.Engine;

/**
 * Chats through the agent framework of a real venue, using the echo test LLM.
 * Every test has an agent of its own, so they run side by side — as one owner's
 * agents do.
 */
@Execution(ExecutionMode.CONCURRENT)
class ChatSessionTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static Engine engine;
	private static Venue venue;

	@BeforeAll
	static void boot() {
		EmbeddedVenue embedded = shared.venue();
		engine = embedded.engine();
		// Act as a named local user sub-principal of the venue, as BrightSide does.
		venue = embedded.clientAs(Identity.of("chatter").userDID(embedded.did()));
	}

	private static AppConfig.Chat echoChat(String agentId) {
		return new AppConfig.Chat(agentId, AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
	}

	@Test
	void preparingTheAgentDoesNotCreateAConversationSession() throws Exception {
		ChatSession s = new ChatSession(venue, echoChat("bs-empty-home"));
		s.ensureAgent();

		assertNull(s.sessionId());
		assertTrue(SessionHistory.listSessions(venue, "bs-empty-home").isEmpty());
	}

	@Test
	void toolCallsMayRunAsLongAsDelegatedWork() throws Exception {
		String agentId = "bs-tool-budget";
		new ChatSession(venue, echoChat(agentId)).ensureAgent();

		// What the agent loop resolves from the stored configuration, by Covia's own rule.
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> config = (AMap<AString, ACell>) RT.getIn(agentInfo(agentId), Fields.CONFIG);
		assertEquals(ChatSession.TOOL_CALL_TIMEOUT_MS, AbstractLLMAdapter.resolveToolCallTimeoutMs(config));
		assertTrue(ChatSession.TOOL_CALL_TIMEOUT_MS > AbstractLLMAdapter.DEFAULT_TOOL_CALL_TIMEOUT_MS,
			"longer than the agent loop's default");
	}

	@Test
	void onDemandSkillsAreDiscoverableWithoutBeingPinned() throws Exception {
		String agentId = "bs-child-skills";
		ChatSession s = new ChatSession(venue, echoChat(agentId), "Tester");
		s.ensureAgent();

		ACell info = agentInfo(agentId);
		assertTrue(vectorContains(RT.getIn(info, Fields.CONFIG, "skillsets"),
			Strings.create(BrightsideSkillsAdapter.SKILLSET)),
			"the complete Brightside skillset is an on-demand source");
		assertTrue(vectorContains(RT.getIn(info, Fields.CONFIG, "tools"),
			Strings.create("v/ops/brightside/report-skill-feedback")),
			"the narrow feedback channel is always available");
		assertTrue(vectorContains(RT.getIn(info, Fields.CONFIG, "tools"), Strings.create(ChatSession.INSPECT_OP)),
			"the bounded inspect is always available: the default pack has only read and list");
		AMap<AString, ACell> loads = configuredLoads(info);
		for (String skill : BrightsideSkillsAdapter.SHIPPED) {
			assertFalse(loads.containsKey(Strings.create(skill)),
				"no shipped Brightside skill is pinned by default: " + skill);
		}
		ACell appContext = RT.getIn(info, Fields.CONFIG, Fields.LOADS,
			Strings.create(ChatSession.CONTEXT_LOAD_KEY));
		assertNotNull(appContext, "the app context is a configured non-skill load");
		assertEquals(Strings.create(ChatSession.CONTEXT_OP), RT.getIn(appContext, "op"));
		assertEquals(Strings.create(AppConfig.ECHO_LLM_OPERATION),
			RT.getIn(appContext, "input", "modelOperation"));
		assertTrue(RT.getIn(appContext, "input", "userName") instanceof convex.core.data.AString);
	}

	@Test
	void writesTheAgentsNameToItsRecord() throws Exception {
		String agentId = "bs-named";
		new ChatSession(venue, echoChat(agentId)).named("My Helper").ensureAgent();

		assertEquals(Strings.create("My Helper"), RT.getIn(agentInfo(agentId), Fields.CONFIG, "name"));
		assertTrue(SessionHistory.listSessions(venue, agentId).isEmpty(), "creation opens no conversation");
	}

	@Test
	void memoryIsPinnedThroughAReadOnlyOperation() throws Exception {
		String agentId = "bs-memory-pin";
		new ChatSession(venue, echoChat(agentId)).ensureAgent();

		ACell context = RT.getIn(agentInfo(agentId), Fields.CONFIG, "context");
		assertTrue(context instanceof AVector<?> v && v.count() == 2, String.valueOf(context));
		// What the owner is like, shared by all their assistants, then this one's own.
		List<String> paths = new java.util.ArrayList<>();
		for (ACell pin : (AVector<ACell>) context) {
			assertEquals(Strings.create(ChatSession.MEMORY_RECALL_OP), RT.getIn(pin, "op"));
			paths.add(String.valueOf(RT.getIn(pin, "input", "path")));
		}
		assertEquals(List.of(ChatSession.SHARED_MEMORY_PATH, ChatSession.MEMORY_PATH), paths);

		// Shared memory written by one assistant reaches another's assembled context.
		venue.invoke("v/ops/memory", Maps.of("path", ChatSession.SHARED_MEMORY_PATH, "command", "remember",
			"text", "Prefers British English")).get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);
		new ChatSession(venue, echoChat("bs-memory-other")).ensureAgent();
		ACell assembled = venue.invoke("v/ops/agent/context", Maps.of("agentId", "bs-memory-other"))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);
		assertTrue(String.valueOf(assembled).contains("Prefers British English"), "the other assistant sees it");

		// A context entry runs before every inference, and the venue admits only
		// an op declared readOnly there (covia#465): the read/write memory tool
		// is refused, so it must not be what the pin names.
		assertTrue(declaresReadOnly(ChatSession.MEMORY_RECALL_OP), "the pinned op is declared readOnly");
		assertFalse(declaresReadOnly("v/ops/memory"), "the memory tool is not");
	}

	@Test
	void anAgentMadeToSpecialiseStartsWithItsSkillPinnedAndKeepsIt() throws Exception {
		String agentId = "bs-researcher";
		String skill = BrightsideSkillsAdapter.RESEARCH;
		new ChatSession(venue, echoChat(agentId)).pinning(skill).ensureAgent();
		ACell pin = RT.getIn(agentInfo(agentId), Fields.CONFIG, Fields.LOADS, skill);
		assertEquals(CVMBool.TRUE, RT.getIn(pin, "skill"), String.valueOf(pin));

		// Opened again later, as any chat is, the pin stays: it is the owner's.
		new ChatSession(venue, echoChat(agentId)).ensureAgent();
		assertNotNull(RT.getIn(agentInfo(agentId), Fields.CONFIG, Fields.LOADS, skill));
		assertNotNull(venue.invoke("v/ops/agent/context", Maps.of("agentId", agentId))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS), "the pinned skill loads");
	}

	@Test
	void migratesAMemoryContextPinOnAnAgentItDoesNotOtherwiseReconfigure() throws Exception {
		String agentId = "bs-old-memory-pin";
		// An agent created by an earlier Brightside, or from a venue template of
		// the time: its memory pinned through the read/write tool, which the venue
		// now refuses at every inference.
		venue.invoke("v/ops/agent/create", Maps.of(
			"agentId", agentId,
			"config", Maps.of(
				Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
				"llmOperation", AppConfig.ECHO_LLM_OPERATION,
				"systemPrompt", "Echo the user.",
				"context", convex.core.data.Vectors.of(Maps.of(
					"op", "v/ops/memory",
					"input", Maps.of("path", ChatSession.MEMORY_PATH, "command", "recall"),
					"label", "Agent memory (edit using path n/memory)")))))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);

		// Not the configured assistant: Brightside leaves its configuration as
		// the record holds it — except for this pin, which would brick every chat.
		assertTrue(new ChatSession(venue, echoChat(agentId), null, false).ensureAgent());

		ACell context = RT.getIn(agentInfo(agentId), Fields.CONFIG, "context");
		assertTrue(context instanceof AVector<?> v && v.count() == 1, String.valueOf(context));
		ACell pin = ((AVector<?>) context).get(0);
		assertEquals(Strings.create(ChatSession.MEMORY_RECALL_OP), RT.getIn(pin, "op"));
		assertEquals(Strings.create(ChatSession.MEMORY_PATH), RT.getIn(pin, "input", "path"));
		assertNull(RT.getIn(pin, "input", "command"));
		assertEquals(Strings.create("Agent memory (edit using path n/memory)"), RT.getIn(pin, "label"));
		// And the venue assembles the agent's context again — the failure the owner saw.
		assertNotNull(venue.invoke("v/ops/agent/context", Maps.of("agentId", agentId))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS));
	}

	private static boolean declaresReadOnly(String op) {
		Asset asset = engine.resolveAsset(Strings.create(op), engine.venueContext());
		assertNotNull(asset, "resolves on the venue: " + op);
		return CVMBool.TRUE.equals(RT.getIn(asset.meta(), Fields.OPERATION, Fields.READ_ONLY));
	}

	@Test
	void migratesTheLegacyPinnedBaselineWithoutDroppingOtherPins() throws Exception {
		String agentId = "bs-introduction-migration";
		new ChatSession(venue, echoChat(agentId)).ensureAgent();
		String customPin = "v/skills/root/covia";
		venue.invoke("v/ops/agent/update", Maps.of(
			"agentId", agentId,
			"config", Maps.of("loads", Maps.of(
				BrightsideSkillsAdapter.SKILLSET + "/identity",
				Maps.of("skill", true, "budget", 2200L, "label", "identity"),
				BrightsideSkillsAdapter.INTRODUCTION,
				Maps.of("skill", true, "budget", 4000L, "label", "introduction"),
				BrightsideSkillsAdapter.SKILLS,
				Maps.of("skill", true, "budget", 2000L, "label", "skills"),
				customPin, Maps.of("skill", true, "budget", 1000L, "label", "custom")))))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);

		new ChatSession(venue, echoChat(agentId)).ensureAgent();
		AMap<AString, ACell> loads = configuredLoads(agentInfo(agentId));
		assertTrue(loads.containsKey(Strings.create(customPin)),
			"owner-configured pins survive the migration");
		assertTrue(loads.containsKey(Strings.create(ChatSession.CONTEXT_LOAD_KEY)));
		assertFalse(loads.containsKey(Strings.create(BrightsideSkillsAdapter.INTRODUCTION)));
		assertFalse(loads.containsKey(Strings.create(BrightsideSkillsAdapter.SKILLS)));
		assertFalse(loads.containsKey(Strings.create(BrightsideSkillsAdapter.SKILLSET + "/identity")));
	}

	@Test
	void repliesAndKeepsTheSessionUntilReset() throws Exception {
		ChatSession s = new ChatSession(venue, echoChat("bs-echo"));
		assertNull(s.sessionId());

		ChatSession.Reply first = s.send("hello from brightside");
		assertNotNull(first.sessionId());
		assertEquals(first.sessionId(), s.sessionId());
		assertTrue(first.text().contains("hello from brightside"), first.text());

		ChatSession.Reply second = s.send("second message");
		assertEquals(first.sessionId(), second.sessionId(), "same conversation");

		s.reset();
		assertNull(s.sessionId());
		ChatSession.Reply third = s.send("third message");
		assertNotEquals(first.sessionId(), third.sessionId(), "new conversation");
	}

	@Test
	void followUpEntersTheVenueQueueWhileAReplyIsInFlight() throws Exception {
		String agentId = "bs-queued-follow-up";
		AppConfig.Chat chat = new AppConfig.Chat(agentId, "v/test/ops/taskcomplete",
			AppConfig.ECHO_LLM_OPERATION, "");
		ChatSession session = new ChatSession(venue, chat);
		session.ensureAgent();

		String gateName = "brightside-queued-follow-up";
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			venue.invoke("v/ops/agent/update", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of("testGate", gateName)))
				.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);

			AtomicReference<String> acceptedSession = new AtomicReference<>();
			CountDownLatch accepted = new CountDownLatch(1);
			CompletableFuture<ChatSession.Reply> first = CompletableFuture.supplyAsync(() -> {
				try {
					return session.send("first", sid -> {
						acceptedSession.set(sid);
						accepted.countDown();
					});
				} catch (Exception e) {
					throw new java.util.concurrent.CompletionException(e);
				}
			});

			assertTrue(accepted.await(5, TimeUnit.SECONDS), "the venue accepts the chat before replying");
			assertTrue(gate.awaitEntered(5, TimeUnit.SECONDS), "the first agent cycle is still in flight");
			String sid = acceptedSession.get();
			ChatSession.Delivery delivery = session.enqueue("follow-up", sid);
			assertEquals(sid, delivery.sessionId(), "the follow-up is routed to the active session");

			ACell record = SessionHistory.readAgentValue(venue, agentId);
			ACell pending = RT.getIn(record, "sessions", Blob.fromHex(sid), "pending");
			assertTrue(pending instanceof AVector<?> vector && vector.count() >= 2,
				"agent:message appends beyond the envelope already presented to the blocked cycle");

			gate.release();
			assertEquals(sid, first.get(5, TimeUnit.SECONDS).sessionId());
		}
	}

	@Test
	void cancelStopsWaitingWithoutInterruptingTheTurn() throws Exception {
		String agentId = "bs-cancel";
		AppConfig.Chat chat = new AppConfig.Chat(agentId, "v/test/ops/taskcomplete",
			AppConfig.ECHO_LLM_OPERATION, "");
		ChatSession session = new ChatSession(venue, chat);
		session.ensureAgent();
		assertFalse(session.cancel(), "nothing in flight yet");

		String gateName = "brightside-cancel";
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			venue.invoke("v/ops/agent/update", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of("testGate", gateName)))
				.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);

			CountDownLatch accepted = new CountDownLatch(1);
			CompletableFuture<ChatSession.Reply> first = CompletableFuture.supplyAsync(() -> {
				try {
					return session.send("first", sid -> accepted.countDown());
				} catch (Exception e) {
					throw new java.util.concurrent.CompletionException(e);
				}
			});
			assertTrue(accepted.await(5, TimeUnit.SECONDS), "the venue accepts the chat");
			assertTrue(gate.awaitEntered(5, TimeUnit.SECONDS), "the turn is in flight");

			// The wait has no deadline of its own; the user ends it.
			assertTrue(session.cancel(), "the chat job in flight is cancelled");
			ExecutionException stopped = assertThrows(ExecutionException.class,
				() -> first.get(5, TimeUnit.SECONDS));
			assertTrue(stopped.getCause() instanceof CancellationException, String.valueOf(stopped.getCause()));
			String sid = session.sessionId();
			assertNotNull(sid, "the conversation is kept");
			assertFalse(session.cancel(), "nothing left in flight");

			// Cancelling the job does not interrupt the agent: its turn finishes
			// and the reply still lands in the session for the watcher to show.
			gate.release();
			assertTrue(awaitReplyInSession(agentId, sid, 10_000), "the turn's reply reaches the session");
			assertEquals(sid, session.send("second").sessionId(), "the same conversation continues");
		}
	}

	/**
	 * Whether the session's raw conversation gains an assistant turn. Raw, not
	 * the projection: the test transition replies with a map, which the chat
	 * projection does not show as a message.
	 */
	private static boolean awaitReplyInSession(String agentId, String sid, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			for (SessionHistory.RawTurn turn : SessionHistory.rawTurns(venue, agentId, sid)) {
				if ("assistant".equals(turn.role())) return true;
			}
			Thread.sleep(100);
		}
		return false;
	}

	@Test
	void resumeContinuesAnExistingSession() throws Exception {
		ChatSession first = new ChatSession(venue, echoChat("bs-resume"));
		String sid = first.send("opening line").sessionId();
		assertNotNull(sid);

		// A fresh session object (as on restart) that resumes the saved id.
		ChatSession resumed = new ChatSession(venue, echoChat("bs-resume"));
		resumed.resume(sid);
		assertEquals(sid, resumed.send("continued").sessionId(), "same conversation continues");
	}

	@Test
	void resumeFallsBackWhenSessionIsUnknown() throws Exception {
		ChatSession s = new ChatSession(venue, echoChat("bs-resume-bad"));
		s.resume("00000000000000000000000000000000"); // never minted
		try (var logs = brightside.ExpectedLogs.warn(ChatSession.class,
			"Could not continue the previous conversation (Unknown sessionId: 00000000000000000000000000000000")) {
			ChatSession.Reply reply = s.send("hello");
			assertNotNull(reply.sessionId(), "falls back to a fresh session instead of failing");
		}
	}

	@Test
	void onlyAnUnknownSessionTriggersTheFallback() throws Exception {
		Job missing = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		missing.fail(AgentAdapter.UNKNOWN_SESSION_CODE, "This conversation has expired");
		assertTrue(ChatSession.isUnknownSession(new JobFailedException(missing)));
		assertTrue(ChatSession.isUnknownSession(new ExecutionException(new JobFailedException(missing))));
		assertFalse(ChatSession.isUnknownSession(new JobFailedException("Unknown sessionId: abc")));
		Job other = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		other.fail("other:unknown-session", "Unknown session: abc");
		assertFalse(ChatSession.isUnknownSession(new JobFailedException(other)));
		assertFalse(ChatSession.isUnknownSession(new RuntimeException("Secret ANTHROPIC_API_KEY not found")));
		assertFalse(ChatSession.isUnknownSession(new java.util.concurrent.TimeoutException("No reply")));
	}

	@Test
	void aModelFailureKeepsTheResumedSession() throws Exception {
		// A real session, then resume it on an agent whose model operation is broken.
		ChatSession good = new ChatSession(venue, echoChat("bs-keep"));
		String sid = good.send("opening line").sessionId();

		AppConfig.Chat broken = new AppConfig.Chat("bs-keep", AppConfig.DEFAULT_OPERATION,
			"v/ops/no/such/model", "Echo the user.");
		ChatSession s = new ChatSession(venue, broken);
		s.resume(sid);
		assertThrows(Exception.class, () -> s.send("hello"));
		// The failure was the model, not an unknown session: the id must stand,
		// so the next attempt continues this conversation rather than a fresh one.
		assertEquals(sid, s.sessionId(), "session id kept on a non-session failure");

		// The failed transition left the agent SUSPENDED (the venue refuses all
		// chat until agent:resume). Re-pointed at a working model, reconfigure
		// resumes it and the same session continues.
		assertTrue(s.reconfigure(echoChat("bs-keep")));
		assertEquals(sid, s.send("continued").sessionId());
	}

	@Test
	void aSuspendedAgentIsResumedOnTheNextSend() throws Exception {
		// Suspend it with a broken model, then fix the config behind the
		// session's back (as Settings does on a restart) and just send.
		AppConfig.Chat broken = new AppConfig.Chat("bs-susp", AppConfig.DEFAULT_OPERATION,
			"v/ops/no/such/model", "Echo the user.");
		assertThrows(Exception.class, () -> new ChatSession(venue, broken).send("boom"));

		ChatSession fixed = new ChatSession(venue, echoChat("bs-susp"));
		assertTrue(ChatSession.isSuspended(new RuntimeException("Agent 'x' is suspended: y; fix the cause")));
		assertNotNull(fixed.send("hello again").sessionId(), "resumed, not bricked");
	}

	@Test
	void anAgentStoppedOnPurposeStaysStopped() throws Exception {
		ChatSession s = new ChatSession(venue, echoChat("bs-stopped"));
		assertNotNull(s.send("hello").sessionId());
		job("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "bs-stopped"));

		// Neither re-applying the configuration nor sending lifts it.
		new ChatSession(venue, echoChat("bs-stopped")).ensureAgent();
		assertThrows(ChatSession.SuspendedException.class, () -> s.send("still there?"));
		ACell info = agentInfo("bs-stopped");
		assertEquals(Strings.create("SUSPENDED"), RT.getIn(info, Fields.STATUS), String.valueOf(info));

		job("v/ops/agent/resume", Maps.of(Fields.AGENT_ID, "bs-stopped"));
		assertNotNull(s.send("back").sessionId(), "whoever stopped it lets it run again");
	}

	@Test
	void theReadyHookRunsOnceOnTheFirstSend() throws Exception {
		java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
		ChatSession s = new ChatSession(venue, echoChat("bs-ready")).whenReady(runs::incrementAndGet);
		assertEquals(0, runs.get(), "binding submits nothing and runs nothing");
		s.send("one");
		s.send("two");
		assertEquals(1, runs.get());
	}

	@Test
	void ensureAgentIsIdempotentAcrossSessions() throws Exception {
		ChatSession a = new ChatSession(venue, echoChat("bs-shared"));
		a.ensureAgent();
		a.ensureAgent();
		// A second session for an agent that already exists takes the update path.
		ChatSession b = new ChatSession(venue, echoChat("bs-shared"));
		b.ensureAgent();
		assertNotNull(b.send("hi").sessionId());
	}

	@Test
	void namedAgentCanKeepItsStoredConfigurationWhenReopened() throws Exception {
		String agentId = "bs-custom-model";
		ChatSession created = new ChatSession(venue, echoChat(agentId), null, true);
		created.ensureAgent();

		AppConfig.Chat replacement = new AppConfig.Chat(agentId, AppConfig.DEFAULT_OPERATION,
			"v/ops/no/such/model", "This must not replace the stored prompt.");
		ChatSession reopened = new ChatSession(venue, replacement, null, false);
		ChatSession.Reply reply = reopened.send("still configured");
		assertTrue(reply.text().contains("still configured"), reply.text());
	}

	@Test
	void rendersStringsVerbatimAndStructuresAsJson() {
		assertEquals("", ChatSession.render(null));
		assertEquals("plain text", ChatSession.render(Strings.create("plain text")));
		String json = ChatSession.render(Maps.of("answer", 42L));
		assertTrue(json.contains("\"answer\""), json);
		assertTrue(json.contains("42"), json);
	}

	private static ACell agentInfo(String agentId) throws Exception {
		return job("v/ops/agent/info", Maps.of(Fields.AGENT_ID, agentId));
	}

	private static ACell job(String op, AMap<AString, ACell> input) throws Exception {
		return venue.invoke(op, input).get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> configuredLoads(ACell info) {
		return (AMap<AString, ACell>) RT.getIn(info, Fields.CONFIG, Fields.LOADS);
	}

	private static boolean vectorContains(ACell cell, ACell expected) {
		if (!(cell instanceof AVector<?> vector)) return false;
		for (long i = 0; i < vector.count(); i++) {
			if (expected.equals(vector.get(i))) return true;
		}
		return false;
	}
}
