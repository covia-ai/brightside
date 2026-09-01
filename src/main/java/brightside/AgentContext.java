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

	/** One offered tool: its name, description, and where it came from (harness/config/a loaded skill). */
	public record Tool(String name, String description, String source) {
	}

	/** One loaded context entry (a skill body, pinned context, …) and its accounting. */
	public record Load(String ref, String kind, String status, long bytes, long budget,
			boolean truncated, boolean deduplicated) {
	}

	/** The full assembled-context report. {@code rawJson} is the untouched report for the Raw view. */
	public record Report(String model, long budgetBytes, long budgetUsed, long budgetRemaining,
			String sessionTokens, List<Message> messages, List<Tool> tools,
			List<String> unavailable, List<Load> loads, String rawJson) {
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

		// Provenance for each tool (harness / config / a loaded skill).
		Map<String, String> source = new HashMap<>();
		List<String> unavailable = new ArrayList<>();
		ACell palette = RT.getIn(report, "palette");
		if (RT.getIn(palette, "tools") instanceof AVector<?> pts) {
			for (long i = 0; i < pts.count(); i++) {
				ACell t = (ACell) pts.get(i);
				String name = str(RT.getIn(t, "name"));
				if (name != null) source.put(name, str(RT.getIn(t, "source")));
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
				tools.add(new Tool(name, str(RT.getIn(t, "description")), source.get(name)));
			}
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
			messages, tools, unavailable, loads, JSON.toStringPretty(report));
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
