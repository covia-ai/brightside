package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import brightside.chat.ChatSession;
import brightside.vault.Vault;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.adapter.agent.GoalTreeContext;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.VenueState;

/**
 * A Brightside install written before Covia versioned anything, upgraded by
 * this build, and read at every stage of the upgrade: what the owner sees
 * must not change, only what is added.
 *
 * <p>The install ({@code src/test/resources/brightside/v0-install}) was made
 * by {@link V0Install} on the build its README names: venue state with no
 * {@code :version}, and every conversation in the legacy {@code conversation}
 * form, compacted ones nesting their archived turns. The stages:</p>
 * <ol>
 * <li>as the old build left it, on disk;</li>
 * <li>opened by this build, which migrates the venue to version 1 at startup
 * but leaves each conversation as it is until its session next runs;</li>
 * <li>each conversation converted in memory to the append-only history form,
 * nothing written;</li>
 * <li>sessions run again, which converts them for real;</li>
 * <li>a converted session compacted twice more;</li>
 * <li>closed and opened again.</li>
 * </ol>
 */
class CoviaMigrationTest {

	private static final String FIXTURE = "/brightside/v0-install/";
	private static final AString CONVERSATION = Strings.create("conversation");
	private static final AString HISTORY = Strings.create("history");
	private static final AString HISTORY_VERSION = Strings.create("historyVersion");
	private static final AString CONTEXT_START = Strings.create("contextStart");
	private static final AString HISTORY_RANGE = Strings.create("historyRange");
	private static final AString RENDERED_CONTEXT = Strings.create("renderedContext");

	@Test
	void aV0InstallReadsTheSameAtEveryStageOfItsUpgrade() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path home = directory.path();
			unzip(FIXTURE + "venue.etch.zip", home);
			List<String> written = expected();

			// 0. As the old build left it: no venue version on disk.
			assertTrue(storedVenue(home).containsKey(Covia.DID), "the install holds its venue");
			assertFalse(storedVenue(home).containsKey(Covia.VERSION), "the v0 install has no venue version");

