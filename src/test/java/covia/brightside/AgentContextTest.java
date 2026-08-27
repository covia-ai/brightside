package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.brightside.chat.ChatSession;
import covia.grid.Venue;
import covia.venue.Config;

/**
 * The inspector's data: {@code v/ops/agent/context} assembled for a real
 * session on a real venue, as the agent's owner.
 */
class AgentContextTest {

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
	void assemblesTheModelInputForASession() throws Exception {
		String userDID = Identity.of("inspector").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("ctx-agent", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant.", 30);
		ChatSession session = new ChatSession(client, chat, "Inspector");
		String sid = session.send("what do you see?").sessionId();
		assertNotNull(sid);

		AgentContext.Report report = AgentContext.load(client, "ctx-agent", sid);
		assertNotNull(report, "owner-callable");
		assertNotNull(report.model());
		assertFalse(report.messages().isEmpty(), "the assembled messages");
		assertTrue(report.messages().stream().anyMatch(message -> "system".equals(message.role())),
			"the configured system prompt is represented");
		assertTrue(report.messages().stream().anyMatch(message -> "user".equals(message.role())),
			"the conversation is represented");
		assertTrue(report.loads().stream()
			.anyMatch(load -> ChatSession.CONTEXT_LOAD_KEY.equals(load.ref())),
			"the dynamic Brightside context load is represented");
		assertTrue(report.tools().stream().anyMatch(t -> t.name() != null && t.name().contains("memory")),
			"the memory tool is offered: " + report.tools());
		assertNotNull(report.rawJson());

		// The raw turns for the same session line up with what the chat elides.
		List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(
			venue.agentRecord(userDID, "ctx-agent"), sid);
		assertEquals("user", turns.get(0).role());
	}

	@Test
	void nullForAMissingAgent() {
		Venue client = venue.clientAs(Identity.of("nobody").userDID(venue.did()));
		assertNull(AgentContext.load(client, "no-such-agent", null));
	}
}
