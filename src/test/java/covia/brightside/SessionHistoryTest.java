package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.brightside.chat.ChatSession;
import covia.grid.Venue;
import covia.venue.Config;

/** Reads the conversation back from a real venue's live agent session state. */
class SessionHistoryTest {

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
	void reopensTheConversationFromLiveState() throws Exception {
		String userDID = Identity.of("tester").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("hist-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.", 30);

		ChatSession s = new ChatSession(client, chat, "Tester");
		String sid = s.send("remember this line").sessionId();
		assertNotNull(sid);

		// A fresh reader (as on restart) sees the same session and its turns.
		SessionHistory.Snapshot conv = SessionHistory.loadLatest(client, "hist-agent");
		assertNotNull(conv, "live conversation is readable");
		assertNotNull(conv.agentValue(), "carries the agent value for change comparison");
		assertEquals(sid, conv.sessionId(), "reopens the same session");
		assertTrue(conv.items().stream()
			.anyMatch(it -> it instanceof SessionHistory.Message m
				&& m.role().equals("user") && m.text().contains("remember this line")),
			"transcript contains the user message");

		// The lattice value compare: a new turn changes the agent value.
		s.send("another line");
		SessionHistory.Snapshot after = SessionHistory.loadLatest(client, "hist-agent");
		assertNotNull(after);
		assertNotEquals(conv.agentValue(), after.agentValue(), "the agent value changed");
	}

	@Test
	void listsAndReopensPastSessions() throws Exception {
		String userDID = Identity.of("switcher").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("switch-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.", 30);
		ChatSession s = new ChatSession(client, chat, "Switcher");

		String sid1 = s.send("first conversation topic").sessionId();
		s.reset(); // a new chat: the next message mints a fresh session
		String sid2 = s.send("second conversation topic").sessionId();
		assertNotNull(sid1);
		assertNotNull(sid2);
		assertNotEquals(sid1, sid2, "reset starts a new session");

		// The switcher lists both, newest first, titled by the first user message.
		List<SessionHistory.Session> list = SessionHistory.listSessions(client, "switch-agent");
		assertEquals(2, list.size(), "both conversations are listed");
		assertEquals(sid2, list.get(0).sessionId(), "newest conversation first");
		assertEquals(sid1, list.get(1).sessionId());
		assertTrue(list.get(1).title().contains("first conversation topic"),
			"the title is the first user message");

		// Reopening a specific past session gives that session's transcript.
		SessionHistory.Snapshot first = SessionHistory.load(client, "switch-agent", sid1);
		assertEquals(sid1, first.sessionId());
		assertTrue(hasUserMessage(first, "first conversation topic"));
		assertFalse(hasUserMessage(first, "second conversation topic"),
			"an old session shows only its own turns");

		// A null or unknown id falls back to the latest — never a blank screen.
		assertEquals(sid2, SessionHistory.load(client, "switch-agent", null).sessionId());
		assertEquals(sid2, SessionHistory.load(client, "switch-agent", "deadbeef").sessionId());
	}

	private static boolean hasUserMessage(SessionHistory.Snapshot snap, String needle) {
		return snap.items().stream().anyMatch(it -> it instanceof SessionHistory.Message m
			&& m.role().equals("user") && m.text().contains(needle));
	}

	@Test
	void renamesAndDeletesSessions() throws Exception {
		String userDID = Identity.of("editor").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("edit-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.", 30);
		ChatSession s = new ChatSession(client, chat, "Editor");
		String keep = s.send("keep this one").sessionId();
		s.reset();
		String drop = s.send("delete this one").sessionId();

		// Rename: a set title is shown in place of the first-message title.
		invoke(client, "v/ops/agent/rename-session",
			Maps.of("agentId", "edit-agent", "sessionId", keep, "title", "My renamed chat"));
		assertEquals("My renamed chat", titleOf(client, "edit-agent", keep));

		// Clearing the title (omit it) reverts to the auto-derived label.
		invoke(client, "v/ops/agent/rename-session",
			Maps.of("agentId", "edit-agent", "sessionId", keep));
		assertTrue(titleOf(client, "edit-agent", keep).contains("keep this one"));

		// Delete: the session is gone from the list; the other remains.
		invoke(client, "v/ops/agent/delete-session",
			Maps.of("agentId", "edit-agent", "sessionId", drop));
		List<SessionHistory.Session> list = SessionHistory.listSessions(client, "edit-agent");
		assertTrue(list.stream().anyMatch(x -> x.sessionId().equals(keep)), "kept session remains");
		assertFalse(list.stream().anyMatch(x -> x.sessionId().equals(drop)), "deleted session is gone");
	}

	@Test
	void rawTurnsExposeTheUnprojectedConversation() throws Exception {
		String userDID = Identity.of("raw").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("raw-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.", 30);
		ChatSession s = new ChatSession(client, chat, "Raw");
		String sid = s.send("hello raw world").sessionId();

		List<SessionHistory.RawTurn> turns = SessionHistory.rawTurns(client, "raw-agent", sid);
		assertTrue(turns.size() >= 2, "at least the user turn and the assistant reply");
		assertTrue(turns.stream().anyMatch(t -> "user".equals(t.role())
			&& t.content() != null && t.content().contains("hello raw world")),
			"the raw turns include the user message verbatim");
	}

	private static String titleOf(Venue client, String agentId, String sessionId) {
		return SessionHistory.listSessions(client, agentId).stream()
			.filter(x -> x.sessionId().equals(sessionId))
			.map(SessionHistory.Session::title).findFirst().orElse(null);
	}

	private static void invoke(Venue client, String op, ACell input) throws Exception {
		var job = client.invoke(op, input).get(30, java.util.concurrent.TimeUnit.SECONDS);
		job.future().get(30, java.util.concurrent.TimeUnit.SECONDS);
	}

	@Test
	void noConversationForAnUnusedAgent() {
		String userDID = Identity.of("nobody").userDID(venue.did());
		assertNull(SessionHistory.loadLatest(venue.clientAs(userDID), "never-created"),
			"an agent with no sessions has no conversation");
	}
}
