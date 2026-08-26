package covia.brightside.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.brightside.AppConfig;
import covia.brightside.Identity;
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
		// Act as a named local user sub-principal of the venue, as BrightSide does.
		venue = LocalVenue.create(engine);
		venue.setUser(Identity.of("tester").userDID(engine.getDIDString().toString()));
	}

	@AfterAll
	static void shutdown() {
		if (engine != null) engine.close();
	}

	private static AppConfig.Chat echoChat(String agentId) {
		return new AppConfig.Chat(agentId, AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.", 30);
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
			"v/ops/no/such/model", "Echo the user.", 30);
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
			"v/ops/no/such/model", "Echo the user.", 30);
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
	void rendersStringsVerbatimAndStructuresAsJson() {
		assertEquals("", ChatSession.render(null));
		assertEquals("plain text", ChatSession.render(Strings.create("plain text")));
		String json = ChatSession.render(Maps.of("answer", 42L));
		assertTrue(json.contains("\"answer\""), json);
		assertTrue(json.contains("42"), json);
	}
}
