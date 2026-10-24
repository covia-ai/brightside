package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.SessionHistory;
import brightside.chat.ChatSession;
import brightside.voice.Voice;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Venue;

/**
 * Reads the conversation back from a real venue's live agent session state.
 * Every test is its own user with its own agent, so they run side by side.
 */
@Execution(ExecutionMode.CONCURRENT)
class SessionHistoryTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void reopensTheConversationFromLiveState() throws Exception {
		String userDID = Identity.of("tester").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("hist-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");

		ChatSession s = new ChatSession(client, chat, "Tester");
		String sid = s.send("remember this line").sessionId();
		assertNotNull(sid);

		// A fresh reader (as on restart) sees the same session and its turns.
		long jobsBefore = RecordedJobs.of(venue, userDID);
		SessionHistory.Snapshot conv = SessionHistory.loadLatest(client, "hist-agent");
		assertNotNull(conv, "live conversation is readable");
		assertEquals(jobsBefore, RecordedJobs.of(venue, userDID), "reading the record leaves no job record");
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
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
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
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
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
	void inProcessAgentRecordMatchesAndDetectsChange() throws Exception {
		String userDID = Identity.of("inproc").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("inproc-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession s = new ChatSession(client, chat, "InProc");
		s.ensureAgent();

		// Reads the idle record straight from the in-process lattice (no covia:read job).
		ACell record = venue.agentRecord(userDID, "inproc-agent");
		assertNotNull(record, "the agent record reads in-process");
		// The watcher's cheap compare: an idle, unchanged agent is an equal value.
		assertEquals(record, venue.agentRecord(userDID, "inproc-agent"), "unchanged is an equal value");

		// A chat mutates the record. Its returned job intentionally completes before
		// the run loop's final RUNNING → SLEEPING bookkeeping, but either state is
		// already distinct from the idle pre-chat record and needs no timing assumption.
		s.send("first message");
		ACell changed = venue.agentRecord(userDID, "inproc-agent");
		assertNotEquals(record, changed, "a new turn changes the value");
		assertEquals(SessionHistory.listSessions(client, "inproc-agent").size(),
			SessionHistory.sessionsOf(changed).size(), "same projection as the op-based read");
	}

	@Test
	void rawTurnsExposeTheUnprojectedConversation() throws Exception {
		String userDID = Identity.of("raw").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("raw-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession s = new ChatSession(client, chat, "Raw");
		String sid = s.send("hello raw world").sessionId();

		List<SessionHistory.RawTurn> turns = SessionHistory.rawTurns(client, "raw-agent", sid);
		assertTrue(turns.size() >= 2, "at least the user turn and the assistant reply");
		assertTrue(turns.stream().anyMatch(t -> "user".equals(t.role())
			&& t.content() != null && t.content().contains("hello raw world")),
			"the raw turns include the user message verbatim");
	}

	@Test
	void channelDeliveredMessagesShowTheirTextAndOrigin() throws Exception {
		String userDID = Identity.of("channelled").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("channel-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		new ChatSession(client, chat, "Channelled").ensureAgent();

		// A message delivered by a channel adapter (Discord, Telegram …) is a
		// structured {text, via} map, exactly as covia-discord's BotRunner sends it.
		invoke(client, "v/ops/agent/chat", Maps.of(
			"agentId", "channel-agent",
			"message", Maps.of(
				"text", "hello from a discord channel",
				"via", Maps.of(
					"channel", "discord",
					"from", Maps.of("id", "123", "username", "quark"),
					"chat", Maps.of("id", "456", "type", "TEXT", "name", "general")))));

		SessionHistory.Snapshot conv = SessionHistory.loadLatest(client, "channel-agent");
		assertNotNull(conv);
		SessionHistory.Message inbound = conv.items().stream()
			.filter(it -> it instanceof SessionHistory.Message m && "user".equals(m.role()))
			.map(it -> (SessionHistory.Message) it)
			.findFirst().orElse(null);
		assertNotNull(inbound, "the channel-delivered user message is shown, not dropped");
		assertEquals("hello from a discord channel", inbound.text());
		assertEquals("Discord · #general · quark", inbound.origin());

		// The switcher titles the conversation by the message's text, not JSON.
		List<SessionHistory.Session> list = SessionHistory.listSessions(client, "channel-agent");
		assertTrue(list.get(0).title().contains("hello from a discord"),
			"the title is the message text: " + list.get(0).title());

		// A message typed in the app has no origin.
		ChatSession s = new ChatSession(client, chat, "Channelled");
		s.send("typed right here");
		SessionHistory.Snapshot after = SessionHistory.loadLatest(client, "channel-agent");
		assertTrue(after.items().stream().anyMatch(it -> it instanceof SessionHistory.Message m
			&& "user".equals(m.role()) && "typed right here".equals(m.text()) && m.origin() == null),
			"an app-typed message carries no origin caption");
	}

	@Test
	void aSpokenMessageTellsTheAgentItWasHeard() throws Exception {
		String userDID = Identity.of("speaker").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("spoken-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession s = new ChatSession(client, chat, "Speaker");
		Voice.Engine engine = new Voice.Engine("local", true, "parakeet-tdt-v3", "ready", 0, 0, false, null);

		ChatSession.Reply reply = s.send(Voice.spoken("remind me to call Sam at four", engine, false, Voice.Sent.PAUSE), sid -> {
		});
		// The echo model hands back what the model was given: the words, and that they were heard.
		ACell given = JSON.parse(reply.text());
		assertEquals("remind me to call Sam at four", String.valueOf(RT.getIn(given, "text")));
		assertEquals("voice", String.valueOf(RT.getIn(given, "via", "channel")));
		assertEquals(CVMBool.FALSE, RT.getIn(given, "via", "reviewed"), "sent by voice, not read first");
		assertEquals("pause", String.valueOf(RT.getIn(given, "via", "sent")), "by a pause, nobody choosing to send");
		assertEquals("parakeet-tdt-v3", String.valueOf(RT.getIn(given, "via", "transcribed", "model")));

		SessionHistory.Snapshot conv = SessionHistory.loadLatest(client, "spoken-agent");
		assertTrue(conv.items().stream().anyMatch(it -> it instanceof SessionHistory.Message m && "user".equals(m.role())
			&& "remind me to call Sam at four".equals(m.text()) && "Voice".equals(m.origin())),
			"the transcript shows the words, said by voice: " + conv.items());
	}

	@Test
	void savedSummaryPreservesTheTranscriptAndIsReusedUntilAnotherTurn() throws Exception {
		String userDID = Identity.of("summariser").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("summary-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession session = new ChatSession(client, chat, "Summariser");
		String sid = session.send("keep this original conversation").sessionId();
		List<SessionHistory.RawTurn> before = SessionHistory.rawTurns(client, "summary-agent", sid);
		ACell input = Maps.of("agentId", "summary-agent", "sessionId", sid);
		ACell summary = client.run("v/ops/agent/summarise", input)
			.get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(RT.getIn(summary, "response"));
		assertNotNull(RT.getIn(summary, "ts"));
		assertEquals(before, SessionHistory.rawTurns(client, "summary-agent", sid),
			"saving a summary neither replaces history nor appends summary-generation turns");
		assertEquals(summary, client.run("v/ops/agent/summarise", input)
			.get(5, java.util.concurrent.TimeUnit.SECONDS), "the unchanged conversation reuses its saved result");
		session.send("another fact to retain");
		List<SessionHistory.RawTurn> advanced = SessionHistory.rawTurns(client, "summary-agent", sid);
		ACell refreshed = client.run("v/ops/agent/summarise", input)
			.get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertNotEquals(RT.getIn(summary, "turns"),
			RT.getIn(refreshed, "turns"), "new turns invalidate the saved summary");
		assertEquals(advanced, SessionHistory.rawTurns(client, "summary-agent", sid));
	}

	@Test
	void compactedTurnsShowAsTheirSummary() throws Exception {
		String userDID = Identity.of("compactor").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat chat = new AppConfig.Chat("compact-agent",
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession s = new ChatSession(client, chat, "Compactor");
		String sid = s.send("the opening question").sessionId();
		s.send("a second message");

		// The owner compacts the idle session under a summary (in the app the
		// assistant writes it, through its compact control).
		compact(client, userDID, "compact-agent", sid, "We discussed the opening question.");

		SessionHistory.Snapshot snap = SessionHistory.load(client, "compact-agent", sid);
		assertEquals(1, snap.items().size(), "the archived turns are replaced by one item: " + snap.items());
		SessionHistory.Summary summary = (SessionHistory.Summary) snap.items().get(0);
		assertEquals("We discussed the opening question.", summary.text());
		assertTrue(summary.turns() >= 4, "two questions and their replies stand behind it: " + summary.turns());

		// The switcher still titles and times it by the archived turns.
		SessionHistory.Session listed = SessionHistory.listSessions(client, "compact-agent").get(0);
		assertEquals(sid, listed.sessionId());
		assertTrue(listed.title().contains("the opening question"),
			"titled by the archived first message: " + listed.title());
		assertTrue(listed.lastTs() > 0, "timed by the archived turns");

		// The raw view names the compaction rather than dropping it.
		List<SessionHistory.RawTurn> raw = SessionHistory.rawTurns(client, "compact-agent", sid);
		assertEquals(1, raw.size());
		assertEquals("compacted", raw.get(0).role());
		assertEquals("We discussed the opening question.", raw.get(0).content());

		// The conversation carries on after its summary.
		s.send("and a third");
		SessionHistory.Snapshot after = SessionHistory.load(client, "compact-agent", sid);
		assertTrue(after.items().get(0) instanceof SessionHistory.Summary, "the summary leads");
		assertTrue(hasUserMessage(after, "and a third"), "new turns follow it");
	}

	/** Compacts once the session is idle: the venue refuses compaction mid-cycle. */
	private static void compact(Venue client, String userDID, String agentId, String sid, String summary)
			throws Exception {
		Exception refused = null;
		for (int attempt = 0; attempt < 50; attempt++) {
			if (!SessionHistory.isSessionActive(venue.agentRecord(userDID, agentId), sid)) {
				try {
					invoke(client, "v/ops/agent/compact-session",
						Maps.of("agentId", agentId, "sessionId", sid, "summary", summary));
					return;
				} catch (Exception e) {
					if (!String.valueOf(e.getMessage()).contains("active")) throw e;
					refused = e;
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("the session never went idle", refused);
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
	void sessionsLastActiveTogetherListTheNewerFirst() {
		// Ids grow with creation; turns are timed to the millisecond, so two can tie.
		List<String> ids = List.of("0a01", "0a02", "0a03", "0a04", "0a05", "0a06");
		AMap<ACell, ACell> sessions = Maps.empty();
		for (String id : ids) {
			sessions = sessions.assoc(Blob.fromHex(id), Maps.of("frames", Vectors.of(Maps.of("conversation",
				Vectors.of(Maps.of("role", "user", "content", "topic " + id, "ts", 1000L))))));
		}
		ACell record = Maps.of("sessions", sessions);
		assertEquals(List.of("0a06", "0a05", "0a04", "0a03", "0a02", "0a01"),
			SessionHistory.sessionsOf(record).stream().map(SessionHistory.Session::sessionId).toList());
		assertEquals("0a06", SessionHistory.snapshotOf(record, null).sessionId(), "the latest is the newest of the tie");
	}

	@Test
	void messagesWaitingOnARunningTurnShowAfterTheConversation() {
		// As the venue holds them while a turn runs: accepted, not yet in the conversation.
		ACell session = Maps.of(
			"frames", Vectors.of(Maps.of("conversation", Vectors.of(
				Maps.of("role", "user", "content", "start the work", "ts", 1000L),
				Maps.of("role", "assistant", "content", "on it", "ts", 1001L)))),
			"pending", Vectors.of(
				Maps.of("message", "and keep it local", "caller", "did:key:z6Mk:u:mike", "sessionId", "0b01"),
				Maps.of("message", Maps.of("text", "not GPL", "via", Maps.of("channel", "voice")),
					"caller", "did:key:z6Mk:u:mike", "sessionId", "0b01")),
			"inCycle", "0x7a31");
		ACell record = Maps.of("sessions", Maps.of(Blob.fromHex("0b01"), session));
		List<SessionHistory.Item> expected = List.of(
			new SessionHistory.Message("user", "start the work"),
			new SessionHistory.Message("assistant", "on it"),
			new SessionHistory.Message("user", "and keep it local", null, true),
			new SessionHistory.Message("user", "not GPL", "Voice", true));
		assertEquals(expected, SessionHistory.snapshotOf(record, "0b01").items());
		assertEquals(expected, SessionHistory.snapshotOf(record, null).items(), "the latest session shows them too");
	}

	@Test
	void aToolASkillAddedShowsAsItselfNotTheDispatcher() {
		// A loaded skill's tools run through invoke_tool, the real tool named in its input.
		ACell inner = Maps.of("project", "brightside", "prompt", "Add speech output");
		ACell conversation = Vectors.of(
			Maps.of("role", "user", "content", "go ahead", "ts", 1000L),
			Maps.of("role", "assistant", "content", "Handing this to Claude Code.", "ts", 1001L,
				"toolCalls", Vectors.of(Maps.of("id", "c1", "name", "invoke_tool",
					"arguments", Maps.of("name", "claudecode_run", "input", inner)))),
			Maps.of("role", "tool", "id", "c1", "name", "invoke_tool", "content", "Done.", "ts", 1002L),
			Maps.of("role", "assistant", "content", "It is done.", "ts", 1003L));
		ACell record = Maps.of("sessions", Maps.of(Blob.fromHex("0c01"),
			Maps.of("frames", Vectors.of(Maps.of("conversation", conversation)))));
		SessionHistory.Activity activity = (SessionHistory.Activity) SessionHistory.snapshotOf(record, "0c01").items().get(1);
		SessionHistory.Step step = activity.steps().get(1);
		assertEquals("claudecode_run", step.title());
		assertEquals(SessionHistory.renderContent(inner), step.call());
		assertEquals("Done.", step.detail());
		// Arguments a model sent as JSON text are seen through the same way.
		assertEquals("claudecode_run", SessionHistory.ToolCall.of("invoke_tool",
			convex.core.data.Strings.create("{\"name\":\"claudecode_run\",\"input\":{}}")).name());
		assertEquals("covia_read", SessionHistory.ToolCall.of("covia_read", inner).name());
	}

	@Test
	void noConversationForAnUnusedAgent() {
		String userDID = Identity.of("nobody").userDID(venue.did());
		assertNull(SessionHistory.loadLatest(venue.clientAs(userDID), "never-created"),
			"an agent with no sessions has no conversation");
	}
}
