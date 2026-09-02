package brightside.chat;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brightside.AppConfig;
import brightside.BrightsideSkillsAdapter;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;

/**
 * One conversation with the configured chat agent on a venue.
 *
 * <p>Uses the venue's agent framework directly: the agent is created (or its
 * configuration re-applied) with {@code agent:create} / {@code agent:update},
 * and the leading message is an {@code agent:chat} call that returns the
 * agent's reply and the session id. While that chat is in flight, follow-up
 * messages use {@code agent:message}: they are delivered immediately to the
 * same session's venue queue rather than starting a competing chat job. The
 * session id is echoed on later messages so the agent keeps the conversation
 * history; {@link #reset()} starts a new one.
 *
 * <p>Calls block until the agent replies, however long its turn takes — use
 * from a worker thread, never the Swing event thread. {@link #cancel()} from
 * another thread ends the wait.
 */
public final class ChatSession {

	private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

	private static final String OP_CREATE = "v/ops/agent/create";
	private static final String OP_UPDATE = "v/ops/agent/update";
	private static final String OP_INFO = "v/ops/agent/info";
	private static final String OP_CHAT = "v/ops/agent/chat";
	private static final String OP_MESSAGE = "v/ops/agent/message";
	private static final String OP_RESUME = "v/ops/agent/resume";

	private static final String STATUS_SUSPENDED = "SUSPENDED";

	/** Time allowed for agent management calls (create/update/info). */
	private static final long ADMIN_TIMEOUT_SECONDS = 30;

	/** The assistant's private memory lives in its scratch namespace. */
	public static final String MEMORY_PATH = "n/memory";
	/** The user's own skills — discoverable, theirs to grow. */
	public static final String USER_SKILLSET = "w/skills";
	/**
	 * The venue's shipped skill library. {@code v/skills/root} is the usable
	 * skillset level — the per-family entry points (agents, grid, auth,
	 * connections, venue, discovery…), each revealing its family when loaded.
	 * Each is a useful first load for its family and reveals the rest, which is
	 * the shape Brightside's own skills follow too. (Pointing at bare
	 * {@code v/skills} would be silently useless: it holds skillsets, not
	 * skills.)
	 */
	public static final String VENUE_SKILLSET = "v/skills/root";
	private static final String MEMORY_OP = "v/ops/memory";
	/**
	 * Read-only render of a memory collection. A context entry runs before every
	 * inference, and Covia only admits an op declared {@code readOnly} there
	 * (covia#465 asks for a warning instead); {@link #MEMORY_OP} is the
	 * read/write tool and cannot be.
	 */
	static final String MEMORY_RECALL_OP = "v/ops/memory-recall";
	private static final String SKILL_FEEDBACK_OP = "v/ops/brightside/report-skill-feedback";
	private static final String LEGACY_IDENTITY_SKILL = BrightsideSkillsAdapter.SKILLSET + "/identity";
	/** Read-only dynamic product context; deliberately a non-skill load. */
	public static final String CONTEXT_OP = "v/ops/brightside/context";
	/** Stable identity for the configured context load. */
	public static final String CONTEXT_LOAD_KEY = "brightside-context";

	/** The agent's reply and the session it belongs to. */
	public record Reply(String text, String sessionId) {
	}

	/** A follow-up message accepted into a venue-managed session queue. */
	public record Delivery(String sessionId) {
	}

	private final Venue venue;
	private AppConfig.Chat config;
	private final String userName;
	private final boolean configureExisting;
	private volatile String sessionId;
	private volatile long sessionGeneration;
	private boolean pendingResume;
	private boolean agentReady;
	/** The chat job whose reply is being awaited, or null. */
	private volatile Job inFlight;

	public ChatSession(Venue venue, AppConfig.Chat config) {
		this(venue, config, null);
	}

	/**
	 * @param venue    in-process client bound to the acting user
	 * @param config   the chat agent's configuration
	 * @param userName the user's display name (e.g. {@code "Mike"}), told to the
	 *                 agent so it addresses them correctly, or null
	 */
	public ChatSession(Venue venue, AppConfig.Chat config, String userName) {
		this(venue, config, userName, true);
	}

	/**
	 * @param configureExisting         whether {@link #ensureAgent()} re-applies
	 *                                  this configuration when the agent exists
	 */
	public ChatSession(Venue venue, AppConfig.Chat config, String userName,
			boolean configureExisting) {
		this.venue = venue;
		this.config = config;
		this.userName = userName;
		this.configureExisting = configureExisting;
	}

