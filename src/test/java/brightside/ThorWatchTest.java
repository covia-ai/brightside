package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import covia.venue.AgentEvents;

/**
 * Thor's watch on a real venue: a user's agent passing something shaped like
 * a key to a tool outside the secret store reaches Thor as a task naming it,
 * and nothing else does — a key where it belongs, the gods, routine tokens
 * and hashes, or the same agent again within the cooldown.
 */
class ThorWatchTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given. */
	private static final String TASK_LLM = "v/test/ops/taskllm";
	/** Calls a tool with the user's message as its input, then answers. */
	private static final String TOOL_LLM = "v/test/ops/toolllm";
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
	void aKeyInAnAgentsToolCallReachesThor() throws Exception {
		CompletableFuture<Job> reported = new CompletableFuture<>();
		try (ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, Utils::getCurrentTimestamp, reported::complete)) {
			watch.start();
			String agent = "watch-leaky";
			ChatSession chat = new ChatSession(venue.clientAs(userDID),
				new AppConfig.Chat(agent, AppConfig.DEFAULT_OPERATION, TOOL_LLM, "Use your tools."));
			chat.send("Post this for me: " + KEY);

			Job task = reported.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertEquals(Strings.create(Thor.AGENT_ID), RT.getIn(task.getData(), Fields.INPUT, Fields.AGENT_ID),
				"a task for Thor: " + task.getData());
			assertEquals(Strings.create(userDID + "/g/" + agent), RT.getIn(task.getData(), Fields.INPUT, "input", "suspect"),
				"naming the agent: " + task.getData());
			ACell outcome = task.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertEquals(Strings.create("COMPLETE"), RT.getIn(outcome, Fields.STATUS), "and he takes it up: " + outcome);
		}
	}

	@Test
	void onlyAKeyOutsideTheSecretStoreFromAUsersAgentCounts() {
		ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, () -> 0L, job -> {});
		assertNull(watch.observe(toolCall(userDID, "watch-a", "secret_set", Maps.of("name", "anthropic", "value", KEY))),
			"a key where it belongs");
		assertNull(watch.observe(toolCall(venue.did(), Odin.AGENT_ID, "covia_write", Maps.of("value", KEY))),
			"the gods are not watched");
		assertNull(watch.observe(toolCall(userDID, "watch-a", "covia_write",
			Maps.of("value", "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ4In0.c2ln and " + "ab".repeat(32)))),
			"tokens and hashes are routine");
		assertNotNull(watch.observe(toolCall(userDID, "watch-a", "discord_send", Maps.of("content", "here: " + KEY))),
			"a key leaving");
	}

	@Test
	void anAgentReportedStaysQuietForTheCooldown() {
		long[] now = {1_000};
		ThorWatch watch = new ThorWatch(venue, () -> TASK_LLM, () -> now[0], job -> {});
		AgentEvents.Event leak = toolCall(userDID, "watch-b", "covia_write", Maps.of("value", KEY));
		assertNotNull(watch.observe(leak));
		assertNull(watch.observe(leak), "not twice");
		assertNotNull(watch.observe(toolCall(userDID, "watch-c", "covia_write", Maps.of("value", KEY))),
			"another agent is its own case");
		now[0] += ThorWatch.COOLDOWN_MILLIS;
		assertNotNull(watch.observe(leak), "again once the cooldown has passed");
	}

	/** A {@code tool:start} event, as the venue emits one, for {@code tool} called with {@code input}. */
	private static AgentEvents.Event toolCall(String owner, String agentId, String tool, AMap<AString, ACell> input) {
		AMap<AString, ACell> data = Maps.of(Fields.NAME, tool, Fields.DETAIL, Maps.of(Fields.INPUT, input));
		return new AgentEvents.Event(Strings.create(owner), Strings.create(agentId), 1, 0, AgentEvents.TOOL_START, data);
	}
}
