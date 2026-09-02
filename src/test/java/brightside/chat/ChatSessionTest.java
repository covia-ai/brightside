package brightside.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import brightside.AppConfig;
import brightside.BrightsideAdapter;
import brightside.BrightsideSkillsAdapter;
import brightside.Identity;
import brightside.SessionHistory;
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
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.LocalVenue;

/** Chats through the agent framework of a temporary engine, using the echo test LLM. */
class ChatSessionTest {

	private static Engine engine;
	private static LocalVenue venue;

	@BeforeAll
	static void boot() {
		engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
		engine.registerAdapter(new BrightsideAdapter());
		engine.registerAdapter(new BrightsideSkillsAdapter());
		// Act as a named local user sub-principal of the venue, as BrightSide does.
		venue = LocalVenue.create(engine);
		venue.setUser(Identity.of("tester").userDID(engine.getDIDString().toString()));
	}

	@AfterAll
	static void shutdown() {
		if (engine != null) engine.close();
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
	void memoryIsPinnedThroughAReadOnlyOperation() throws Exception {
		String agentId = "bs-memory-pin";
		new ChatSession(venue, echoChat(agentId)).ensureAgent();

		ACell context = RT.getIn(agentInfo(agentId), Fields.CONFIG, "context");
		assertTrue(context instanceof AVector<?> v && v.count() == 1, String.valueOf(context));
		ACell pin = ((AVector<?>) context).get(0);
		assertEquals(Strings.create(ChatSession.MEMORY_RECALL_OP), RT.getIn(pin, "op"));
		assertEquals(Strings.create(ChatSession.MEMORY_PATH), RT.getIn(pin, "input", "path"));

		// A context entry runs before every inference, and the venue admits only
		// an op declared readOnly there (covia#465): the read/write memory tool
		// is refused, so it must not be what the pin names.
		assertTrue(declaresReadOnly(ChatSession.MEMORY_RECALL_OP), "the pinned op is declared readOnly");
		assertFalse(declaresReadOnly("v/ops/memory"), "the memory tool is not");
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
		ChatSession.Reply reply = s.send("hello");
		assertNotNull(reply.sessionId(), "falls back to a fresh session instead of failing");
	}

	@Test
	void onlyAnUnknownSessionTriggersTheFallback() throws Exception {
		assertTrue(ChatSession.isUnknownSession(new RuntimeException("Unknown sessionId: abc — omit sessionId")));
		assertTrue(ChatSession.isUnknownSession(new java.util.concurrent.ExecutionException(
			new IllegalStateException("Unknown session: abc"))));
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
		return venue.invoke("v/ops/agent/info", Maps.of(Fields.AGENT_ID, agentId))
			.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);
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
