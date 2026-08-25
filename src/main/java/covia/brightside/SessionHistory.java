package covia.brightside;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * Reads the assistant's conversation from the venue's <b>live agent session
 * state</b> — the single source of truth — and projects it for display.
 *
 * <p>The agent record ({@code g/<agentId>}) holds a {@code sessions} index; each
 * session's {@code frames[0].conversation} is the turn list ({@code role} /
 * {@code content} / {@code toolCalls}). This projects an ordered list of
 * {@link Item}s: user and final-assistant {@link Message}s, plus an
 * {@link Activity} group (the intermediate "let me try…" narration and the tool
 * calls/results) between a question and its answer — so a turn's tool use is
 * hidden by default but available to expand.
 *
 * <p>Change detection is a plain lattice value compare: the {@link Snapshot}
 * carries the agent value cell, and lattice values are immutable and
 * content-addressed, so {@code agentValue().equals(previous)} tells cheaply
 * whether anything changed.
 *
 * <p>Reads the {@code AgentState} schema directly (public field names) because
 * the purpose-built {@code agent:sessionRead} projection is restricted to an
 * agent's own execution context, so it isn't callable by the owner here.
 */
public final class SessionHistory {

	private static final Logger log = LoggerFactory.getLogger(SessionHistory.class);

	private static final long TIMEOUT_SECONDS = 30;
	private static final String ROLE_USER = "user";
	private static final String ROLE_ASSISTANT = "assistant";
	private static final String ROLE_TOOL = "tool";

	/** A rendered transcript item: a {@link Message} bubble or an {@link Activity} group. */
	public sealed interface Item permits Message, Activity {
	}

	/** A chat message. {@code role} is {@code "user"} or {@code "assistant"}. */
	public record Message(String role, String text) implements Item {
	}

	/** The tool-use steps of one turn, shown collapsed and expandable. */
	public record Activity(List<Step> steps) implements Item {
	}

	/**
	 * One step within an {@link Activity}: either the assistant's narration
	 * ({@code tool=false}, text in {@code detail}) or a tool call/result
	 * ({@code tool=true}, {@code title} = tool name, {@code detail} = result,
	 * {@code error} = whether it failed).
	 */
	public record Step(boolean tool, String title, String detail, boolean error) {
	}

	/**
	 * The agent value at read time, the most-recently-active session id, and its
	 * projected items. Compare {@link #agentValue} across reads ({@code .equals})
	 * to tell whether anything changed.
	 */
	public record Snapshot(ACell agentValue, String sessionId, List<Item> items) {
	}

	/**
	 * One past conversation for the switcher: its {@code sessionId}, a short
	 * {@code title} (the first user message), and {@code lastTs} (its most recent
	 * turn) for ordering and a relative-time label.
	 */
	public record Session(String sessionId, String title, long lastTs) {
	}

	private SessionHistory() {
	}

	/**
	 * Reads the agent record and projects its most recently active conversation.
	 * Returns {@code null} only when the agent has no record yet or the read
	 * fails; an existing agent with no sessions returns an empty transcript.
	 */
	public static Snapshot loadLatest(Venue client, String agentId) {
		ACell record = readAgentValue(client, agentId);
		return (record != null) ? latestFrom(record) : null;
	}

	/**
	 * Reads the agent record and projects one specific session (by hex id). Falls
	 * back to the most-recently-active session when {@code sessionId} is null or
	 * no longer present, so a stale id never leaves a blank screen.
	 */
	public static Snapshot load(Venue client, String agentId, String sessionId) {
		ACell record = readAgentValue(client, agentId);
		return (record != null) ? snapshotOf(record, sessionId) : null;
	}

	/** All of the agent's non-empty conversations, newest first (for the switcher). */
	public static List<Session> listSessions(Venue client, String agentId) {
		ACell record = readAgentValue(client, agentId);
		return (record != null) ? sessionsOf(record) : List.of();
	}

