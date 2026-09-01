package brightside;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Venue;

/**
 * Reads, via {@code v/ops/agent/context}, the <b>exact context an agent would
 * send to its model</b> for a session — assembled through the same path as a
 * live turn, but without calling the model — and structures it for display.
 *
 * <p>This is the whole picture: the system/user messages (identity, the skills
 * index, pinned memory, every loaded skill body, the conversation), the tool
 * palette the model is offered, which skills/context entries are loaded, and the
 * byte budget. See {@link brightside.ui.inspect.ContextInspector}.
 */
public final class AgentContext {

	private static final Logger log = LoggerFactory.getLogger(AgentContext.class);
	private static final long TIMEOUT_SECONDS = 30;

	/** One tool call on an assembled assistant message: its {@code name}, {@code id}, and rendered {@code args}. */
	public record Call(String name, String id, String args) {
	}

	/**
	 * One assembled message the model receives ({@code system}/{@code user}/
	 * {@code assistant}/{@code tool}). {@code text} is its content — blank for
	 * an assistant message that only makes {@code calls}, and for a tool message
	 * whose result is structured. A {@code tool} message pairs with its call by
	 * {@code name} and {@code id}; {@code result} renders its
	 * {@code structuredContent} (null when the result is plain text, in
	 * {@code text}) and {@code error} its {@code isError}. Loaded context and job
	 * results arrive this way too, as {@code pinned_context}/{@code loaded_context}/
	 * {@code get_job_results} exchanges, so nothing the model sees is elided.
	 */
	public record Message(String role, String text, List<Call> calls, String name, String id,
			String result, boolean error) {
	}

	/**
	 * One tool definition the model receives: its {@code name} and
	 * {@code description} exactly as sent, and its provenance — {@code source}
	 * (harness / default / config / skill), the venue {@code operation} behind
	 * it and the {@code skill} that declares it, where the palette says. A
	 * {@code requiresSkill} tool is a gate: it is declared so the model can see
	 * it, but calling it before loading a skill that provides it fails.
	 */
	public record Tool(String name, String description, String source, String operation, String skill,
			boolean requiresSkill) {
	}

	/**
	 * A band of the assembled messages, {@code [from, to)}: the head (identity,
	 * the skills index, pinned context), the live surface, the conversation,
	 * the tool loop, the inference tail. From the report's {@code marks}.
	 */
	public record Band(String name, int from, int to) {
		public int size() {
			return to - from;
		}
	}

	/** One loaded context entry (a skill body, pinned context, …) and its accounting. */
	public record Load(String ref, String kind, String status, long bytes, long budget,
			boolean truncated, boolean deduplicated) {
	}

	/**
	 * The full assembled-context report. {@code budgetBytes} is the model's
	 * declared context size — a guide the assembler warns against, not a cap,
	 * so {@code budgetUsed} may exceed it. {@code cacheMarks} are the message
	 * indexes at which a cached prefix ends. {@code rawJson} is the untouched
	 * report for the Raw view.
	 */
	public record Report(String model, long budgetBytes, long budgetUsed, long budgetRemaining,
			String sessionTokens, List<Message> messages, List<Band> bands, List<Long> cacheMarks,
			List<Tool> tools, List<String> unavailable, List<Load> loads, String rawJson) {

		/** Budget used as a percentage of the declared size; may exceed 100. */
		public int budgetPercent() {
			return (budgetBytes > 0) ? (int) Math.round(100.0 * budgetUsed / budgetBytes) : 0;
		}
	}

	private AgentContext() {
	}

