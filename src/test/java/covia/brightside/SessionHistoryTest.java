package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
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
	void noConversationForAnUnusedAgent() {
		String userDID = Identity.of("nobody").userDID(venue.did());
		assertNull(SessionHistory.loadLatest(venue.clientAs(userDID), "never-created"),
			"an agent with no sessions has no conversation");
	}
}