	/**
	 * The raw agent record cell ({@code g/<agentId>}), or null if the agent has
	 * no record yet or the read fails. This is the value to compare with
	 * {@code .equals} for change detection, and to project sessions from.
	 */
	public static ACell readAgentValue(Venue client, String agentId) {
		try {
			Job job = client.invoke("v/ops/covia/read", Maps.of("path", "g/" + agentId))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			ACell result = job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			ACell record = RT.getIn(result, "value");
			return (record instanceof AMap) ? record : null;
		} catch (Exception e) {
			log.warn("Could not read agent record {}: {}", agentId, e.toString());
			return null;
		}
	}

	/** Enumerates the sessions in an already-read agent record, newest first. */
	public static List<Session> sessionsOf(ACell record) {
		List<Session> out = new ArrayList<>();
		AMap<ACell, ACell> sessions = asMap(RT.getIn(record, "sessions"));
		if (sessions != null) {
			for (long i = 0; i < sessions.count(); i++) {
				MapEntry<ACell, ACell> entry = sessions.entryAt(i);
				AVector<ACell> conversation = conversationOf(entry.getValue());
				if (conversation == null || conversation.isEmpty()) continue;
				out.add(new Session(sidHex(entry.getKey()),
					sessionTitle(entry.getValue(), conversation), latestTurnTs(conversation)));
			}
		}
		out.sort((a, b) -> Long.compare(b.lastTs(), a.lastTs()));
		return out;
	}

	/** Projects one session (by hex id) from an already-read record, or the latest. */
	public static Snapshot snapshotOf(ACell record, String sessionId) {
		if (!(record instanceof AMap)) return null;
		AMap<ACell, ACell> sessions = asMap(RT.getIn(record, "sessions"));
		if (sessions != null && sessionId != null) {
			for (long i = 0; i < sessions.count(); i++) {
				MapEntry<ACell, ACell> entry = sessions.entryAt(i);
				if (!sessionId.equals(sidHex(entry.getKey()))) continue;
				AVector<ACell> conversation = conversationOf(entry.getValue());
				List<Item> items = (conversation != null) ? project(conversation) : List.of();
				return new Snapshot(record, sessionId, items);
			}
		}
		return latestFrom(record);
	}

	/** Projects the most-recently-active session from an already-read record. */
	private static Snapshot latestFrom(ACell record) {
		if (!(record instanceof AMap)) return null;
		AMap<ACell, ACell> sessions = asMap(RT.getIn(record, "sessions"));
		String bestSid = null;
		long bestTs = Long.MIN_VALUE;
		AVector<ACell> bestConversation = null;
		if (sessions != null) {
			for (long i = 0; i < sessions.count(); i++) {
				MapEntry<ACell, ACell> entry = sessions.entryAt(i);
				AVector<ACell> conversation = conversationOf(entry.getValue());
				if (conversation == null || conversation.isEmpty()) continue;
				long ts = latestTurnTs(conversation);
				if (ts >= bestTs) {
					bestTs = ts;
					bestSid = sidHex(entry.getKey());
					bestConversation = conversation;
				}
			}
		}
		List<Item> items = (bestConversation != null) ? project(bestConversation) : List.of();
		return new Snapshot(record, bestSid, items);
	}

	/** A session's display title: its set {@code meta.title}, else the first user message. */
	private static String sessionTitle(ACell session, AVector<ACell> conversation) {
		AMap<ACell, ACell> meta = asMap(RT.getIn(session, "meta"));
		if (meta != null) {
			String title = str(meta.get(Strings.create("title")));
			if (notBlank(title)) return firstLine(title);
		}
		return titleOf(conversation);
	}

