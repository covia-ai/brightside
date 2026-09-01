package brightside;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Principals;
import covia.grid.Venue;

/**
 * What an agent <em>is</em>: the venue's {@code agent:info} summary (status,
 * configuration, unavailable tools) joined with its record's conversations,
 * structured for the agent info screen ({@link brightside.ui.inspect.AgentInspector}).
 */
public final class AgentInfo {

	private static final Logger log = LoggerFactory.getLogger(AgentInfo.class);
	private static final long TIMEOUT_SECONDS = 30;

	/** One pinned load: its key, label, kind ({@code skill}/{@code op}/{@code entry}) and byte budget. */
	public record Pin(String ref, String label, String kind, long budget) {
	}

	/**
	 * The whole picture. {@code standard} marks Brightside's default agent; {@code lastActive}
	 * is epoch millis, 0 when it has never chatted; {@code rawJson} is the untouched summary.
	 */
	public record Summary(String id, String name, String did, String ownerDID, String ownerName, boolean standard,
			String status, String error, long tasks, long timelineLength,
			String model, String operation, String systemPrompt,
			boolean defaultTools, List<String> tools, List<String> skillsets, List<Pin> pins, List<String> context,
			List<String> unavailable, int conversations, long lastActive, String rawJson) {
	}

	private AgentInfo() {
	}

	/**
	 * Reads {@code agentId}'s summary as the owner and joins it with the already-read
	 * {@code record} ({@code g/<agentId>}, may be null). Null if the agent does not exist.
	 */
	public static Summary load(Venue client, ACell record, String agentId, String ownerDID, String ownerName,
			boolean standard) {
		ACell info;
		try {
			// A read: the op is declared readOnly, so no job record is left behind.
			info = client.run("v/ops/agent/info", Maps.of("agentId", agentId)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Could not read agent info for {}: {}", agentId, e.toString());
			return null;
		}
		if (!(info instanceof AMap)) return null;

		ACell config = RT.getIn(info, Fields.CONFIG);
		String name = str(RT.getIn(config, "name"));
		List<SessionHistory.Session> sessions = (record != null) ? SessionHistory.sessionsOf(record) : List.of();
		long lastActive = sessions.isEmpty() ? 0 : sessions.getFirst().lastTs();
		String did = Principals.agentDID(Strings.create(ownerDID), Strings.create(agentId)).toString();

		return new Summary(agentId, (name != null && !name.isBlank()) ? name : agentId, did, ownerDID, ownerName,
			standard, str(RT.getIn(info, Fields.STATUS)), render(RT.getIn(info, "error")),
			lng(RT.getIn(info, "tasks")), lng(RT.getIn(info, "timelineLength")),
			str(RT.getIn(config, "llmOperation")), str(RT.getIn(config, Fields.OPERATION)),
			str(RT.getIn(config, "systemPrompt")),
			CVMBool.TRUE.equals(RT.getIn(config, "defaultTools")),
			strings(RT.getIn(config, "tools")), strings(RT.getIn(config, "skillsets")),
			pins(RT.getIn(config, Fields.LOADS)), context(RT.getIn(config, "context")),
			rendered(RT.getIn(info, Fields.UNAVAILABLE_TOOLS)),
			sessions.size(), lastActive, JSON.toStringPretty(info));
	}

	/** {@code config.loads}: a map of load key to its entry. */
	private static List<Pin> pins(ACell loads) {
		List<Pin> out = new ArrayList<>();
		if (loads instanceof AMap<?, ?> m) {
			for (long i = 0; i < m.count(); i++) {
				MapEntry<?, ?> e = m.entryAt(i);
				ACell entry = (ACell) e.getValue();
				String kind = CVMBool.TRUE.equals(RT.getIn(entry, "skill")) ? "skill"
					: (RT.getIn(entry, "op") != null) ? "op" : "entry";
				out.add(new Pin(e.getKey().toString(), str(RT.getIn(entry, "label")), kind, lng(RT.getIn(entry, "budget"))));
			}
		}
		return out;
	}

	/** {@code config.context}: each entry by its label, else its op. */
	private static List<String> context(ACell context) {
		List<String> out = new ArrayList<>();
		if (context instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				ACell c = (ACell) v.get(i);
				String label = str(RT.getIn(c, "label"));
				out.add((label != null) ? label : render(RT.getIn(c, "op")));
			}
		}
		return out;
	}

	private static List<String> strings(ACell cell) {
		List<String> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				String s = str((ACell) v.get(i));
				if (s != null) out.add(s);
			}
		}
		return out;
	}

	private static List<String> rendered(ACell cell) {
		List<String> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) out.add(render((ACell) v.get(i)));
		}
		return out;
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	/** Null stays null, strings verbatim, anything else pretty-printed as JSON. */
	private static String render(ACell cell) {
		if (cell == null) return null;
		if (cell instanceof AString s) return s.toString();
		return JSON.toStringPretty(cell);
	}

	private static long lng(ACell cell) {
		return (cell instanceof CVMLong l) ? l.longValue() : 0;
	}
}
