package covia.brightside.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