	/** The conversation as plain text ("You:" / "Brightside:" turns) — for copying out. */
	public static String plainText(List<Item> items) {
		StringBuilder sb = new StringBuilder();
		for (Item it : items) {
			if (it instanceof Message m) {
				String who = ROLE_USER.equals(m.role()) ? "You" : "Brightside";
				sb.append(who).append(": ").append(m.text()).append("\n\n");
			}
		}
		return sb.toString().stripTrailing();
	}

	/** A session's title: its first non-blank user message, first line, truncated. */
	private static String titleOf(AVector<ACell> conversation) {
		for (long i = 0; i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			if (!ROLE_USER.equals(str(RT.getIn(turn, "role")))) continue;
			String content = str(RT.getIn(turn, "content"));
			if (notBlank(content)) return firstLine(content);
		}
		return "New conversation";
	}

	private static String firstLine(String s) {
		String line = s.strip();
		int nl = line.indexOf('\n');
		if (nl >= 0) line = line.substring(0, nl).strip();
		return (line.length() <= 48) ? line : line.substring(0, 47).strip() + "…";
	}

	@SuppressWarnings("unchecked")
	private static AMap<ACell, ACell> asMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<ACell, ACell>) m : null;
	}

	private static String sidHex(ACell key) {
		return (key instanceof ABlob b) ? b.toHexString() : String.valueOf(key);
	}

	/** {@code session.frames[0].conversation}, or null. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> conversationOf(ACell session) {
		if (!(RT.getIn(session, "frames") instanceof AVector<?> frames) || frames.isEmpty()) return null;
		ACell conv = RT.getIn((ACell) frames.get(0), "conversation");
		return (conv instanceof AVector<?> v) ? (AVector<ACell>) v : null;
	}

	private static long latestTurnTs(AVector<ACell> conversation) {
		long ts = 0;
		for (long i = 0; i < conversation.count(); i++) {
			ACell t = RT.getIn(conversation.get(i), "ts");
			if (t instanceof CVMLong l) ts = Math.max(ts, l.longValue());
		}
		return ts;
	}

	/**
	 * Projects the raw conversation into display items: user and final-assistant
	 * messages, with the intermediate narration + tool steps of a turn grouped
	 * into an {@link Activity} placed just before the turn's final reply.
	 */
	private static List<Item> project(AVector<ACell> conversation) {
		List<Item> items = new ArrayList<>();
		List<Step> pending = new ArrayList<>();
		for (long i = 0; i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			String role = str(RT.getIn(turn, "role"));
			if (role == null) continue;
			String content = str(RT.getIn(turn, "content"));

			switch (role) {
				case ROLE_USER -> {
					flush(items, pending);
					if (notBlank(content)) items.add(new Message(ROLE_USER, content));
				}
				case ROLE_ASSISTANT -> {
					if (hasToolCalls(turn)) {
						if (notBlank(content)) pending.add(new Step(false, "", content, false));
					} else {
						flush(items, pending);
						if (notBlank(content)) items.add(new Message(ROLE_ASSISTANT, content));
					}
				}
				case ROLE_TOOL -> {
					String name = str(RT.getIn(turn, "name"));
					String detail = renderContent(RT.getIn(turn, "content"));
					boolean error = CVMBool.TRUE.equals(RT.getIn(turn, "isError"));
					pending.add(new Step(true, (name != null) ? name : "tool", detail, error));
				}
				default -> {
					// system and any other roles are not shown
				}
			}
		}
		flush(items, pending);
		return items;
	}

	private static void flush(List<Item> items, List<Step> pending) {
		if (!pending.isEmpty()) {
			items.add(new Activity(List.copyOf(pending)));
			pending.clear();
		}
	}

	private static boolean hasToolCalls(ACell turn) {
		return RT.getIn(turn, "toolCalls") instanceof AVector<?> v && !v.isEmpty();
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	/** A tool result rendered for display: strings verbatim, structures as JSON. */
	private static String renderContent(ACell content) {
		if (content == null) return "";
		if (content instanceof AString s) return s.toString();
		return JSON.toStringPretty(content);
	}
}
