package brightside;

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

import brightside.AgentContext;
import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.SessionHistory;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
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
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant.");
		ChatSession session = new ChatSession(client, chat, "Inspector");
		String sid = session.send("what do you see?").sessionId();
		assertNotNull(sid);

		long jobsBefore = RecordedJobs.of(venue, userDID);
		AgentContext.Report report = AgentContext.load(client, "ctx-agent", sid);
		assertNotNull(report, "owner-callable");
		assertEquals(jobsBefore, RecordedJobs.of(venue, userDID), "an inspection is a read: no job record");
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
		// The venue offers compact only to an agent that names it (covia#464); a
		// long conversation must be able to summarise itself in place.
		assertTrue(report.tools().stream().anyMatch(t -> "compact".equals(t.name()) && "harness".equals(t.source())),
			"the compact control is offered from the first turn: " + report.tools());
		// Nothing is declared ahead of a load: with no skill loaded, every tool
		// the model receives is the harness's, a default, or configured.
		assertTrue(report.tools().stream().allMatch(t -> "harness".equals(t.source())
			|| "default".equals(t.source()) || "config".equals(t.source())),
			"a skill's tools wait for its load: " + report.tools());
		assertNotNull(report.rawJson());

		// The marks divide the messages into contiguous bands covering all of them.
		List<AgentContext.Band> bands = report.bands();
		assertFalse(bands.isEmpty());
		assertEquals(0, bands.get(0).from());
		assertEquals(report.messages().size(), bands.get(bands.size() - 1).to());
		for (int i = 1; i < bands.size(); i++) {
			assertEquals(bands.get(i - 1).to(), bands.get(i).from(), "bands are contiguous: " + bands);
		}

		// The raw turns for the same session line up with what the chat elides.
		List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(
			venue.agentRecord(userDID, "ctx-agent"), sid);
		assertEquals("user", turns.get(0).role());
	}

	/**
	 * A tool exchange reaches the model as an assistant message that only makes
	 * the call, then a tool message whose result is {@code structuredContent},
	 * not {@code content}. Both halves must come through the projection — call,
	 * result and the id pairing them — or the inspector shows the exchange as
	 * two blank headings. The venue's tool-calling test model calls
	 * {@code v/test/ops/echo}, whose result is a map.
	 */
	@Test
	void projectsToolCallsAndStructuredResults() throws Exception {
		String userDID = Identity.of("caller").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("tool-agent", AppConfig.DEFAULT_OPERATION,
			"v/test/ops/toolllm", "You are a test assistant.");
		ChatSession session = new ChatSession(client, chat, "Caller");
		String sid = session.send("echo this back").sessionId();
		assertNotNull(sid);

		AgentContext.Report report = AgentContext.load(client, "tool-agent", sid);
		assertNotNull(report);
		AgentContext.Message result = report.messages().stream()
			.filter(m -> "tool".equals(m.role()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("a tool result is projected: " + report.messages()));
		assertNotNull(result.id(), "the result names its call: " + result);
		assertTrue(result.result() != null && !result.result().isBlank(),
			"a structured result is rendered, not dropped for lacking content: " + result);
		AgentContext.Message call = report.messages().stream()
			.filter(m -> m.calls().stream().anyMatch(c -> result.id().equals(c.id())))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the assistant call it answers is projected: " + report.messages()));
		assertEquals("assistant", call.role());
		assertEquals(result.name(), call.calls().get(0).name(), "the result carries its call's name");

		// The cycle-detail view reads the same exchange from the stored turns.
		List<SessionHistory.RawTurn> turns = SessionHistory.rawTurnsOf(
			venue.agentRecord(userDID, "tool-agent"), sid);
		assertTrue(turns.stream().anyMatch(t -> "tool".equals(t.role())
			&& t.toolResult() != null && !t.toolResult().isBlank()), "the stored tool result: " + turns);
	}

	@Test
	void bandsFollowTheMarksAndDropEmptyOnes() {
		List<AgentContext.Band> bands = AgentContext.bands(
			Maps.of("head", 1L, "live", 2L, "conversation", 11L, "toolLoop", 11L), 13);
		assertEquals(List.of(
			new AgentContext.Band("Head", 0, 1),
			new AgentContext.Band("Live", 1, 2),
			new AgentContext.Band("Conversation", 2, 11),
			new AgentContext.Band("Tail", 11, 13)), bands, "an empty tool loop is dropped");
		assertEquals(List.of(new AgentContext.Band("Messages", 0, 4)), AgentContext.bands(null, 4),
			"without marks, one band holds everything");
		assertTrue(AgentContext.bands(null, 0).isEmpty());
		// A mark past the end, or out of order, is clamped rather than trusted.
		assertEquals(List.of(new AgentContext.Band("Head", 0, 3)),
			AgentContext.bands(Maps.of("head", 9L, "live", 1L), 3));
	}

	@Test
	void nullForAMissingAgent() {
		Venue client = venue.clientAs(Identity.of("nobody").userDID(venue.did()));
		assertNull(AgentContext.load(client, "no-such-agent", null));
	}
}