	public AppConfig.Chat config() {
		return config;
	}

	/**
	 * Re-apply the agent's configuration (e.g. a new model chosen in Settings).
	 *
	 * @return true if it applied now; false if the agent is busy (a reply in
	 *         flight) and it will apply on the next message instead
	 */
	public synchronized boolean reconfigure(AppConfig.Chat config) throws Exception {
		this.config = config;
		this.agentReady = false;
		return ensureAgent(true);
	}

	/** The user's display name, or null if unspecified. */
	public String userName() {
		return userName;
	}

	/** Current session id, or null before the first reply / after a reset. */
	public String sessionId() {
		return sessionId;
	}

	/** Forget the current session; the next message starts a new conversation. */
	public void reset() {
		sessionGeneration++;
		sessionId = null;
		pendingResume = false;
	}

	/**
	 * Continue a previous conversation (e.g. reopened on restart). The id is
	 * used on the next message; if the venue no longer knows it, {@link #send}
	 * quietly falls back to a new session.
	 */
	public void resume(String sessionId) {
		sessionGeneration++;
		this.sessionId = sessionId;
		this.pendingResume = (sessionId != null);
	}

	/**
	 * Makes sure the chat agent exists with the configured operation, model
	 * and system prompt. Creates it on first use. For Brightside's standard agent
	 * it re-applies configuration on later runs; named agents can instead retain
	 * the configuration already stored in their agent record. Idempotent per
	 * session object.
	 *
	 * @return true if the configuration is in force; false if the agent exists
	 *         but could not be updated yet (e.g. it is mid-cycle) — chatting
	 *         still works on its previous configuration and the update is
	 *         retried on the next call
	 * @throws Exception if the agent does not exist and cannot be created
	 */
	public synchronized boolean ensureAgent() throws Exception {
		return ensureAgent(configureExisting);
	}

