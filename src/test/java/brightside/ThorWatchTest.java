package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.ToolGuards;

/**
 * Thor's watch on a real venue, tier by tier: a key on its way out is held
 * until Thor allows it, a key anywhere else outside the secret store reaches
 * him at once, and calls that only send something out reach him as a summary.
 * Nothing else does — a key where it belongs, the gods (so Thor's own calls
 * never wait on him), routine tokens and hashes, or the same agent again
 * within the cooldown.
 */
class ThorWatchTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given, without ever allowing a held call. */
	private static final String TASK_LLM = "v/test/ops/taskllm";
	/** Calls {@link #TOOL} with the user's message as its input, then answers. */
	private static final String TOOL_LLM = "v/test/ops/toolllm";
	private static final String TOOL = "v/test/ops/echo";
	/** Shaped like an Anthropic key; not one. */
	private static final String KEY = "sk-ant-api03-" + "x".repeat(40);

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
		userDID = Identity.of("watch-owner").userDID(venue.did());
	}

	@Test
	void aKeyOnItsWayOutWaitsForThorWhoDoesNotAllowIt() throws Exception {
		CompletableFuture<Job> asked = new CompletableFuture<>();
		try (ThorWatch watch = watch(Set.of(TOOL), asked)) {
			String reply = chat("watch-exfil", "Send this out: " + KEY);
			assertTrue(reply.contains(ThorWatch.NOT_ALLOWED), "the call was refused: " + reply);

			Job task = asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertEquals(Strings.create(userDID + "/g/watch-exfil"), RT.getIn(task.getData(), Fields.INPUT, "input", "suspect"),
				"Thor was asked about it: " + task.getData());
		}
	}

	@Test
	void aKeyElsewhereReachesThorAndTheCallRuns() throws Exception {
		CompletableFuture<Job> reported = new CompletableFuture<>();
		try (ThorWatch watch = watch(Set.of(), reported)) {
			String reply = chat("watch-leaky", "Keep this: " + KEY);
			assertTrue(reply.contains("Tool returned"), "the call ran: " + reply);

			Job task = reported.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertEquals(Strings.create(Thor.AGENT_ID), RT.getIn(task.getData(), Fields.INPUT, Fields.AGENT_ID),
				"a task for Thor: " + task.getData());
			assertEquals(Strings.create(userDID + "/g/watch-leaky"), RT.getIn(task.getData(), Fields.INPUT, "input", "suspect"),
				"naming the agent: " + task.getData());
		}
	}

	@Test
	void sendingOutRunsAndReachesThorInTheSummary() throws Exception {
		try (ThorWatch watch = watch(Set.of(TOOL), new CompletableFuture<>())) {
			String reply = chat("watch-chatty", "Tell everyone hello");
			assertTrue(reply.contains("Tool returned"), "the call ran: " + reply);

			Job summary = watch.summarise();
			assertNotNull(summary, "there was something to summarise");
			assertEquals(Strings.create(Thor.AGENT_ID), RT.getIn(summary.getData(), Fields.INPUT, Fields.AGENT_ID));
			assertNotNull(RT.getIn(summary.getData(), Fields.INPUT, "input", "summary", userDID + "/g/watch-chatty", TOOL),
				"naming the agent and what it sent: " + summary.getData());
			assertNull(watch.summarise(), "and starts afresh");
		}
	}

	@Test
	void onlyAUsersAgentsCallsFallInATier() throws Exception {
		// Not started: classifying and checking need no guard on the venue.
		ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, Utils::getCurrentTimestamp, Set.of("discord_send"), job -> {});
		assertNull(watch.classify(call(userDID, "secret_set", Maps.of("name", "anthropic", "value", KEY))),
			"a key where it belongs");
		assertNull(watch.classify(call(venue.did(), "discord_send", Maps.of("content", KEY))),
			"the gods are not watched");
		assertNull(watch.check(call(venue.did(), "discord_send", Maps.of("content", KEY))),
			"so Thor's own calls pass at once, never waiting on him");
		assertNull(watch.classify(call(userDID, "covia_write",
			Maps.of("value", "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ4In0.c2ln and " + "ab".repeat(32)))),
			"tokens and hashes are routine");
		assertNull(watch.classify(call(userDID, "covia_write",
			Maps.of("path", "w/task-scheduler-configuration-2026", "value", "disk-usage-report-for-october"))),
			"nor is a word that merely ends in sk-");
		assertEquals(ThorWatch.Tier.HIGH, watch.classify(call(userDID, "discord_send", Maps.of("content", KEY))).tier());
		assertEquals(ThorWatch.Tier.LOW, watch.classify(call(userDID, "discord_send", Maps.of("content", "hi"))).tier());
		assertEquals(ThorWatch.Tier.MEDIUM, watch.classify(call(userDID, "covia_write", Maps.of("value", KEY))).tier());
	}

	@Test
	void anAgentReportedStaysQuietForTheCooldown() {
		long[] now = {1_000};
		ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, () -> now[0], Set.of(), job -> {});
		ToolGuards.Call leak = call(userDID, "watch-b", "covia_write", Maps.of("value", KEY));
		assertNotNull(watch.classify(leak));
		assertNull(watch.classify(leak), "not twice");
		assertNotNull(watch.classify(call(userDID, "watch-c", "covia_write", Maps.of("value", KEY))),
			"another agent is its own case");
		now[0] += ThorWatch.COOLDOWN_MILLIS;
		assertNotNull(watch.classify(leak), "again once the cooldown has passed");
	}

	/** A started watch, classing {@code outward} as sending out, telling {@code reports} of the first task. */
	private static ThorWatch watch(Set<String> outward, CompletableFuture<Job> reports) {
		ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, Utils::getCurrentTimestamp, outward,
			job -> { if (reports != null) reports.complete(job); });
		watch.start();
		return watch;
	}

	/** {@code message} to a new agent on the tool-calling test model; its reply. */
	private static String chat(String agent, String message) throws Exception {
		ChatSession chat = new ChatSession(venue.clientAs(userDID),
			new AppConfig.Chat(agent, AppConfig.DEFAULT_OPERATION, TOOL_LLM, "Use your tools."));
		return String.valueOf(chat.send(message).text());
	}

	private static ToolGuards.Call call(String owner, String tool, AMap<AString, ACell> input) {
		return call(owner, "watch-a", tool, input);
	}

	/** An operation call as the venue's guards see it. */
	private static ToolGuards.Call call(String owner, String agentId, String tool, AMap<AString, ACell> input) {
		return new ToolGuards.Call(Strings.create(owner), Strings.create(agentId), null, tool, Strings.create(tool), input);
	}
}
