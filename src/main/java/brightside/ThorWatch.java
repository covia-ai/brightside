package brightside;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentEvents;

/**
 * Thor's own watch: Brightside follows every user agent's tool calls live and
 * gives {@link Thor} a task when one carries something shaped like a key,
 * token or private key to any operation but the secret store's — the shape of
 * a credential leaving. One rule, deliberately: it is rare and nearly always
 * worth a look, so the watch adds almost nothing to the gods' work.
 *
 * <p>Watching is an in-process subscription, so it costs no job; only a
 * report does. The venue's own agents are not watched, and an agent reported
 * is not reported again within {@link #COOLDOWN_MILLIS}. The task carries
 * facts — which agent, which tool, which conversation — and none of the input
 * itself; Thor reads that through his bridge. See {@code docs/THOR_WATCH.md}.
 */
public final class ThorWatch implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(ThorWatch.class);

	/** How long an agent reported stays quiet before it can be reported again. */
	static final long COOLDOWN_MILLIS = 60 * 60 * 1000;
	/** How much of a tool call's input is looked at. */
	private static final int MAX_SCAN = 64 * 1024;
	private static final String OP_AGENT_REQUEST = "v/ops/agent/request";
	/**
	 * The secret store's tools, where a key belongs: a tool is named after
	 * its adapter, so {@code secret:set} is {@code secret_set}.
	 */
	private static final String SECRET_TOOLS = "secret_";
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

	/** What the watch saw: {@code tool}, called by agent {@code agentId} of {@code ownerDID}. */
	record Observation(String ownerDID, String agentId, String tool, String sessionId) {

		String address() {
			return ownerDID + "/g/" + agentId;
		}
	}

	private final EmbeddedVenue venue;
	private final Supplier<String> model;
	private final LongSupplier clock;
	private final Consumer<Job> onReport;
	/** When each agent was last reported, by address. The worker thread's alone. */
	private final Map<String, Long> reported = new HashMap<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "brightside-thor-watch");
		t.setDaemon(true);
		return t;
	});
	private volatile AutoCloseable subscription;

	/**
	 * A watch on {@code venue}, not yet started. {@code model} is the model
	 * Thor is kept configured with, the chat's; {@code onReport} hears of each
	 * report sent.
	 */
	ThorWatch(EmbeddedVenue venue, Supplier<String> model, LongSupplier clock, Consumer<Job> onReport) {
		this.venue = venue;
		this.model = model;
		this.clock = clock;
		this.onReport = onReport;
	}

	/** Starts watching {@code venue}'s agents; close to stop. */
	public static ThorWatch start(EmbeddedVenue venue, Supplier<String> model) {
		ThorWatch watch = new ThorWatch(venue, model, Utils::getCurrentTimestamp, job -> {});
		watch.start();
		return watch;
	}

	void start() {
		subscription = venue.subscribeAgents(this::onEvent);
	}

	/** On the venue's emitting thread: pick out tool calls and hand them off. */
	private void onEvent(AgentEvents.Event event) {
		if (!AgentEvents.TOOL_START.equals(event.type())) return;
		if (venue.did().equals(event.ownerDID().toString())) return;
		worker.execute(() -> {
			try {
				Observation seen = observe(event);
				if (seen != null) onReport.accept(report(seen));
			} catch (Exception e) {
				log.warn("Could not report to {}: {}", Thor.AGENT_ID, e.toString());
			}
		});
	}

	/**
	 * What {@code event} shows, or null when it is nothing to report. Counts
	 * as a report for the cooldown. On the worker thread; package-visible for
	 * tests.
	 */
	Observation observe(AgentEvents.Event event) {
		if (!AgentEvents.TOOL_START.equals(event.type())) return null;
		String owner = event.ownerDID().toString();
		if (venue.did().equals(owner)) return null; // the gods answer to the owner directly
		AString name = RT.ensureString(RT.getIn(event.data(), Fields.NAME));
		if (name == null || name.toString().startsWith(SECRET_TOOLS)) return null;
		ACell input = RT.getIn(event.data(), Fields.DETAIL, Fields.INPUT);
		if (input == null) return null;
		String text = input.toString();
		if (text.length() > MAX_SCAN) text = text.substring(0, MAX_SCAN);
		if (!CREDENTIAL.matcher(text).find()) return null;

		Observation seen = new Observation(owner, event.agentId().toString(), plain(name.toString()),
			str(RT.getIn(event.data(), Fields.SESSION_ID)));
		long now = clock.getAsLong();
		Long last = reported.get(seen.address());
		if (last != null && now - last < COOLDOWN_MILLIS) return null;
		reported.put(seen.address(), now);
		return seen;
	}

	/**
	 * Gives Thor the task, as the venue operator who owns him, once he is
	 * configured (no job when he already is). Returns the task's job.
	 */
	Job report(Observation seen) throws Exception {
		Thor.GOD.ensure(venue, model.get());
		String task = "Brightside's watch saw " + seen.address() + " call the tool " + seen.tool()
			+ " with something shaped like a key, token or private key in its input, outside the secret store."
			+ " Find out whether a credential is leaving, and act if it is.";
		// from is the venue itself: no user or agent asked. The agent reported is
		// the suspect, apart from the reporter an alert's agent field names.
		AMap<AString, ACell> input = Maps.of(
			"task", task,
			"from", venue.did(),
			"suspect", seen.address(),
			"user", seen.ownerDID(),
			"tool", seen.tool());
		if (seen.sessionId() != null) input = input.assoc(Fields.SESSION_ID, Strings.create(seen.sessionId()));
		Job job = venue.operator().invoke(OP_AGENT_REQUEST, Maps.of(
			Fields.AGENT_ID, Thor.AGENT_ID, "input", input, "timeout", 0L)).get(30, TimeUnit.SECONDS);
		log.info("Reported {} to {}: a credential-shaped input to {}", seen.address(), Thor.AGENT_ID, seen.tool());
		return job;
	}

	/** A tool name as the model gave it, kept to the characters a real one has. */
	private static String plain(String name) {
		String s = name.replaceAll("[^A-Za-z0-9_:/.-]", "");
		return s.length() > 64 ? s.substring(0, 64) : s;
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	@Override
	public void close() {
		AutoCloseable s = subscription;
		subscription = null;
		if (s != null) {
			try {
				s.close();
			} catch (Exception e) {
				// a subscription's close does not fail
			}
		}
		worker.shutdownNow();
	}
}
