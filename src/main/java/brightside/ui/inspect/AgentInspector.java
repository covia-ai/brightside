package brightside.ui.inspect;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.JComponent;

import brightside.AgentInfo;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;

/**
 * A read-only view of one agent (from {@link AgentInfo}), as one
 * {@link Readout} document: identity, status, model and activity; the system
 * prompt and what is pinned into every turn; the always-on tools, skill
 * libraries and anything unavailable; and, folded at the end, the venue's raw
 * {@code agent:info} summary. Everything shown is selectable and copyable.
 * {@link SessionViews} shows it as the Agent tab above the chat.
 */
public final class AgentInspector {

	private AgentInspector() {
	}

	/** The agent's document, scrolling. */
	public static JComponent view(AgentInfo.Summary a) {
		return Scrolls.vertical(document(a));
	}

	static Readout document(AgentInfo.Summary a) {
		Readout d = new Readout();
		d.pair("Name", a.name());
		d.pair("Id", a.id());
		d.pair("Agent DID", a.did());
		d.pair("Owner", a.ownerName() + "  ·  " + a.ownerDID());
		d.pair("Status", status(a));
		d.pair("Model", orDash(a.model()));
		d.pair("Operation", orDash(a.operation()));
		d.pair("Conversations", activity(a));
		d.pair("Tasks", Long.toString(a.tasks()));
		d.pair("Timeline", a.timelineLength() + " event" + (a.timelineLength() == 1 ? "" : "s"));
		d.note(a.standard()
			? "Brightside's standard agent: its model and instructions follow Settings and config.json."
			: "Created from the agents pane; it keeps the model and instructions it was given.");

		d.section("System prompt");
		d.prose(orDash(a.systemPrompt()));
		d.section("In every turn");
		for (String c : a.context()) d.prose(c);
		for (AgentInfo.Pin pin : a.pins()) d.prose(pinLine(pin));
		if (a.context().isEmpty() && a.pins().isEmpty()) d.note("Nothing pinned.");

		d.section("Always-on tools");
		if (a.defaultTools()) d.prose("Read-only workspace access (covia read/list)");
		for (String t : a.tools()) d.prose(t);
		if (!a.defaultTools() && a.tools().isEmpty()) d.note("None.");
		d.section("Skill libraries");
		list(d, a.skillsets(), "None — the agent cannot discover skills.");
		if (!a.unavailable().isEmpty()) {
			d.section("Unavailable", Styles.ERROR);
			list(d, a.unavailable(), "");
		}
		d.note("Everything else arrives by loading a skill that grants it.");

		d.section("Raw");
		d.excerpt(a.rawJson(), true);
		return d;
	}

	private static void list(Readout d, List<String> items, String whenEmpty) {
		if (items.isEmpty() && !whenEmpty.isEmpty()) d.note(whenEmpty);
		for (String s : items) d.prose(s);
	}

	private static String pinLine(AgentInfo.Pin pin) {
		StringBuilder sb = new StringBuilder(pin.ref()).append("   ·  ").append(pin.kind());
		if (pin.label() != null) sb.append("  ·  ").append(pin.label());
		if (pin.budget() > 0) sb.append(String.format("  ·  %,d bytes", pin.budget()));
		return sb.toString();
	}

	private static String status(AgentInfo.Summary a) {
		String s = orDash(a.status());
		return (a.error() != null) ? s + "  —  " + a.error() : s;
	}

	private static String activity(AgentInfo.Summary a) {
		if (a.conversations() == 0) return "none yet";
		String when = new SimpleDateFormat("d MMM yyyy, HH:mm").format(new Date(a.lastActive()));
		return a.conversations() + "  ·  last active " + when;
	}

	private static String orDash(String s) {
		return (s == null || s.isBlank()) ? "—" : s;
	}
}
