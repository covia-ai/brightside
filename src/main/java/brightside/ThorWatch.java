package brightside;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.ToolGuards;

/**
 * Thor's own watch: a guard on every user agent's tool calls, so {@link Thor}
 * hears of trouble without anyone reporting it. Each call is sorted into one
 * of three tiers, each with one rule:
 * <ul>
 *   <li><b>High</b> — something shaped like a key going out (a message, a
 *   post, another agent): the call is held while Thor decides, and runs only
 *   if he allows it.</li>
 *   <li><b>Medium</b> — something shaped like a key going anywhere else
 *   outside the secret store: the call runs, and Thor is told at once.</li>
 *   <li><b>Low</b> — anything going out without one: the call runs, and Thor
 *   gets a summary of the last {@link #SUMMARY_MILLIS} if there was any.</li>
 * </ul>
 *
 * <p>Watching is an in-process guard, so it costs no job; only a report does.
 * The venue's own agents pass untouched — Thor's own calls among them, so a
 * held call never waits on itself. An agent reported at medium is not
 * reported again within {@link #COOLDOWN_MILLIS}. A task carries facts —
 * which agent, which tool, which conversation — and none of the input itself;
 * Thor reads that through his bridge. See {@code docs/THOR.md}.
 */
public final class ThorWatch implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(ThorWatch.class);

	enum Tier { HIGH, MEDIUM, LOW }

	/** How long an agent reported at medium stays quiet before it can be reported again. */
	static final long COOLDOWN_MILLIS = 60 * 60 * 1000;
	/** How long low-tier calls gather before Thor gets their summary. */
	static final long SUMMARY_MILLIS = 30 * 60 * 1000;
	/** How long a held call waits for Thor's answer. */
	static final long HOLD_MILLIS = 120_000;
	/** Why a held call did not run, before Thor's own reason when he gave one. */
	static final String NOT_ALLOWED = "held for Brightside's guardian, who did not allow it";
	/** How much of a tool call's input is looked at. */
	private static final int MAX_SCAN = 64 * 1024;
	private static final String OP_AGENT_REQUEST = "v/ops/agent/request";
	/**
	 * The secret store's tools, where a key belongs: a tool is named after
	 * its adapter, so {@code secret:set} is {@code secret_set}.
	 */
	private static final String SECRET_TOOLS = "secret_";
	/** The tools that send something out of Brightside: messages, posts, other agents. */
	static final Set<String> OUTWARD = Set.of(
		"discord_send", "slack_send", "telegram_send", "whatsapp_send", "a2a_send",
		"moltbook_create_post", "moltbook_comment");
	/**
	 * What a credential looks like: provider API keys (Anthropic, OpenAI and
	 * the like, GitHub, Slack, AWS, Google) and PEM private keys. A key starts
	 * a word, so {@code task-scheduler-configuration} is not one.
	 */
	static final Pattern CREDENTIAL = Pattern.compile(String.join("|",
		"\\bsk-[A-Za-z0-9_-]{20,}",
		"gh[pousr]_[A-Za-z0-9]{30,}",
		"github_pat_[A-Za-z0-9_]{30,}",
		"xox[abprs]-[A-Za-z0-9-]{10,}",
		"AKIA[0-9A-Z]{16}",
		"AIza[0-9A-Za-z_-]{35}",
		"-----BEGIN [A-Z ]*PRIVATE KEY-----"));
	/** The answer Thor gives for a held call. */
	private static final AMap<AString, ACell> VERDICT = Maps.of(
		"type", "object",
		"properties", Maps.of(
			"allow", Maps.of("type", "boolean",
				"description", "true lets the held call run; false refuses it"),
			"reason", Maps.of("type", "string",
				"description", "why, in a sentence; a refusal's reason is shown to the agent")),
		"required", Vectors.of(Strings.create("allow"), Strings.create("reason")));

	/** What the watch saw: {@code tool}, called by agent {@code agentId} of {@code ownerDID}. */
	record Observation(Tier tier, String ownerDID, String agentId, String tool, String sessionId) {

		String address() {
			return ownerDID + "/g/" + agentId;
		}
	}

	private final EmbeddedVenue venue;
	private final Supplier<String> model;
	private final LongSupplier clock;
	private final Set<String> outward;
	private final Consumer<Job> onReport;
	/** When each agent was last reported at medium, by address. */
	private final Map<String, Long> reported = new HashMap<>();
	/** Low-tier calls since the last summary: tool counts by agent address. */
	private final Map<String, Map<String, Integer>> gathered = new LinkedHashMap<>();
	private ScheduledFuture<?> summaryDue;
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "brightside-thor-watch");
		t.setDaemon(true);
		return t;
	});
	private volatile ToolGuards.Registration guard;

	/**
	 * A watch on {@code venue}, not yet started. {@code model} is the model
	 * Thor is kept configured with, the chat's; {@code outward} the tools that
	 * send something out; {@code onReport} hears of each task sent to Thor.
	 */
	ThorWatch(EmbeddedVenue venue, Supplier<String> model, LongSupplier clock, Set<String> outward,
			Consumer<Job> onReport) {
		this.venue = venue;
		this.model = model;
		this.clock = clock;
		this.outward = outward;
		this.onReport = onReport;
	}

	/** Starts guarding {@code venue}'s agents; close to stop. */
	public static ThorWatch start(EmbeddedVenue venue, Supplier<String> model) {
		ThorWatch watch = new ThorWatch(venue, model, Utils::getCurrentTimestamp, OUTWARD, job -> {});
		watch.start();
		return watch;
	}

	void start() {
		guard = venue.engine().toolGuards().add(this::check);
	}

	/**
	 * The guard: null to let {@code call} run, else why not. A high-tier call
	 * waits here for Thor; the others are noted and pass at once. On the
	 * call's own thread; package-visible for tests.
	 */
	String check(ToolGuards.Call call) throws Exception {
		Observation seen = classify(call);
		if (seen == null) return null;
		switch (seen.tier()) {
			case HIGH:
				return hold(seen);
			case MEDIUM:
				worker.execute(() -> report(seen));
				return null;
			default:
				gather(seen);
				return null;
		}
	}

	/**
	 * The tier {@code call} falls in, or null for none. A medium call counts
	 * as reported for the cooldown. Package-visible for tests.
	 */
	Observation classify(ToolGuards.Call call) {
		AString owner = call.ownerDID();
		if (owner == null || call.agentId() == null) return null;
		if (venue.did().equals(owner.toString())) return null; // the gods answer to the owner directly
		String tool = call.tool();
		if (tool == null || tool.startsWith(SECRET_TOOLS)) return null;
		boolean out = outward.contains(tool);
		boolean key = carriesKey(call.input());
		Tier tier = key ? (out ? Tier.HIGH : Tier.MEDIUM) : (out ? Tier.LOW : null);
		if (tier == null) return null;

		Observation seen = new Observation(tier, owner.toString(), call.agentId().toString(), plain(tool),
			(call.sessionId() != null) ? call.sessionId().toHexString() : null);
		if (tier == Tier.MEDIUM) {
			long now = clock.getAsLong();
			synchronized (reported) {
				Long last = reported.get(seen.address());
				if (last != null && now - last < COOLDOWN_MILLIS) return null;
				reported.put(seen.address(), now);
			}
		}
		return seen;
	}

	private static boolean carriesKey(ACell input) {
		if (input == null) return false;
		String text = input.toString();
		if (text.length() > MAX_SCAN) text = text.substring(0, MAX_SCAN);
		return CREDENTIAL.matcher(text).find();
	}

	/** Asks Thor about a held call and waits for his answer: null when he allows it, else why not. */
	private String hold(Observation seen) throws Exception {
		AMap<AString, ACell> task = facts(seen, "Brightside is holding " + seen.address() + "'s call to "
			+ seen.tool() + ": its input carries something shaped like a key, token or private key, on its way"
			+ " out. Allow the call only if it is plainly what the owner wants.")
			.assoc(Strings.create("held"), CVMBool.TRUE);
		Job job = submit(task, Maps.of("timeout", HOLD_MILLIS, "responseSchema", VERDICT));
		ACell answer = job.future().get(HOLD_MILLIS + 10_000, TimeUnit.MILLISECONDS);
		ACell verdict = RT.getIn(answer, "output");
		if (CVMBool.TRUE.equals(RT.getIn(verdict, "allow"))) return null;
		AString reason = RT.ensureString(RT.getIn(verdict, "reason"));
		return (reason != null && !reason.isEmpty()) ? NOT_ALLOWED + ": " + reason : NOT_ALLOWED;
	}

	/** Tells Thor of a medium-tier call; on the worker thread. */
	private void report(Observation seen) {
		try {
			submit(facts(seen, "Brightside's watch saw " + seen.address() + " call " + seen.tool()
				+ " with something shaped like a key, token or private key in its input, outside the secret store."
				+ " Find out whether a credential is leaving, and act if it is."), Maps.of("timeout", 0L));
		} catch (Exception e) {
			log.warn("Could not report to {}: {}", Thor.AGENT_ID, e.toString());
		}
	}

	/** Notes a low-tier call for the next summary, due {@link #SUMMARY_MILLIS} after the first. */
	private synchronized void gather(Observation seen) {
		gathered.computeIfAbsent(seen.address(), a -> new LinkedHashMap<>()).merge(seen.tool(), 1, Integer::sum);
		if (summaryDue == null) summaryDue = worker.schedule(this::summarise, SUMMARY_MILLIS, TimeUnit.MILLISECONDS);
	}

	/**
	 * Gives Thor the summary of the low-tier calls gathered since the last,
	 * and starts afresh. Returns the task's job, or null when there was
	 * nothing to tell. Package-visible for tests.
	 */
	Job summarise() {
		Map<String, Map<String, Integer>> since;
		synchronized (this) {
			since = new LinkedHashMap<>(gathered);
			gathered.clear();
			summaryDue = null;
		}
		if (since.isEmpty()) return null;
		StringBuilder text = new StringBuilder("Brightside's watch: in the last half hour these agents sent things out —");
		AMap<AString, ACell> counts = Maps.empty();
		for (Map.Entry<String, Map<String, Integer>> agent : since.entrySet()) {
			text.append(' ').append(agent.getKey()).append(':');
			AMap<AString, ACell> tools = Maps.empty();
			for (Map.Entry<String, Integer> tool : agent.getValue().entrySet()) {
				text.append(' ').append(tool.getKey()).append(" ×").append(tool.getValue());
				tools = tools.assoc(Strings.create(tool.getKey()), convex.core.data.prim.CVMLong.create(tool.getValue()));
			}
			text.append(';');
			counts = counts.assoc(Strings.create(agent.getKey()), tools);
		}
		text.append(" Look into anything that seems out of place; most need nothing.");
		try {
			return submit(Maps.of("task", text.toString(), "from", venue.did(), "summary", counts), Maps.of("timeout", 0L));
		} catch (Exception e) {
			log.warn("Could not send {} the watch's summary: {}", Thor.AGENT_ID, e.toString());
			return null;
		}
	}

	/** A task about one observation: {@code text} and the facts behind it, none of the input. */
	private AMap<AString, ACell> facts(Observation seen, String text) {
		// from is the venue itself: no user or agent asked. The agent concerned is
		// the suspect, apart from the reporter an alert's agent field names.
		AMap<AString, ACell> task = Maps.of(
			"task", text,
			"from", venue.did(),
			"suspect", seen.address(),
			"user", seen.ownerDID(),
			"tool", seen.tool());
		if (seen.sessionId() != null) task = task.assoc(Fields.SESSION_ID, Strings.create(seen.sessionId()));
		return task;
	}

	/**
	 * Gives Thor {@code task}, as the venue operator who owns him, once he is
	 * configured (no job when he already is), with the request's
	 * {@code options}. Returns the task's job.
	 */
	private Job submit(AMap<AString, ACell> task, AMap<AString, ACell> options) throws Exception {
		Thor.GOD.ensure(venue, model.get());
		AMap<AString, ACell> request = options.assoc(Fields.AGENT_ID, Strings.create(Thor.AGENT_ID))
			.assoc(Strings.create("input"), task);
		Job job = venue.operator().invoke(OP_AGENT_REQUEST, request).get(30, TimeUnit.SECONDS);
		log.info("Watch report to {}: {}", Thor.AGENT_ID, RT.getIn(task, "suspect") != null
			? RT.getIn(task, "suspect") + " via " + RT.getIn(task, "tool") : "summary");
		onReport.accept(job);
		return job;
	}

	/** A tool name as the model gave it, kept to the characters a real one has. */
	private static String plain(String name) {
		String s = name.replaceAll("[^A-Za-z0-9_:/.-]", "");
		return s.length() > 64 ? s.substring(0, 64) : s;
	}

	@Override
	public void close() {
		ToolGuards.Registration g = guard;
		guard = null;
		if (g != null) g.close();
		worker.shutdownNow();
	}
}
