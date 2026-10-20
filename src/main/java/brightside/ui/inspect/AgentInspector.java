package brightside.ui.inspect;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import java.awt.BorderLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;

import brightside.AgentInfo;
import brightside.ui.components.Badge;
import brightside.ui.components.Borders;
import brightside.ui.components.Chip;
import brightside.ui.components.Labels;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Tone;
import net.miginfocom.swing.MigLayout;

/**
 * A read-only view of one agent (from {@link AgentInfo}): a fixed header with
 * its name, run state, kind and activity, above one {@link Readout} document:
 * identity, model and queue; the system
 * prompt and what is pinned into every turn; the always-on tools, skill
 * libraries and anything unavailable; and, folded at the end, the venue's raw
 * {@code agent:info} summary. Everything shown is selectable and copyable.
 * {@link SessionViews} shows it as the Agent tab above the chat.
 */
public final class AgentInspector {

	private AgentInspector() {
	}

	/** The agent: who and how it is in a fixed header, its document scrolling below. */
	public static JComponent view(AgentInfo.Summary a) {
		JPanel view = new JPanel(new BorderLayout());
		view.setOpaque(false);
		view.add(header(a), BorderLayout.NORTH);
		view.add(Scrolls.vertical(document(a)), BorderLayout.CENTER);
		return view;
	}

	/**
	 * The agent at a glance: its name, its run state (in a tone, with its last
	 * error on hover), whether it is Brightside's standard agent or one of the
	 * owner's own, and how much it has been used.
	 */
	static JComponent header(AgentInfo.Summary a) {
		JPanel h = new JPanel(new MigLayout("insets 18 24 14 24, fillx, gapy 2", "[]12[grow,fill]", ""));
		h.setOpaque(false);
		h.setBorder(Borders.hairline(false, false, true, false));
		h.add(new Badge("bot", Tone.ACCENT, 38), "spany 2, aligny center");
		JPanel title = new JPanel(new MigLayout("insets 0, gap 10", "", ""));
		title.setOpaque(false);
		title.add(Labels.section(a.name()), "wmin 0");
		StatusLabel state = state(a);
		state.setToolTipText(a.error());
		title.add(state);
		Chip kind = new Chip(a.standard() ? "Standard" : "Custom");
		kind.setToolTipText(a.standard()
			? "Brightside's standard agent: its model and instructions follow Settings and config.json."
			: "Created from the agents pane; it keeps the model and instructions it was given.");
		title.add(kind);
		h.add(title, "wmin 0, wrap");
		h.add(Labels.small(activity(a)), "wmin 0");
		return h;
	}

	private static StatusLabel state(AgentInfo.Summary a) {
		String words = AgentInfo.state(a.status());
		if (a.error() != null) return new StatusLabel(words, "circle-alert", Tone.ERROR);
		return switch (a.status() == null ? "" : a.status()) {
			case "RUNNING" -> new StatusLabel(words, "loader-circle", Tone.ACCENT);
			case "SLEEPING" -> new StatusLabel(words, "moon", Tone.MUTED);
			case "SUSPENDED" -> new StatusLabel(words, "circle-pause", Tone.WARNING);
			case "TERMINATED" -> new StatusLabel(words, "circle-x", Tone.MUTED);
			default -> new StatusLabel(words, "info", Tone.MUTED);
		};
	}

	static Readout document(AgentInfo.Summary a) {
		Readout d = new Readout();
		if (a.error() != null) {
			d.pair("Last error", a.error(), Styles.ERROR).tip("What stopped the agent, as the venue reports it.");
		}
		d.pair("Id", a.id()).tip("The name the venue knows the agent by. Its record lives under it in the owner's namespace.");
		d.pair("Agent DID", a.did()).tip("The agent's own identifier, distinct from its owner's: what it acts and signs as.");
		d.pair("Owner", a.ownerName() + "  ·  " + a.ownerDID()).tip("The user the agent belongs to and acts for.");
		d.pair("Model", orDash(a.model())).tip("The model operation its replies run through.");
		d.pair("Operation", orDash(a.operation())).tip("The agent operation Brightside invokes to chat with it.");
		d.pair("Tasks", Long.toString(a.tasks())).tip("Tasks on the agent's queue.");
		d.pair("Timeline", a.timelineLength() + " event" + (a.timelineLength() == 1 ? "" : "s"))
			.tip("Events the venue has recorded on the agent's timeline.");

		d.section("System prompt").tip("The instructions that open every turn: the agent's identity and role.");
		d.prose(orDash(a.systemPrompt()));
		d.section("In every turn").tip("Context pinned by configuration: read afresh each turn and watched for change, "
			+ "so the agent always has it without loading anything.");
		for (String c : a.context()) d.prose(c);
		for (AgentInfo.Pin pin : a.pins()) d.prose(pinLine(pin)).tip("A pinned load: what it refers to, its kind, "
			+ "its label in the context, and the most of it that is placed there.");
		if (a.context().isEmpty() && a.pins().isEmpty()) d.note("Nothing pinned.");

		d.section("Always-on tools").tip("Tools the agent has without loading anything.");
		if (a.defaultTools()) d.prose("Read-only workspace access (covia read/list/inspect)");
		for (String t : a.tools()) d.prose(t);
		if (!a.defaultTools() && a.tools().isEmpty()) d.note("None.");
		d.section("Skill libraries").tip("The skillsets the agent searches for skills, in order; the first skill "
			+ "of a name wins.");
		list(d, a.skillsets(), "None — the agent cannot discover skills.");
		if (!a.unavailable().isEmpty()) {
			d.section("Unavailable", Styles.ERROR).tip("Configured entries that did not resolve.");
			list(d, a.unavailable(), "");
		}
		d.note("Everything else arrives by loading a skill that grants it.");

		d.section("Raw").tip("The venue's own summary of the agent, as JSON.");
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

	private static String activity(AgentInfo.Summary a) {
		if (a.conversations() == 0) return "No conversations yet";
		String when = new SimpleDateFormat("d MMM yyyy, HH:mm").format(new Date(a.lastActive()));
		return a.conversations() + (a.conversations() == 1 ? " conversation" : " conversations") + "  ·  last active "
			+ when;
	}

	private static String orDash(String s) {
		return (s == null || s.isBlank()) ? "—" : s;
	}
}
