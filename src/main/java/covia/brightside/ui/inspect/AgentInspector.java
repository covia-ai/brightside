package covia.brightside.ui.inspect;

import static covia.brightside.ui.inspect.Blocks.body;
import static covia.brightside.ui.inspect.Blocks.column;
import static covia.brightside.ui.inspect.Blocks.divider;
import static covia.brightside.ui.inspect.Blocks.errorColor;
import static covia.brightside.ui.inspect.Blocks.heading;
import static covia.brightside.ui.inspect.Blocks.kv;
import static covia.brightside.ui.inspect.Blocks.raw;
import static covia.brightside.ui.inspect.Blocks.scroll;
import static covia.brightside.ui.inspect.Blocks.small;

import java.awt.BorderLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import covia.brightside.AgentInfo;

/**
 * A read-only view of one agent (from {@link AgentInfo}). Tabs: <em>Overview</em>
 * (identity, status, model, activity), <em>Instructions</em> (the system prompt
 * and what is pinned into every turn), <em>Capabilities</em> (always-on tools,
 * skill libraries, pinned loads, anything unavailable) and <em>Raw</em> (the
 * venue's {@code agent:info} summary). Everything shown is selectable and copyable.
 */
@SuppressWarnings("serial")
public final class AgentInspector extends JPanel {

	public AgentInspector(AgentInfo.Summary a) {
		super(new BorderLayout());
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Overview", scroll(overview(a)));
		tabs.addTab("Instructions", scroll(instructions(a)));
		tabs.addTab("Capabilities", scroll(capabilities(a)));
		tabs.addTab("Raw", raw(a.rawJson()));
		add(tabs, BorderLayout.CENTER);
	}

	private static JComponent overview(AgentInfo.Summary a) {
		JPanel p = column();
		p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
		p.add(kv("Name", a.name()));
		p.add(kv("Id", a.id()));
		p.add(kv("Agent DID", a.did()));
		p.add(kv("Owner", a.ownerName() + "  ·  " + a.ownerDID()));
		p.add(kv("Status", status(a)));
		p.add(kv("Model", orDash(a.model())));
		p.add(kv("Operation", orDash(a.operation())));
		p.add(kv("Conversations", activity(a)));
		p.add(kv("Tasks", Long.toString(a.tasks())));
		p.add(kv("Timeline", a.timelineLength() + " event" + (a.timelineLength() == 1 ? "" : "s")));
		JLabel note = small(a.standard()
			? "Brightside's standard agent: its model and instructions follow Settings and config.json."
			: "Created from the agents pane; it keeps the model and instructions it was given.");
		note.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		p.add(note);
		return p;
	}

	private static JComponent instructions(AgentInfo.Summary a) {
		JPanel p = column();
		p.add(heading("System prompt"));
		p.add(body(orDash(a.systemPrompt()), false));
		p.add(divider());
		p.add(heading("In every turn"));
		for (String c : a.context()) p.add(body(c, false));
		for (AgentInfo.Pin pin : a.pins()) p.add(body(pinLine(pin), false));
		if (a.context().isEmpty() && a.pins().isEmpty()) p.add(small("Nothing pinned."));
		return p;
	}

	private static JComponent capabilities(AgentInfo.Summary a) {
		JPanel p = column();
		p.add(heading("Always-on tools"));
		if (a.defaultTools()) p.add(body("Read-only workspace access (covia read/list)", false));
		for (String t : a.tools()) p.add(body(t, false));
		if (!a.defaultTools() && a.tools().isEmpty()) p.add(small("None."));
		p.add(divider());
		p.add(heading("Skill libraries"));
		list(p, a.skillsets(), "None — the agent cannot discover skills.");
		p.add(divider());
		p.add(heading("Pinned loads"));
		if (a.pins().isEmpty()) p.add(small("None."));
		for (AgentInfo.Pin pin : a.pins()) p.add(body(pinLine(pin), false));
		if (!a.unavailable().isEmpty()) {
			p.add(divider());
			JLabel h = heading("Unavailable");
			h.setForeground(errorColor());
			p.add(h);
			list(p, a.unavailable(), "");
		}
		JLabel note = small("Everything else arrives by loading a skill that grants it.");
		note.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		p.add(note);
		return p;
	}

	private static void list(JPanel p, List<String> items, String whenEmpty) {
		if (items.isEmpty() && !whenEmpty.isEmpty()) p.add(small(whenEmpty));
		for (String s : items) p.add(body(s, false));
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
