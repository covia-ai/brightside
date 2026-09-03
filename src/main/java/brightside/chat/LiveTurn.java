package brightside.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import brightside.SessionHistory;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.venue.AgentEvents;

/**
 * The turn in flight, as the owner should see it: the steps the assistant has
 * taken so far — its narration and each tool call, in order — and a one-line
 * status for the pending-reply bubble, both built from the venue's live agent
 * events. No Swing here, so the mapping from events to what is shown is
 * testable on its own.
 *
 * <p>The steps are the {@link SessionHistory.Step}s the stored turn projects
 * to once the reply lands — the tool's own name as the title, inputs and
 * results rendered the same way — so the chip built live can stand as the
 * transcript's chip without being rebuilt.
 *
 * <p>Only what the venue sends is shown; the status is never manufactured from
 * elapsed time. A model call is "Thinking…", a tool call is its display name,
 * and when the assistant narrates a step its own words are the status.
 */
public final class LiveTurn {

	public static final String PREPARING = "Preparing…";
	public static final String WORKING = "Working…";
	public static final String THINKING = "Thinking…";
	/** The status is one line; longer narration is cut at a word. */
	static final int STATUS_CHARS = 90;

	private final List<SessionHistory.Step> steps = new ArrayList<>();
	/** Tool call id → index of its running step, until its result arrives. */
	private final Map<String, Integer> running = new HashMap<>();
	private String status = PREPARING;

	/** The one-line status for the bubble. */
	public String status() {
		return status;
	}

	/** The steps so far as a transcript item, or null before the first step. */
	public SessionHistory.Activity activity() {
		return steps.isEmpty() ? null : new SessionHistory.Activity(List.copyOf(steps));
	}

	/** The venue accepted the message; only worth saying while nothing else has happened. */
	public boolean accepted() {
		return PREPARING.equals(status) && setStatus(WORKING);
	}

	/**
	 * Applies one agent event. {@code toolLabel} is the display name for a tool
	 * call's status line ("Moltbook home…"), or null to fall back to the tool's
	 * name; the step itself keeps the tool's own name, as the stored turn does.
	 *
	 * @return true when something the owner sees changed
	 */
	public boolean apply(AgentEvents.Event event, String toolLabel) {
		AString type = event.type();
		if (AgentEvents.INFERENCE_START.equals(type)) return setStatus(THINKING);
		if (AgentEvents.INFERENCE_END.equals(type)) return narrated(event.data());
		if (AgentEvents.TOOL_START.equals(type)) return toolStarted(event.data(), toolLabel);
		if (AgentEvents.TOOL_RESULT.equals(type)) return toolFinished(event.data());
		return false;
	}

	/**
	 * A reply that asks for tools and says something on the way is narration:
	 * a step of its own and the best status there is. A reply with no tool
	 * calls is the answer, which arrives through the chat job, not here.
	 */
	private boolean narrated(AMap<AString, ACell> data) {
		if (!(RT.getIn(data, "toolCalls") instanceof AVector<?> calls) || calls.isEmpty()) return false;
		String content = str(RT.getIn(data, "content"));
		if (content == null || content.isBlank()) return false;
		steps.add(new SessionHistory.Step(false, "", content, false, null));
		setStatus(oneLine(content));
		return true;
	}

	private boolean toolStarted(AMap<AString, ACell> data, String toolLabel) {
		String name = str(RT.getIn(data, "name"));
		String title = (name != null) ? name : "tool";
		ACell input = RT.getIn(data, "detail", "input");
		String call = (input != null) ? SessionHistory.renderContent(input) : null;
		steps.add(new SessionHistory.Step(true, title, null, false, call));
		String id = str(RT.getIn(data, "id"));
		if (id != null) running.put(id, steps.size() - 1);
		String label = (toolLabel != null && !toolLabel.isBlank()) ? toolLabel : title.replace('_', ' ');
		setStatus(label + "…");
		return true;
	}

	/**
	 * The result completes its running step, found by call id, else the latest
	 * unfinished step of that name. A result whose start was missed still
	 * shows, as a finished step.
	 */
	private boolean toolFinished(AMap<AString, ACell> data) {
		String id = str(RT.getIn(data, "id"));
		String name = str(RT.getIn(data, "name"));
		boolean error = CVMBool.TRUE.equals(RT.getIn(data, "isError"));
		String result = SessionHistory.renderContent(RT.getIn(data, "detail", "result"));
		Integer at = (id != null) ? running.remove(id) : null;
		if (at == null) at = latestRunning(name);
		if (at == null) {
			steps.add(new SessionHistory.Step(true, (name != null) ? name : "tool", result, error, null));
			return true;
		}
		SessionHistory.Step started = steps.get(at);
		steps.set(at, new SessionHistory.Step(true, started.title(), result, error, started.call()));
		return true;
	}

	private Integer latestRunning(String name) {
		for (int i = steps.size() - 1; i >= 0; i--) {
			SessionHistory.Step s = steps.get(i);
			if (s.tool() && s.detail() == null && (name == null || name.equals(s.title()))) return i;
		}
		return null;
	}

	private boolean setStatus(String text) {
		if (text == null || text.isBlank() || text.equals(status)) return false;
		status = text;
		return true;
	}

	/** The first line of the narration, whitespace collapsed, cut at a word to fit the bubble. */
	static String oneLine(String text) {
		String line = null;
		for (String candidate : text.split("\\R")) {
			if (!candidate.isBlank()) {
				line = candidate.trim().replaceAll("\\s+", " ");
				break;
			}
		}
		if (line == null) return text.trim();
		if (line.length() <= STATUS_CHARS) return line;
		int cut = line.lastIndexOf(' ', STATUS_CHARS);
		if (cut < STATUS_CHARS / 2) cut = STATUS_CHARS;
		return line.substring(0, cut).stripTrailing() + "…";
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}
}