	/**
	 * Assembles the context for {@code agentId}'s session {@code sessionId} (or,
	 * with a null session, the fresh-transition context). Returns null on failure.
	 */
	public static Report load(Venue client, String agentId, String sessionId) {
		try {
			AMap<AString, ACell> input = Maps.of("agentId", agentId);
			if (sessionId != null) input = input.assoc(Strings.create("sessionId"), Strings.create(sessionId));
			// A read: the op is declared readOnly, so no job record is left behind.
			ACell report = client.run("v/ops/agent/context", input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return (report instanceof AMap) ? parse(report) : null;
		} catch (Exception e) {
			log.warn("Could not assemble agent context for {}: {}", agentId, e.toString());
			return null;
		}
	}

	private static Report parse(ACell report) {
		ACell budget = RT.getIn(report, "budget");
		long bytes = lng(RT.getIn(budget, "bytes"));
		long used = lng(RT.getIn(budget, "used"));
		long remaining = lng(RT.getIn(budget, "remaining"));
		String sessionTokens = formatTokens(RT.getIn(report, "sessionTokens"));

		List<Message> messages = new ArrayList<>();
		if (RT.getIn(report, "messages") instanceof AVector<?> ms) {
			for (long i = 0; i < ms.count(); i++) messages.add(message((ACell) ms.get(i)));
		}

		// Provenance for each tool (harness / default / config / skill), from the palette sidecar.
		Map<String, ACell> provenance = new HashMap<>();
		List<String> unavailable = new ArrayList<>();
		ACell palette = RT.getIn(report, "palette");
		if (RT.getIn(palette, "tools") instanceof AVector<?> pts) {
			for (long i = 0; i < pts.count(); i++) {
				ACell t = (ACell) pts.get(i);
				String name = str(RT.getIn(t, "name"));
				if (name != null) provenance.put(name, t);
			}
		}
		if (RT.getIn(palette, "unavailable") instanceof AVector<?> un) {
			for (long i = 0; i < un.count(); i++) unavailable.add(render((ACell) un.get(i)));
		}

		List<Tool> tools = new ArrayList<>();
		if (RT.getIn(report, "tools") instanceof AVector<?> ts) {
			for (long i = 0; i < ts.count(); i++) {
				ACell t = (ACell) ts.get(i);
				String name = str(RT.getIn(t, "name"));
				ACell entry = provenance.get(name);
				tools.add(new Tool(name, str(RT.getIn(t, "description")), str(RT.getIn(entry, "source")),
					str(RT.getIn(entry, "operation")), str(RT.getIn(entry, "skill")),
					bool(RT.getIn(t, "requiresSkill"))));
			}
		}

		List<Long> cacheMarks = new ArrayList<>();
		if (RT.getIn(report, "cacheMarks") instanceof AVector<?> cms) {
			for (long i = 0; i < cms.count(); i++) cacheMarks.add(lng((ACell) cms.get(i)));
		}

		List<Load> loads = new ArrayList<>();
		if (RT.getIn(report, "loads") instanceof AVector<?> ls) {
			for (long i = 0; i < ls.count(); i++) {
				ACell l = (ACell) ls.get(i);
				loads.add(new Load(str(RT.getIn(l, "ref")), str(RT.getIn(l, "kind")), str(RT.getIn(l, "status")),
					lng(RT.getIn(l, "bytes")), lng(RT.getIn(l, "budget")),
					bool(RT.getIn(l, "truncated")), bool(RT.getIn(l, "deduplicated"))));
			}
		}

		return new Report(render(RT.getIn(report, "model")), bytes, used, remaining, sessionTokens,
			messages, bands(RT.getIn(report, "marks"), messages.size()), List.copyOf(cacheMarks),
			tools, unavailable, loads, JSON.toStringPretty(report));
	}

	/**
	 * The bands the report's {@code marks} divide {@code count} messages into —
	 * each mark is where its band ends — dropping any that are empty. Without
	 * marks, one band holds everything.
	 */
	static List<Band> bands(ACell marks, int count) {
		if (!(marks instanceof AMap)) {
			return (count > 0) ? List.of(new Band("Messages", 0, count)) : List.of();
		}
		int head = mark(marks, "head", 0, count);
		int live = mark(marks, "live", head, count);
		int conversation = mark(marks, "conversation", live, count);
		int toolLoop = mark(marks, "toolLoop", conversation, count);
		List<Band> out = new ArrayList<>();
		band(out, "Head", 0, head);
		band(out, "Live", head, live);
		band(out, "Conversation", live, conversation);
		band(out, "Tool loop", conversation, toolLoop);
		band(out, "Tail", toolLoop, count);
		return List.copyOf(out);
	}

	private static int mark(ACell marks, String key, int atLeast, int atMost) {
		return (int) Math.max(atLeast, Math.min(atMost, lng(RT.getIn(marks, key))));
	}

	private static void band(List<Band> out, String name, int from, int to) {
		if (to > from) out.add(new Band(name, from, to));
	}

	/**
	 * The provider-facing message shape {@code {role, content?, toolCalls?, id?,
	 * name?, structuredContent?, isError?}}: a tool result is in
	 * {@code structuredContent} when typed, {@code content} when plain text.
	 */
	private static Message message(ACell m) {
		List<Call> calls = new ArrayList<>();
		if (RT.getIn(m, "toolCalls") instanceof AVector<?> tcs) {
			for (long i = 0; i < tcs.count(); i++) {
				ACell c = (ACell) tcs.get(i);
				calls.add(new Call(str(RT.getIn(c, "name")), str(RT.getIn(c, "id")),
					render(RT.getIn(c, "arguments"))));
			}
		}
		ACell structured = RT.getIn(m, "structuredContent");
		return new Message(str(RT.getIn(m, "role")), render(RT.getIn(m, "content")), List.copyOf(calls),
			str(RT.getIn(m, "name")), str(RT.getIn(m, "id")),
			(structured != null) ? render(structured) : null, bool(RT.getIn(m, "isError")));
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	/** Strings verbatim, anything else pretty-printed as JSON. */
	private static String render(ACell cell) {
		if (cell == null) return "";
		if (cell instanceof AString s) return s.toString();
		return JSON.toStringPretty(cell);
	}

	/** Session token usage rendered as "N total (in · out · cache r/w)", or raw if unexpected. */
	private static String formatTokens(ACell tokens) {
		if (tokens == null) return null;
		if (!(RT.getIn(tokens, "total") instanceof CVMLong total)) return render(tokens);
		return String.format("%,d total  (%,d in · %,d out · %,d cache read · %,d cache write)",
			total.longValue(), lng(RT.getIn(tokens, "input")), lng(RT.getIn(tokens, "output")),
			lng(RT.getIn(tokens, "cacheRead")), lng(RT.getIn(tokens, "cacheWrite")));
	}

	private static long lng(ACell cell) {
		return (cell instanceof CVMLong l) ? l.longValue() : 0L;
	}

	private static boolean bool(ACell cell) {
		return CVMBool.TRUE.equals(cell);
	}
}
