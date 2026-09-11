package brightside.ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import brightside.EmbeddedVenue;
import convex.core.data.ACell;
import convex.core.data.AString;

/** Catalogue labels for the session view's live tool activity. */
public final class ActivityLabels {

	/** Humanised tool labels by tool name, resolved once from the op catalogue. */
	private final Map<String, String> toolLabels = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The display name of the tool a {@code tool:start} event names, else null:
	 * the label the venue put on the event when it has one beyond the raw name
	 * (covia#463), else the op asset's {@code name} from the catalogue. Called
	 * on the venue's emitting thread — in-process reads only, never blocking.
	 */
	public String label(EmbeddedVenue v, String did, covia.venue.AgentEvents.Event event) {
		if (!covia.venue.AgentEvents.TOOL_START.equals(event.type())) return null;
		AString name = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(event.data(), "name"));
		if (name == null) return null;
		AString label = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(event.data(), "activityLabel"));
		if (label != null && !label.isEmpty() && !label.equals(name)) return label.toString();
		return toolLabel(v, did, name.toString());
	}

	/**
	 * The tool's display name: its op asset's {@code name} when one resolves,
	 * else the tool name with the underscores spaced. Cached per tool.
	 */
	private String toolLabel(EmbeddedVenue v, String did, String toolName) {
		return toolLabels.computeIfAbsent(toolName, t -> {
			for (String path : toolPathCandidates(t)) {
				ACell asset = v.resolve(did, path);
				AString n = (asset == null) ? null
					: convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(asset, "name"));
				if (n != null && !n.isEmpty()) return n.toString();
			}
			return t.replace('_', ' ');
		});
	}

	/**
	 * Where a provider tool name probably lives in the catalogue:
	 * {@code moltbook_read_post} → {@code v/ops/moltbook/read-post}, then
	 * {@code v/ops/moltbook/read/post}.
	 */
	public static List<String> toolPathCandidates(String toolName) {
		List<String> out = new ArrayList<>();
		int us = toolName.indexOf('_');
		if (us > 0) {
			out.add("v/ops/" + toolName.substring(0, us) + "/" + toolName.substring(us + 1).replace('_', '-'));
		}
		out.add("v/ops/" + toolName.replace('_', '/'));
		return out;
	}

}
