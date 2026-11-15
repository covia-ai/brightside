package brightside.model;

import static brightside.BrightSide.APP_NAME;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;

/** A chat agent the user can pick in the agents pane: its {@code id} (path segment) and display {@code name}. */
public record AgentRef(String id, String name) {

	/**
	 * The name to show for agent {@code id}, whose record is {@code record}
	 * (or null): the name configured on it; for the owner's standard
	 * assistant ({@code standardId}, or null for none) without one, the app's
	 * own name, since that assistant is the app to its owner; otherwise the id.
	 */
	public static String displayName(ACell record, String id, String standardId) {
		AString name = (record == null) ? null : RT.ensureString(RT.getIn(record, "config", "name"));
		if (name != null && !name.toString().isBlank()) return name.toString().trim();
		return (id != null && id.equals(standardId)) ? APP_NAME : id;
	}
}