			Map<String, List<SessionHistory.Item>> transcripts;
			Map<String, List<SessionHistory.RawTurn>> raw;
			String plain, compacted;
			try (V0Install.Launched launched = V0Install.launch(home)) {
				EmbeddedVenue venue = launched.venue();
				String userDID = Identity.of(V0Install.OWNER).userDID(venue.did());

				// 1. This build opens it: the venue is version 1, the conversations still legacy.
				assertEquals(VenueState.CURRENT_VERSION, venue.engine().getVenueState().version());
				ACell notes = venue.agentRecord(userDID, V0Install.NOTES_AGENT);
				ACell tools = venue.agentRecord(userDID, V0Install.TOOLS_AGENT);
				for (ACell record : List.of(notes, tools)) {
					for (AMap<AString, ACell> frame : rootFrames(record).values()) {
						assertTrue(frame.containsKey(CONVERSATION), "a session not yet run keeps its legacy conversation");
						assertFalse(frame.containsKey(HISTORY));
					}
				}
				assertEquals(written, V0Install.describe(venue), "the owner sees what the old build showed");
				assertEquals(notes, venue.agentRecord(userDID, V0Install.NOTES_AGENT), "reading changes nothing");
				assertEquals(tools, venue.agentRecord(userDID, V0Install.TOOLS_AGENT), "reading changes nothing");

				// 2. Each conversation converted in memory: the same to the owner, its cached context kept.
				for (ACell record : List.of(notes, tools)) {
					String agentId = (record == notes) ? V0Install.NOTES_AGENT : V0Install.TOOLS_AGENT;
					ACell migrated = migrated(record);
					for (AMap<AString, ACell> frame : rootFrames(migrated).values()) {
						assertEquals(CVMLong.ONE, frame.get(HISTORY_VERSION));
						assertFalse(frame.containsKey(CONVERSATION));
					}
					assertEquals(V0Install.describe(agentId, record), V0Install.describe(agentId, migrated),
						"converted, " + agentId + " reads the same");
					Map<String, AMap<AString, ACell>> before = rootFrames(record), after = rootFrames(migrated);
					for (String sid : before.keySet()) {
						assertNotNull(before.get(sid).get(RENDERED_CONTEXT), "the old build cached its model context");
						assertEquals(before.get(sid).get(RENDERED_CONTEXT), after.get(sid).get(RENDERED_CONTEXT),
							"the model's cached context is untouched");
					}
				}

				// 3. Sessions run again, and are converted for real; one left alone stays legacy.
				List<SessionHistory.Session> sessions = SessionHistory.sessionsOf(notes);
				transcripts = transcripts(notes);
				raw = rawTurns(notes);
				plain = sessionTitled(sessions, "the first question");
				compacted = sessionTitled(sessions, "an opening topic");
				Map<String, String> titles = new LinkedHashMap<>();
				for (SessionHistory.Session s : sessions) titles.put(s.sessionId(), s.title());
				for (String sid : titles.keySet()) {
					if (sid.equals(plain)) continue;
					resumedOn(venue, userDID, sid).send("after the upgrade");
				}
				String toolSession = SessionHistory.sessionsOf(tools).get(0).sessionId();
				List<SessionHistory.Item> toolTranscript = transcripts(tools).get(toolSession);
				ChatSession toolChat = new ChatSession(venue.clientAs(userDID), new AppConfig.Chat(V0Install.TOOLS_AGENT,
					AppConfig.DEFAULT_OPERATION, V0Install.TOOL_LLM_OPERATION, "Use a tool, then answer."), "Owner");
				toolChat.resume(toolSession);
				toolChat.send("after the upgrade");

				ACell upgraded = venue.agentRecord(userDID, V0Install.NOTES_AGENT);
				Map<String, AMap<AString, ACell>> frames = rootFrames(upgraded);
				for (String sid : titles.keySet()) {
					AMap<AString, ACell> frame = frames.get(sid);
					if (sid.equals(plain)) {
						assertTrue(frame.containsKey(CONVERSATION), "a session not run since stays as it was");
					} else {
						assertEquals(CVMLong.ONE, frame.get(HISTORY_VERSION), "a session that ran is converted");
						assertFalse(frame.containsKey(CONVERSATION));
						assertNotNull(frame.get(CONTEXT_START));
					}
				}
				assertTrue(hasRange(frames.get(compacted)), "its summaries refer back into the history");
				Map<String, List<SessionHistory.Item>> afterRun = transcripts(upgraded);
				Map<String, List<SessionHistory.RawTurn>> rawAfterRun = rawTurns(upgraded);
				for (SessionHistory.Session s : SessionHistory.sessionsOf(upgraded)) {
					assertEquals(titles.get(s.sessionId()), s.title(), "every session keeps its title");
				}
				for (String sid : titles.keySet()) {
					assertStartsWith(transcripts.get(sid), afterRun.get(sid), "the transcript so far is unchanged");
					assertStartsWith(raw.get(sid), rawAfterRun.get(sid), "the raw turns so far are unchanged");
				}
				assertEquals(transcripts.get(plain), afterRun.get(plain));
				for (String sid : titles.keySet()) {
					if (!sid.equals(plain)) assertTrue(hasUserMessage(afterRun.get(sid), "after the upgrade"), "the new turn follows");
				}
				List<SessionHistory.Item> toolAfter = transcripts(venue.agentRecord(userDID, V0Install.TOOLS_AGENT)).get(toolSession);
				assertStartsWith(toolTranscript, toolAfter, "the tool session so far is unchanged");
				assertTrue(hasUserMessage(toolAfter, "after the upgrade"), "the new turn follows");

				// 4. The converted session compacted twice more: one summary for the owner, nothing lost underneath.
				AVector<ACell> history = GoalTreeContext.history(frames.get(compacted));
				SessionHistory.Summary first = (SessionHistory.Summary) transcripts.get(compacted).get(0);
				V0Install.compact(venue, venue.clientAs(userDID), userDID, compacted, "Third summary.");
				resumedOn(venue, userDID, compacted).send("between the last two");
				V0Install.compact(venue, venue.clientAs(userDID), userDID, compacted, "Fourth summary.");
				ACell recompacted = venue.agentRecord(userDID, V0Install.NOTES_AGENT);
				AMap<AString, ACell> frame = rootFrames(recompacted).get(compacted);
				AVector<ACell> retained = GoalTreeContext.history(frame);
				assertEquals(history, retained.slice(0, history.count()), "compaction only appends to the history");
				List<SessionHistory.Item> summarised = transcripts(recompacted).get(compacted);
				assertEquals(1, summarised.size(), "the owner sees one summary: " + summarised);
				SessionHistory.Summary latest = assertInstanceOf(SessionHistory.Summary.class, summarised.get(0));
				assertEquals("Fourth summary.", latest.text());
				assertTrue(latest.turns() > first.turns(), "it stands for every turn before it: " + latest.turns());
				assertEquals("compacted", rawTurns(recompacted).get(compacted).get(0).role());
			}

