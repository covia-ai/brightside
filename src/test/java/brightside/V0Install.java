package brightside;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import brightside.chat.ChatSession;
import brightside.vault.Vault;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Venue;

/**
 * Makes the Brightside install that {@link CoviaMigrationTest} upgrades: a
 * store written by a build from before Covia versioned its venue state and
 * its conversations ({@code src/test/resources/brightside/v0-install}).
 *
 * <p>It runs only when asked, from a worktree of the old build (the README
 * beside the fixture says which, and how), and it must compile there and here
 * alike: {@link #describe} is how both builds write down what the owner sees,
 * so it uses only what the two have in common.</p>
 */
class V0Install {

	/** Any 32 bytes are an Ed25519 seed; this one stands in for the owner's. */
	static final String SEED = "5e".repeat(32);
	static final String PASSPHRASE = "the v0 owner";
	static final String OWNER = "owner";
	static final String NOTES_AGENT = "notes";
	static final String TOOLS_AGENT = "tools";
	static final String TOOL_LLM_OPERATION = "v/test/ops/toolllm";
	static final String EXPECTED = "expected.txt";

	/** {@code -Dbrightside.v0.out=<dir>}: where to make the install. */
	@Test
	void generate() throws Exception {
		String out = System.getProperty("brightside.v0.out");
		assumeTrue(out != null, "makes the v0 install only when asked");
		Path home = Path.of(out);
		List<String> written;
		try (Launched launched = launch(home)) {
			write(launched.venue());
			written = describe(launched.venue());
		}
		Files.write(home.resolve(EXPECTED), written, StandardCharsets.UTF_8);
		// The old build reads its own install back after a restart, as it was written.
		try (Launched reopened = launch(home)) {
			List<String> reread = describe(reopened.venue());
			if (!written.equals(reread)) throw new AssertionError("the v0 build reads its install differently after a restart");
		}
	}

	/** Every kind of conversation the owner can have had. */
	private static void write(EmbeddedVenue venue) throws Exception {
		String userDID = Identity.of(OWNER).userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		AppConfig.Chat notes = new AppConfig.Chat(NOTES_AGENT,
			AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		ChatSession chat = new ChatSession(client, notes, "Owner");

		// A plain conversation.
		chat.send("the first question");
		chat.send("the second question");

		// One compacted, carried on, compacted again and carried on.
		chat.reset();
		String compacted = chat.send("an opening topic").sessionId();
		chat.send("more on that topic");
		compact(venue, client, userDID, compacted, "We covered the opening topic.");
		chat.send("a new direction");
		compact(venue, client, userDID, compacted, "We covered the topic, then a new direction.");
		chat.send("after both summaries");

		// One the owner renamed.
		chat.reset();
		String renamed = chat.send("a chat worth naming").sessionId();
		invoke(client, "v/ops/agent/rename-session",
			Maps.of("agentId", NOTES_AGENT, "sessionId", renamed, "title", "A named chat"));

		// One a channel delivered, then typed in.
		chat.reset();
		invoke(client, "v/ops/agent/chat", Maps.of(
			"agentId", NOTES_AGENT,
			"message", Maps.of(
				"text", "hello from a discord channel",
				"via", Maps.of(
					"channel", "discord",
					"from", Maps.of("id", "123", "username", "quark"),
					"chat", Maps.of("id", "456", "type", "TEXT", "name", "general")))));
		ChatSession typed = new ChatSession(client, notes, "Owner");
		typed.resume(SessionHistory.sessionsOf(venue.agentRecord(userDID, NOTES_AGENT)).get(0).sessionId());
		typed.send("and typed right here");

		// Another agent, whose turns call a tool before they answer.
		AppConfig.Chat tools = new AppConfig.Chat(TOOLS_AGENT,
			AppConfig.DEFAULT_OPERATION, TOOL_LLM_OPERATION, "Use a tool, then answer.");
		new ChatSession(client, tools, "Owner").send("look something up");
	}

	/**
	 * What the owner sees of every agent and session, a line each: the session
	 * list, each transcript and each raw turn, in neither build's types.
	 */
	static List<String> describe(EmbeddedVenue venue) {
		String userDID = Identity.of(OWNER).userDID(venue.did());
		List<String> out = new ArrayList<>();
		for (String agentId : List.of(NOTES_AGENT, TOOLS_AGENT)) {
			out.addAll(describe(agentId, venue.agentRecord(userDID, agentId)));
		}
		return out;
	}

	/** One agent's record, described as {@link #describe(EmbeddedVenue)} does. */
	static List<String> describe(String agentId, ACell record) {
		List<String> out = new ArrayList<>();
		out.add("agent " + agentId);
		for (SessionHistory.Session s : SessionHistory.sessionsOf(record)) {
			out.add("session " + s.sessionId() + " | " + line(s.title()) + " | " + s.lastTs());
			for (SessionHistory.Item item : SessionHistory.snapshotOf(record, s.sessionId()).items()) {
				if (item instanceof SessionHistory.Message m) {
					out.add("  message " + m.role() + " | " + line(m.text()) + " | " + line(m.origin()));
				} else if (item instanceof SessionHistory.Summary sum) {
					out.add("  summary " + sum.turns() + " | " + line(sum.text()));
				} else if (item instanceof SessionHistory.Activity a) {
					out.add("  activity");
					for (SessionHistory.Step step : a.steps()) {
						out.add("    step " + step.tool() + " | " + line(step.title()) + " | " + line(step.detail())
							+ " | " + step.error() + " | " + line(step.call()));
					}
				} else {
					out.add("  item " + line(String.valueOf(item)));
				}
			}
			for (SessionHistory.RawTurn t : SessionHistory.rawTurnsOf(record, s.sessionId())) {
				out.add("  raw " + t.role() + " | " + line(t.content()) + " | " + line(t.toolResult())
					+ " | " + t.error() + " | " + line(t.meta()));
				for (SessionHistory.RawCall c : t.calls()) {
					out.add("    call " + c.name() + " | " + c.id() + " | " + line(c.args()));
				}
			}
		}
		return out;
	}

	private static String line(String s) {
		return (s == null) ? "-" : s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
	}

	/** Compacts once the session is idle: the venue refuses compaction mid-cycle. */
	static void compact(EmbeddedVenue venue, Venue client, String userDID, String sid, String summary)
			throws Exception {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (!SessionHistory.isSessionActive(venue.agentRecord(userDID, NOTES_AGENT), sid)) {
				try {
					invoke(client, "v/ops/agent/compact-session",
						Maps.of("agentId", NOTES_AGENT, "sessionId", sid, "summary", summary));
					return;
				} catch (Exception e) {
					if (!String.valueOf(e.getMessage()).contains("active")) throw e;
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("the session never went idle");
	}

	static void invoke(Venue client, String op, ACell input) throws Exception {
		var job = client.invoke(op, input).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);
	}

	record Launched(EmbeddedVenue venue) implements AutoCloseable {
		@Override
		public void close() {
			venue.close();
		}
	}

	/** Launches the way Brightside does: the store keyed from the seed, the vault from the passphrase. */
	static Launched launch(Path home) throws IOException {
		Vault vault = Vault.open(home, PASSPHRASE.toCharArray());
		AMap<AString, ACell> config = vault.secure(AppConfig.defaultVenue(home), SEED)
			.assoc(Fields.PORT, CVMLong.create(0));
		return new Launched(EmbeddedVenue.launch(config));
	}
}
