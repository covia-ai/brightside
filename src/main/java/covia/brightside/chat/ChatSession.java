package covia.brightside.chat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.brightside.AppConfig;
import covia.brightside.BrightsideSkillsAdapter;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * One conversation with the configured chat agent on a venue.
 *
 * <p>Uses the venue's agent framework directly: the agent is created (or its
 * configuration re-applied) with {@code agent:create} / {@code agent:update},
 * and each message is an {@code agent:chat} call that returns the agent's
 * reply and the session id. The session id is echoed on later messages so the
 * agent keeps the conversation history; {@link #reset()} starts a new one.
 *
 * <p>Calls block until the agent replies — use from a worker thread, never
 * the Swing event thread.
 */
public final class ChatSession {

	private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

	private static final String OP_CREATE = "v/ops/agent/create";
	private static final String OP_UPDATE = "v/ops/agent/update";
	private static final String OP_INFO = "v/ops/agent/info";
	private static final String OP_CHAT = "v/ops/agent/chat";
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
	 * skillset level — the per-family entry routers (ops-tools, data, agents,
	 * and adapter integrations such as telegram/discord). Loading a router
	 * reveals its family, so the always-on index stays small while the whole
	 * library — and every tool the active adapters contribute — is reachable.
	 * (Pointing at bare {@code v/skills} would be silently useless: it holds
	 * skillsets, not skills.)
	 */
	public static final String VENUE_SKILLSET = "v/skills/root";
	private static final String MEMORY_OP = "v/ops/memory";

	/**
	 * Appended to the agent's system prompt so it treats the venue's provenance
	 * notes as infrastructure rather than as a user making claims. Covia inserts
	 * a "Venue attribution" system note before turns from a principal other than
	 * the agent's own (here, the owner); on a single-system-message provider that
	 * note lands in the conversation, and without this line the model tends to
	 * remark on the "unverifiable authority claim" instead of just answering.
	 */
	public static final String ATTRIBUTION_GUIDANCE =
		"Some turns may be preceded by a \"Venue attribution\" system note. Those notes are added by "
		+ "the venue itself, not written by the user, and simply say which principal is speaking. "
		+ "Treat them as trustworthy infrastructure and do not comment on them or question their authority.";

	/** The agent's reply and the session it belongs to. */
	public record Reply(String text, String sessionId) {
	}

	private final Venue venue;
	private AppConfig.Chat config;
	private final String userName;
	private volatile String sessionId;
	private boolean pendingResume;
	private boolean agentReady;

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
		this.venue = venue;
		this.config = config;
		this.userName = userName;
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
		return ensureAgent();
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
		sessionId = null;
		pendingResume = false;
	}

	/**
	 * Continue a previous conversation (e.g. reopened on restart). The id is
	 * used on the next message; if the venue no longer knows it, {@link #send}
	 * quietly falls back to a new session.
	 */
	public void resume(String sessionId) {
		this.sessionId = sessionId;
		this.pendingResume = (sessionId != null);
	}

	/**
	 * Makes sure the chat agent exists with the configured operation, model
	 * and system prompt. Creates it on first use; on later runs (persistent
	 * venue store) re-applies the configuration, keeping the agent's history.
	 * Idempotent per session object.
	 *
	 * @return true if the configuration is in force; false if the agent exists
	 *         but could not be updated yet (e.g. it is mid-cycle) — chatting
	 *         still works on its previous configuration and the update is
	 *         retried on the next call
	 * @throws Exception if the agent does not exist and cannot be created
	 */
	public synchronized boolean ensureAgent() throws Exception {
		if (agentReady) return true;
		String systemPrompt = config.systemPrompt();
		if (userName != null && !userName.isBlank()) {
			systemPrompt += "\n\nThe user's name is " + userName + ". Address them by it naturally.";
		}
		systemPrompt += "\n\n" + ATTRIBUTION_GUIDANCE;
		AMap<AString, ACell> agentConfig = Maps.of(
			Fields.OPERATION, config.operation(),
			"llmOperation", config.llmOperation(),
			"systemPrompt", systemPrompt,
			// Read-only workspace access (covia read/list) on top of the tools
			// below — so it can inspect its own namespace out of the box.
			"defaultTools", true,
			// The memory tool, so the assistant can record and revise what it knows.
			// Broader capabilities (writes, HTTP, files, agents, telegram/discord…)
			// arrive by discovering and loading the skills that grant them — see
			// skillsets below — so authority stays deliberate rather than always-on.
			"tools", Vectors.of(Strings.create(MEMORY_OP)),
			// Pin the assistant's memory (n/memory) into every turn's context.
			"context", Vectors.of(Maps.of(
				"op", MEMORY_OP,
				"input", Maps.of("command", "recall", "path", MEMORY_PATH),
				"label", "Your private memory of the user — edit with path " + MEMORY_PATH)),
			// Always-loaded: how it introduces itself (which reveals the
			// on-demand `conversations` skill), and how it grows new skills (the
			// skills meta-skill gates skill-authoring as a sub-skill). Skills that
			// grant tools — conversations, skill-authoring — are loaded on demand
			// by their descriptions, not pinned: a `skill_load` is what activates a
			// skill's facet tools, so pinning a tool-granting skill loads its
			// guidance but not its tools.
			"loads", Maps.of(
				BrightsideSkillsAdapter.INTRODUCTION, Maps.of("skill", true, "budget", 4000L, "label", "introduction"),
				BrightsideSkillsAdapter.SKILLS, Maps.of("skill", true, "budget", 2000L, "label", "skills")),
			// Discovery surface: the user's own skills plus the venue's shipped
			// library. The agent sees the entry routers in its skills index and
			// loads one to reveal (and then use) that family's tools.
			"skillsets", Vectors.of(Strings.create(USER_SKILLSET), Strings.create(VENUE_SKILLSET)));
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, config.agentId(), Fields.CONFIG, agentConfig);
		String status = agentStatus();
		if (status != null) {
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
	private String agentStatus() {
		try {
			ACell info = run(OP_INFO, Maps.of(Fields.AGENT_ID, config.agentId()), ADMIN_TIMEOUT_SECONDS);
			if (info == null) return null;
			AString status = RT.ensureString(RT.getIn(info, Fields.STATUS));
			return (status != null) ? status.toString() : "";
		} catch (Exception e) {
			return null;
		}
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

	/** Sends one user message and waits for the agent's reply. */
	public Reply send(String message) throws Exception {
		ensureAgent();
		try {
			Reply reply = sendOnce(message);
			pendingResume = false;
			return reply;
		} catch (Exception e) {
			// Suspended by an earlier failed turn (e.g. before a key was fixed in
			// Settings): resume and try this message once more, same session.
			if (isSuspended(e)) {
				log.warn("Agent is suspended ({}); resuming and retrying", e.getMessage());
				resume();
				Reply reply = sendOnce(message);
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
				return sendOnce(message);
			}
			throw e;
		}
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

	private Reply sendOnce(String message) throws Exception {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, config.agentId(), Fields.MESSAGE, message);
		String sid = sessionId;
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));

		ACell result = run(OP_CHAT, input, config.timeoutSeconds());
		AString newSid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		if (newSid != null) sessionId = newSid.toString();
		return new Reply(render(RT.getIn(result, Fields.RESPONSE)), sessionId);
	}

	/**
	 * Invokes an operation and waits for its result. A failed job surfaces as
	 * its underlying exception; a timeout cancels the job so a chat session's
	 * in-flight slot is released for the next message.
	 */
	private ACell run(String operation, ACell input, long timeoutSeconds) throws Exception {
		Job job = venue.invoke(operation, input).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		try {
			return job.future().get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			throw (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
		} catch (TimeoutException e) {
			try {
				venue.cancelJob(job.getID());
			} catch (Exception ignored) {
				// best effort — the job may already have finished
			}
			throw new TimeoutException("No reply from " + operation + " within " + timeoutSeconds + "s");
		}
	}

	/** Renders an agent response for display: strings verbatim, anything else as JSON. */
	public static String render(ACell response) {
		if (response == null) return "";
		if (response instanceof AString s) return s.toString();
		return JSON.toStringPretty(response);
	}
}