			// 5. Closed and opened again: still version 1 on disk, and read the same.
			List<String> beforeClose;
			try (V0Install.Launched launched = V0Install.launch(home)) {
				beforeClose = V0Install.describe(launched.venue());
				String userDID = Identity.of(V0Install.OWNER).userDID(launched.venue().did());
				AMap<AString, ACell> frame = rootFrames(launched.venue().agentRecord(userDID, V0Install.NOTES_AGENT)).get(plain);
				assertTrue(frame.containsKey(CONVERSATION), "the session left alone is still legacy after a restart");
				assertEquals(transcripts.get(plain), transcripts(launched.venue().agentRecord(userDID, V0Install.NOTES_AGENT)).get(plain));
			}
			assertEquals(CVMLong.create(VenueState.CURRENT_VERSION), storedVenue(home).get(Covia.VERSION));
			try (V0Install.Launched launched = V0Install.launch(home)) {
				assertEquals(beforeClose, V0Install.describe(launched.venue()), "a restart reads the same");
			}
		}
	}

	private static List<String> expected() throws Exception {
		try (InputStream in = CoviaMigrationTest.class.getResourceAsStream(FIXTURE + V0Install.EXPECTED)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
	}

	private static void unzip(String resource, Path into) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(CoviaMigrationTest.class.getResourceAsStream(resource))) {
			for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				Files.copy(zip, into.resolve(entry.getName()));
			}
		}
	}

	/** The venue's own state as stored, read straight from the store without launching anything. */
	private static AMap<Keyword, ACell> storedVenue(Path home) throws Exception {
		AMap<AString, ACell> config = Vault.open(home, V0Install.PASSPHRASE.toCharArray())
			.secure(AppConfig.defaultVenue(home), V0Install.SEED);
		try (EtchStore store = EtchStore.create(home.resolve("venue.etch").toFile(), new Config(config).getEtchConfig())) {
			AMap<ACell, ACell> venues = RT.ensureMap(RT.getIn(store.getRootData(), Covia.GRID, Covia.VENUES));
			assertEquals(1, venues.count(), "one venue in the install");
			AMap<Keyword, ACell> value = RT.ensureMap(RT.getIn(venues.entryAt(0).getValue(), Keyword.intern("value")));
			assertNotNull(value);
			return value;
		}
	}

	/** Each session's root frame, by session id. */
	@SuppressWarnings("unchecked")
	private static Map<String, AMap<AString, ACell>> rootFrames(ACell record) {
		Map<String, AMap<AString, ACell>> out = new LinkedHashMap<>();
		AMap<ACell, ACell> sessions = RT.ensureMap(RT.getIn(record, "sessions"));
		for (long i = 0; i < sessions.count(); i++) {
			MapEntry<ACell, ACell> e = sessions.entryAt(i);
			AVector<ACell> frames = RT.ensureVector(RT.getIn(e.getValue(), "frames"));
			out.put(sidHex(e.getKey()), (AMap<AString, ACell>) frames.get(0));
		}
		return out;
	}

	/** The record with every session's frames converted, as Covia converts them, and nothing written. */
	@SuppressWarnings("unchecked")
	private static ACell migrated(ACell record) {
		AMap<ACell, ACell> sessions = RT.ensureMap(RT.getIn(record, "sessions"));
		AMap<ACell, ACell> converted = sessions;
		for (long i = 0; i < sessions.count(); i++) {
			MapEntry<ACell, ACell> e = sessions.entryAt(i);
			AMap<AString, ACell> session = (AMap<AString, ACell>) e.getValue();
			AVector<ACell> frames = RT.ensureVector(session.get(Strings.create("frames")));
			converted = converted.assoc(e.getKey(), session.assoc(Strings.create("frames"), GoalTreeContext.migrateFrames(frames)));
		}
		return ((AMap<ACell, ACell>) record).assoc(Strings.create("sessions"), converted);
	}

	private static boolean hasRange(AMap<AString, ACell> frame) {
		AVector<ACell> history = GoalTreeContext.history(frame);
		for (long i = 0; i < history.count(); i++) {
			if (RT.getIn(history.get(i), HISTORY_RANGE) != null) return true;
		}
		return false;
	}

	private static Map<String, List<SessionHistory.Item>> transcripts(ACell record) {
		Map<String, List<SessionHistory.Item>> out = new LinkedHashMap<>();
		for (SessionHistory.Session s : SessionHistory.sessionsOf(record)) {
			out.put(s.sessionId(), SessionHistory.snapshotOf(record, s.sessionId()).items());
		}
		return out;
	}

	private static Map<String, List<SessionHistory.RawTurn>> rawTurns(ACell record) {
		Map<String, List<SessionHistory.RawTurn>> out = new LinkedHashMap<>();
		for (SessionHistory.Session s : SessionHistory.sessionsOf(record)) {
			out.put(s.sessionId(), SessionHistory.rawTurnsOf(record, s.sessionId()));
		}
		return out;
	}

	private static String sessionTitled(List<SessionHistory.Session> sessions, String title) {
		return sessions.stream().filter(s -> s.title().contains(title)).findFirst().orElseThrow().sessionId();
	}

	private static ChatSession resumedOn(EmbeddedVenue venue, String userDID, String sid) {
		ChatSession chat = new ChatSession(venue.clientAs(userDID), new AppConfig.Chat(V0Install.NOTES_AGENT,
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user."), "Owner");
		chat.resume(sid);
		return chat;
	}

	private static boolean hasUserMessage(List<SessionHistory.Item> items, String text) {
		return items.stream().anyMatch(it -> it instanceof SessionHistory.Message m
			&& "user".equals(m.role()) && text.equals(m.text()));
	}

	private static <T> void assertStartsWith(List<T> prefix, List<T> list, String message) {
		assertNotNull(list, message);
		assertTrue(list.size() >= prefix.size(), message + ": " + list);
		assertEquals(prefix, list.subList(0, prefix.size()), message);
	}

	private static String sidHex(ACell key) {
		return (key instanceof convex.core.data.ABlob b) ? b.toHexString() : String.valueOf(key);
	}
}