	private boolean ensureAgent(boolean updateExisting) throws Exception {
		if (agentReady) return true;
		ACell info = agentInfo();
		String status = agentStatus(info);
		AMap<AString, ACell> existingLoads = configuredLoads(info);
		boolean legacyPinnedBaseline = existingLoads != null
			&& (existingLoads.containsKey(Strings.create(LEGACY_IDENTITY_SKILL))
				|| existingLoads.containsKey(Strings.create(BrightsideSkillsAdapter.INTRODUCTION))
				|| existingLoads.containsKey(Strings.create(BrightsideSkillsAdapter.SKILLS)));
		AMap<AString, ACell> desiredLoads = desiredLoads(existingLoads);
		AMap<AString, ACell> agentConfig = Maps.of(
			Fields.OPERATION, config.operation(),
			"llmOperation", config.llmOperation(),
			"systemPrompt", config.systemPrompt(),
			// Read-only workspace access (covia read/list) on top of the tools
			// below — so it can inspect its own namespace out of the box.
			"defaultTools", true,
			// The memory tool, plus a narrow append-only channel for concrete skill
			// misses. Neither exposes general workspace mutation.
			// Broader capabilities (writes, HTTP, files, agents, telegram/discord…)
			// arrive by discovering and loading the skills that grant them — see
			// skillsets below — so authority stays deliberate rather than always-on.
			"tools", Vectors.of(Strings.create(MEMORY_OP), Strings.create(SKILL_FEEDBACK_OP)),
			// Pin the assistant's memory (n/memory) into every turn's context,
			// through the read-only recall op; the memory tool above is for edits.
			"context", Vectors.of(Maps.of(
				"op", MEMORY_RECALL_OP,
				"input", Maps.of("path", MEMORY_PATH),
				"label", "Your private memory of the user — edit with path " + MEMORY_PATH)),
			// No Brightside skill is pinned by default. The one app-owned load is a
			// read-only context operation; owner pins are preserved and shipped skills
			// are selected from their descriptions on demand.
			"loads", desiredLoads,
			// Replace the old two-entry compatibility vector. Brightside's complete
			// skillset below is an explicit source, so lookup does not depend on Covia
			// #415's unresolved operator-pin child expansion.
			"skills", Vectors.empty(),
			// Discovery surface: the user's own skills plus the venue's shipped
			// library and Brightside's everyday-work skills. The agent loads only the
			// body (and any tools) relevant to the current task.
			"skillsets", Vectors.of(
				Strings.create(USER_SKILLSET),
				Strings.create(BrightsideSkillsAdapter.SKILLSET),
				Strings.create(VENUE_SKILLSET)));
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, config.agentId(), Fields.CONFIG, agentConfig);
		if (status != null) {
			// agent:update recursively merges nested maps. Replace the loads map in
			// two supported steps when retiring the old pinned baseline; the
			// rebuilt map preserves any other operator pins and adds app context.
			if (legacyPinnedBaseline) {
				try {
					replaceConfiguredLoads(desiredLoads);
					log.info("Migrated legacy pinned skill baseline for agent '{}'", config.agentId());
				} catch (Exception e) {
					log.warn("Could not migrate pinned skill baseline for agent '{}' (will retry): {}",
						config.agentId(), e.getMessage());
					return false;
				}
			}
			// A context pin on the read/write memory tool — an earlier Brightside's,
			// or a venue template's of the time — is refused by the venue at every
			// inference now (covia#465), whichever agent carries it. Rewrite it to
			// the read-only recall op even for an agent whose configuration is
			// otherwise left as its record holds it.
			AVector<ACell> context = migratedContext(info);
			if (context != null) {
				try {
					run(OP_UPDATE, Maps.of(Fields.AGENT_ID, config.agentId(),
						Fields.CONFIG, Maps.of("context", context)), ADMIN_TIMEOUT_SECONDS);
					log.info("Migrated memory context pin for agent '{}'", config.agentId());
				} catch (Exception e) {
					log.warn("Could not migrate memory context pin for agent '{}' (will retry): {}",
						config.agentId(), e.getMessage());
					return false;
				}
			}
			if (!updateExisting) {
				agentReady = true;
				return true;
			}
			// agent:update is a recursive *merge* (vectors replace, maps merge), not
			// a replace: tools/skillsets/context above are re-applied wholesale, but
			// a `loads` pin dropped from this map would linger on existing agents.
			try {
				run(OP_UPDATE, input, ADMIN_TIMEOUT_SECONDS);
				log.info("Applied configuration to chat agent '{}'", config.agentId());
			} catch (Exception e) {
				// e.g. the agent is mid-cycle (update is rejected while RUNNING); the
				// previous configuration stands for now. Leave agentReady false so
				// the next send() tries again once the agent is idle.
				log.warn("Could not apply configuration to agent '{}' (will retry): {}",
					config.agentId(), e.getMessage());
				return false;
			}
			// A failed transition (a bad model op, a missing API key…) leaves the
			// agent SUSPENDED, and a suspended agent refuses every chat until it is
			// resumed. The configuration has just been (re)applied — which is the
			// usual fix — so resume it now rather than leave the chat bricked.
			if (STATUS_SUSPENDED.equals(status)) resume();
		} else {
			run(OP_CREATE, input, ADMIN_TIMEOUT_SECONDS);
			log.info("Created chat agent '{}' ({} via {})", config.agentId(), config.operation(), config.llmOperation());
		}
		agentReady = true;
		return true;
	}

	/** The agent's status (SLEEPING, RUNNING, SUSPENDED, TERMINATED), or null if it doesn't exist. */
	private ACell agentInfo() {
		try {
			return run(OP_INFO, Maps.of(Fields.AGENT_ID, config.agentId()), ADMIN_TIMEOUT_SECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	private static String agentStatus(ACell info) {
		if (info == null) return null;
		AString status = RT.ensureString(RT.getIn(info, Fields.STATUS));
		return (status != null) ? status.toString() : "";
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> configuredLoads(ACell info) {
		ACell loads = RT.getIn(info, Fields.CONFIG, Fields.LOADS);
		return (loads instanceof AMap<?, ?>) ? (AMap<AString, ACell>) loads : null;
	}

	/**
	 * {@code config.context} with every pin on the read/write memory tool
	 * rewritten to {@link #MEMORY_RECALL_OP} — same path and label, the
	 * {@code command} dropped — or null when there is nothing to change.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> migratedContext(ACell info) {
		ACell context = RT.getIn(info, Fields.CONFIG, "context");
		if (!(context instanceof AVector<?> entries)) return null;
		AVector<ACell> out = Vectors.empty();
		boolean changed = false;
		for (long i = 0; i < entries.count(); i++) {
			ACell entry = (ACell) entries.get(i);
			if (entry instanceof AMap<?, ?> && Strings.create(MEMORY_OP).equals(RT.getIn(entry, "op"))) {
				AMap<AString, ACell> pin = ((AMap<AString, ACell>) entry)
					.assoc(Strings.create("op"), Strings.create(MEMORY_RECALL_OP));
				if (RT.getIn(entry, "input") instanceof AMap<?, ?> input) {
					pin = pin.assoc(Strings.create("input"),
						((AMap<AString, ACell>) input).dissoc(Strings.create("command")));
				}
				entry = pin;
				changed = true;
			}
			out = out.conj(entry);
		}
		return changed ? out : null;
	}

	/** Owner pins plus Brightside context, after removing the three legacy skill defaults. */
	private AMap<AString, ACell> desiredLoads(AMap<AString, ACell> existing) {
		AMap<AString, ACell> loads = (existing != null) ? existing : Maps.empty();
		loads = loads.dissoc(Strings.create(LEGACY_IDENTITY_SKILL));
		loads = loads.dissoc(Strings.create(BrightsideSkillsAdapter.INTRODUCTION));
		loads = loads.dissoc(Strings.create(BrightsideSkillsAdapter.SKILLS));

		AMap<AString, ACell> input = Maps.of("modelOperation", config.llmOperation());
		if (userName != null && !userName.isBlank()) {
			input = input.assoc(Strings.create("userName"), Strings.create(userName));
		}
		return loads.assoc(Strings.create(CONTEXT_LOAD_KEY), Maps.of(
			"op", CONTEXT_OP,
			"input", input,
			"label", "Brightside application context",
			"budget", 4000L,
			"volatile", CVMBool.FALSE));
	}

	/** Replaces config.loads despite agent:update's normal recursive map merge. */
	private void replaceConfiguredLoads(AMap<AString, ACell> loads) throws Exception {
		AMap<AString, ACell> clear = Maps.empty();
		clear = clear.assoc(Fields.LOADS, null);
		run(OP_UPDATE, Maps.of(
			Fields.AGENT_ID, config.agentId(),
			Fields.CONFIG, clear), ADMIN_TIMEOUT_SECONDS);
		run(OP_UPDATE, Maps.of(
			Fields.AGENT_ID, config.agentId(),
			Fields.CONFIG, Maps.of(Fields.LOADS, loads)), ADMIN_TIMEOUT_SECONDS);
	}

	/** Clears a suspension (SUSPENDED → SLEEPING). Best-effort; logged, never thrown. */
	private void resume() {
		try {
			run(OP_RESUME, Maps.of(Fields.AGENT_ID, config.agentId()), ADMIN_TIMEOUT_SECONDS);
			log.info("Resumed suspended chat agent '{}'", config.agentId());
		} catch (Exception e) {
			log.warn("Could not resume agent '{}': {}", config.agentId(), e.getMessage());
		}
	}

	/** Whether {@code e} is the venue refusing work because the agent is suspended. */
	static boolean isSuspended(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String m = t.getMessage();
			if (m != null && m.contains("is suspended")) return true;
			if (t.getCause() == t) break;
		}
		return false;
	}

	/**
	 * Stops waiting for the reply in flight by cancelling its chat job on the
	 * venue: the blocked {@link #send} throws {@link CancellationException} and
	 * the session is kept. The agent's turn itself is not interrupted — Covia
	 * only drops the job as a waiter — so whatever it finishes still lands in
	 * the session, where the live watcher shows it.
	 *
	 * @return true if a chat job was in flight and has been cancelled
	 */
	public boolean cancel() {
		Job job = inFlight;
		if (job == null || job.isFinished()) return false;
		venue.cancelJob(job.getID());
		return true;
	}

	/** Sends one user message and waits for the agent's reply. */
	public Reply send(String message) throws Exception {
		return send(message, ignored -> {
		});
	}

	/**
	 * Sends one user message and waits for the agent's reply.
	 *
	 * @param accepted called as soon as the venue has accepted the chat and
	 *                 assigned its session id, before model work completes
	 */
	public Reply send(String message, Consumer<String> accepted) throws Exception {
		ensureAgent();
		try {
			Reply reply = sendOnce(message, accepted);
			pendingResume = false;
			return reply;
		} catch (Exception e) {
			// Suspended by an earlier failed turn (e.g. before a key was fixed in
			// Settings): resume and try this message once more, same session.
			if (isSuspended(e)) {
				log.warn("Agent is suspended ({}); resuming and retrying", e.getMessage());
				resume();
				Reply reply = sendOnce(message, accepted);
				pendingResume = false;
				return reply;
			}
			// A resumed session the venue no longer knows: drop it and retry once.
			// Only for that error — a model or key failure must not mint a fresh
			// (orphan) session and fail again in it.
			if (pendingResume && isUnknownSession(e)) {
				log.warn("Could not continue the previous conversation ({}); starting a new one",
					e.getMessage());
				pendingResume = false;
				sessionId = null;
				return sendOnce(message, accepted);
			}
			throw e;
		}
	}

	/**
	 * Delivers a follow-up immediately to an existing session's venue queue.
	 * This does not wait for, or manufacture, a reply; the live session watcher
	 * observes the cycle(s) the venue subsequently runs.
	 */
	public Delivery enqueue(String message, String targetSessionId) throws Exception {
		if (targetSessionId == null || targetSessionId.isBlank()) {
			throw new IllegalStateException("Cannot queue a message before the venue assigns a session");
		}
		ensureAgent();
		ACell result = run(OP_MESSAGE, Maps.of(
			Fields.AGENT_ID, config.agentId(),
			Fields.SESSION_ID, Strings.create(targetSessionId),
			Fields.MESSAGE, message), ADMIN_TIMEOUT_SECONDS);
		AString deliveredSid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		if (deliveredSid == null) {
			throw new IllegalStateException("The venue accepted a queued message without a session id");
		}
		return new Delivery(deliveredSid.toString());
	}

	/**
	 * Whether {@code e} is the venue rejecting an unknown session id. The agent
	 * framework fails the chat job with "Unknown session: …" / "Unknown
	 * sessionId: …" (see Covia's {@code AgentAdapter}); anything else is a
	 * different failure and the session id stays.
	 */
	static boolean isUnknownSession(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String m = t.getMessage();
			if (m != null && m.toLowerCase().contains("unknown session")) return true;
			if (t.getCause() == t) break;
		}
		return false;
	}

	private Reply sendOnce(String message, Consumer<String> accepted) throws Exception {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, config.agentId(), Fields.MESSAGE, message);
		long generation = sessionGeneration;
		String sid = sessionId;
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));

		Job job = venue.invoke(OP_CHAT, input).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		inFlight = job;
		AString acceptedSidCell = RT.ensureString(RT.getIn(job.getData(), Fields.SESSION_ID));
		if (acceptedSidCell != null) {
			String acceptedSid = acceptedSidCell.toString();
			if (sessionGeneration == generation) sessionId = acceptedSid;
			if (accepted != null) accepted.accept(acceptedSid);
		}

		ACell result;
		try {
			result = awaitReply(job);
		} finally {
			if (inFlight == job) inFlight = null;
		}
		AString newSid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		String returnedSid = (newSid != null) ? newSid.toString() : sid;
		// A user may enter Home while this call is in flight. The completed old
		// turn remains valid history, but must not rebind the fresh composer.
		if (newSid != null && sessionGeneration == generation) sessionId = returnedSid;
		return new Reply(render(RT.getIn(result, Fields.RESPONSE)), returnedSid);
	}

	/**
	 * Waits for the agent's reply to a chat job. Deliberately no deadline: a
	 * turn takes as long as its model and tool calls take, each of which the
	 * venue bounds itself, and cutting it off from here would cancel the job and
	 * lose the whole turn. The user ends a wait through {@link #cancel()}.
	 */
	private static ACell awaitReply(Job job) throws Exception {
		try {
			return job.future().get();
		} catch (ExecutionException e) {
			// A cancelled job carries no error message, so name what happened.
			if (Status.CANCELLED.equals(job.getStatus())) {
				throw new CancellationException("Stopped waiting for this reply");
			}
			throw unwrap(e);
		}
	}

	/**
	 * Invokes an agent management operation and waits for its result. A failed
	 * job surfaces as its underlying exception; a timeout cancels the job.
	 */
	private ACell run(String operation, ACell input, long timeoutSeconds) throws Exception {
		Job job = venue.invoke(operation, input).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return await(job, operation, timeoutSeconds);
	}

	private ACell await(Job job, String operation, long timeoutSeconds) throws Exception {
		try {
			return job.future().get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw unwrap(e);
		} catch (TimeoutException e) {
			try {
				venue.cancelJob(job.getID());
			} catch (Exception ignored) {
				// best effort — the job may already have finished
			}
			throw new TimeoutException("No reply from " + operation + " within " + timeoutSeconds + "s");
		}
	}

	private static Exception unwrap(ExecutionException e) {
		Throwable cause = (e.getCause() != null) ? e.getCause() : e;
		return (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
	}

	/** Renders an agent response for display: strings verbatim, anything else as JSON. */
	public static String render(ACell response) {
		if (response == null) return "";
		if (response instanceof AString s) return s.toString();
		return JSON.toStringPretty(response);
	}
}
