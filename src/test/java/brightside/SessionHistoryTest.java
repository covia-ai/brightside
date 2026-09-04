package brightside;

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
	void noConversationForAnUnusedAgent() {
		String userDID = Identity.of("nobody").userDID(venue.did());
		assertNull(SessionHistory.loadLatest(venue.clientAs(userDID), "never-created"),
			"an agent with no sessions has no conversation");
	}
}
