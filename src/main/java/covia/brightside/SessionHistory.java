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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * Reads the assistant's conversation from the venue's <b>live agent session
 * state</b> — the single source of truth — rather than a local copy. On start
 * Brightside reopens the most recently active session and renders it.
 *
 * <p>The agent record ({@code g/<agentId>}) holds a {@code sessions} index; each
 * session's {@code frames[0].conversation} is the turn list ({@code role} /
 * {@code content}). This projects the user and completed-assistant turns, the
 * same view the model keeps (tool scratch and empty tool-call turns skipped).
 *
 * <p>This reads the agent state schema directly (public {@code AgentState} field
 * names). The purpose-built {@code agent:sessionRead} projection is restricted to
 * an agent's own execution context, so it isn't callable by the owner here.
 */
public final class SessionHistory {

	private static final Logger log = LoggerFactory.getLogger(SessionHistory.class);

	private static final long TIMEOUT_SECONDS = 30;
	private static final String ROLE_USER = "user";
	private static final String ROLE_ASSISTANT = "assistant";

	/** A displayed turn. */
	public record Turn(String role, String text) {
	}

	/** A session and its projected transcript. */
	public record Conversation(String sessionId, List<Turn> turns) {
	}

	private SessionHistory() {
	}

	/**
	 * The most recently active conversation for {@code agentId}, read as the
	 * caller (the owner). Returns {@code null} if the agent has no readable
	 * conversation yet, or the read fails.
	 */
	public static Conversation loadLatest(Venue client, String agentId) {
		try {
			Job job = client.invoke("v/ops/covia/read", Maps.of("path", "g/" + agentId))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			ACell result = job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			ACell record = RT.getIn(result, "value");
			if (record == null) record = result;
			AMap<ACell, ACell> sessions = asMap(RT.getIn(record, "sessions"));
			if (sessions == null || sessions.isEmpty()) return null;

			String bestSid = null;
			long bestTs = Long.MIN_VALUE;
			AVector<ACell> bestConversation = null;
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
			if (bestSid == null) return null;
			return new Conversation(bestSid, project(bestConversation));
		} catch (Exception e) {
			log.warn("Could not read live conversation for {}: {}", agentId, e.toString());
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static AMap<ACell, ACell> asMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<ACell, ACell>) m : null;
	}

	private static String sidHex(ACell key) {
		return (key instanceof ABlob b) ? b.toHexString() : key.toString();
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

	/** Keep user turns and completed assistant turns; skip tool scratch / empty. */
	private static List<Turn> project(AVector<ACell> conversation) {
		List<Turn> turns = new ArrayList<>();
		for (long i = 0; i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			AString role = RT.ensureString(RT.getIn(turn, "role"));
			AString content = RT.ensureString(RT.getIn(turn, "content"));
			if (role == null || content == null || content.toString().isBlank()) continue;
			String r = role.toString();
			if (ROLE_USER.equals(r) || ROLE_ASSISTANT.equals(r)) {
				turns.add(new Turn(r, content.toString()));
			}
		}
		return turns;
	}
}
