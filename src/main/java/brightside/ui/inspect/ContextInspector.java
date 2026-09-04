package brightside.ui.inspect;

import static brightside.ui.inspect.Blocks.raw;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import brightside.AgentContext;
import brightside.SessionHistory;
import brightside.SkillIndex;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;

/**
 * A read-only view of the exact context an agent sends its model (from
 * {@link AgentContext}). Tabs: <em>Overview</em> (model, budget, counts),
 * <em>Context</em> (every assembled message, by band — head, live surface,
 * conversation, tool loop, tail), <em>Cycle detail</em> (the stored turns),
 * <em>Tools</em> (every definition the model receives, by where it comes
 * from), <em>Skills</em> (every skill the agent can discover, by skillset —
 * the surface behind the model's index), <em>Loaded</em> (the context entries
 * and their accounting) and <em>Raw</em> (the untouched report).
 *
 * <p>Each tab is a {@link Readout}: one document of sections and entries,
 * long content folded behind a "Show all" link, everything shown selectable
 * and copyable. The tabs cross-refer: a tool to the skill that declares it, a
 * skill to what it reveals and the tools it grants, a call to its result, a
 * loaded entry to its skill — each a link that brings the detail into view.
 */
@SuppressWarnings("serial")
public final class ContextInspector extends JPanel {

	private final JTabbedPane tabs = new JTabbedPane();
	private final Readout context = new Readout();
	private final Readout cycle = new Readout();
	private final Readout tools = new Readout();
	private final Readout skills = new Readout();
	private final Readout loads = new Readout();

	/** What a cross-reference can reach: the anchors the other tabs carry. */
	private final Set<String> skillPaths = new HashSet<>();
	private final Set<String> skillsets = new HashSet<>();
	private final Map<String, String> toolByOperation = new HashMap<>();
	private final Set<String> loadRefs = new HashSet<>();

	/**
	 * @param index the skills the agent can discover ({@link SkillIndex}) — the
	 *              surface behind the index the model sees; may be empty
	 */
	public ContextInspector(AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> index) {
		super(new BorderLayout());
		for (SkillIndex.Skill s : index) {
			skillPaths.add(s.path());
			skillsets.add(s.skillset());
		}
		for (AgentContext.Tool t : report.tools()) {
			if (t.operation() != null && t.name() != null) toolByOperation.putIfAbsent(t.operation(), t.name());
		}
		for (AgentContext.Load l : report.loads()) {
			if (l.ref() != null) loadRefs.add(l.ref());
		}
		long shown = index.stream().filter(s -> !s.shadowed()).count();

		tabs.addTab("Overview", Scrolls.vertical(overview(report)));
		tabs.addTab("Context (" + report.messages().size() + ")", Scrolls.vertical(messages(report)));
		tabs.addTab("Cycle detail (" + turns.size() + ")", Scrolls.vertical(cycle(turns)));
		tabs.addTab("Tools (" + report.tools().size() + ")", Scrolls.vertical(tools(report)));
		tabs.addTab("Skills (" + shown + ")", Scrolls.vertical(skills(index, report)));
		tabs.addTab("Loaded (" + report.loads().size() + ")", Scrolls.vertical(loads(report)));
		tabs.addTab("Raw", raw(report.rawJson()));
		add(tabs, BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Navigation: a link shows another tab and brings its detail into view
	// ------------------------------------------------------------------

	/** A link reading {@code text} that shows {@code pane}'s tab and scrolls to the block anchored {@code id} (null: the top). */
	private Readout.Link to(Readout pane, String id, String text) {
		return new Readout.Link(text, () -> {
			tabs.setSelectedComponent(SwingUtilities.getAncestorOfClass(JScrollPane.class, pane));
			if (id != null) SwingUtilities.invokeLater(() -> pane.scrollTo(id));
		});
	}

	private static String skillAnchor(String path) {
		return "skill " + path;
	}

	private static String skillsetAnchor(String dir) {
		return "skillset " + dir;
	}

	private static String toolAnchor(String name) {
		return "tool " + name;
	}

	private static String messageAnchor(int index) {
		return "message " + index;
	}

	private static String loadAnchor(String ref) {
		return "load " + ref;
	}

	/** {@code path} as a link to its skill (or skillset) when the Skills tab lists it, else as text. */
	private Object skillRef(String path) {
		if (skillPaths.contains(path)) return to(skills, skillAnchor(path), path);
		if (skillsets.contains(path)) return to(skills, skillsetAnchor(path), path);
		return path;
	}

	/** {@code operation} as a link to the tool the model has for it, else as text. */
	private Object toolRef(String operation) {
		String name = toolByOperation.get(operation);
		return (name != null) ? to(tools, toolAnchor(name), operation) : operation;
	}

	// ------------------------------------------------------------------
	// Overview
	// ------------------------------------------------------------------

	private Readout overview(AgentContext.Report r) {
		Readout d = new Readout();
		d.pair("Model", r.model().isBlank() ? "—" : r.model());
		int pct = r.budgetPercent();
		d.pair("Context budget", String.format("%,d of %,d bytes  ·  %d%%", r.budgetUsed(), r.budgetBytes(), pct),
			(pct >= 100) ? Styles.WARNING : null);
		d.pair("Session tokens", (r.sessionTokens() != null) ? r.sessionTokens() : "—");
		d.pair("Messages", to(context, null, messagesSummary(r)));
		d.pair("Tools", to(tools, null, toolsSummary(r)));
		d.pair("Loaded entries", to(loads, null, Integer.toString(r.loads().size())));
		d.note("Exactly what the assistant's model receives for this conversation, assembled as for a live "
			+ "reply but not sent. The budget is the model's declared context size — a guide the assembler "
			+ "warns against, not a cap.");
		return d;
	}

	private static String messagesSummary(AgentContext.Report r) {
		StringBuilder sb = new StringBuilder(Integer.toString(r.messages().size()));
		if (r.bands().size() > 1) {
			sb.append("  (");
			for (int i = 0; i < r.bands().size(); i++) {
				AgentContext.Band b = r.bands().get(i);
				if (i > 0) sb.append("  ·  ");
				sb.append(b.name().toLowerCase()).append(' ').append(b.size());
			}
			sb.append(')');
		}
		return sb.toString();
	}

	private static String toolsSummary(AgentContext.Report r) {
		StringBuilder sb = new StringBuilder(Integer.toString(r.tools().size()));
		if (!r.unavailable().isEmpty()) sb.append("  ·  ").append(r.unavailable().size()).append(" unavailable");
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// Context: every message the model sees, by band
	// ------------------------------------------------------------------

	private Readout messages(AgentContext.Report r) {
		Readout d = context;
		if (r.messages().isEmpty()) return d.note("No messages.");
		// A call and its result pair by id: each links to the other.
		Map<String, Integer> resultOf = new HashMap<>();
		Map<String, Integer> callOf = new HashMap<>();
		for (int i = 0; i < r.messages().size(); i++) {
			AgentContext.Message m = r.messages().get(i);
			if ("tool".equals(m.role()) && m.id() != null) resultOf.put(m.id(), i);
			for (AgentContext.Call c : m.calls()) {
				if (c.id() != null) callOf.putIfAbsent(c.id(), i);
			}
		}
		for (AgentContext.Band band : r.bands()) {
			d.section(band.name() + "  ·  " + span(band));
			for (int i = band.from(); i < band.to(); i++) {
				AgentContext.Message m = r.messages().get(i);
				List<Object> meta = new ArrayList<>();
				if (m.name() != null) {
					String text = m.name() + (m.id() != null ? "  (" + shortId(m.id()) + ")" : "");
					Integer call = (m.id() != null) ? callOf.get(m.id()) : null;
					meta.add((call != null) ? to(d, messageAnchor(call), text) : text);
				}
				for (AgentContext.Call c : m.calls()) {
					String text = "→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "");
					Integer result = (c.id() != null) ? resultOf.get(c.id()) : null;
					meta.add((result != null) ? to(d, messageAnchor(result), text) : text);
				}
				meta.add("#" + i);
				d.anchor(messageAnchor(i));
				d.entry(m.role(), m.error() ? Styles.ERROR : null, meta.toArray());

				boolean empty = true;
				if (!m.text().isBlank()) {
					d.excerpt(m.text(), false);
					empty = false;
				}
				for (AgentContext.Call c : m.calls()) {
					if (c.args() != null && !c.args().isBlank()) {
						d.caption("arguments  ·  " + c.name());
						d.excerpt(c.args(), true);
						empty = false;
					}
				}
				if (m.result() != null) {
					d.caption(m.error() ? "error result" : "result");
					d.excerpt(m.result(), true);
					empty = false;
				}
				if (empty) d.caption("(empty)");
			}
			if (r.cacheMarks().contains((long) band.to())) {
				d.note("A cached prefix ends here: the messages above are stable across inferences.");
			}
		}
		return d;
	}

	private static String span(AgentContext.Band b) {
		return (b.size() == 1) ? "message " + b.from()
			: "messages " + b.from() + "–" + (b.to() - 1);
	}

	// ------------------------------------------------------------------
	// Cycle detail: the stored turns
	// ------------------------------------------------------------------

	private Readout cycle(List<SessionHistory.RawTurn> turns) {
		Readout d = cycle;
		if (turns.isEmpty()) return d.note("No turns recorded for this conversation.");
		int i = 0;
		for (SessionHistory.RawTurn t : turns) {
			List<String> meta = new ArrayList<>();
			if (t.meta() != null && !t.meta().isBlank()) meta.add(t.meta());
			for (SessionHistory.RawCall c : t.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
			meta.add("#" + i++);
			d.entry(t.role(), t.error() ? Styles.ERROR : null, meta.toArray());

			boolean empty = true;
			if (t.content() != null && !t.content().isBlank()) {
				d.excerpt(t.content(), false);
				empty = false;
			}
			for (SessionHistory.RawCall c : t.calls()) {
				if (c.args() != null && !c.args().isBlank()) {
					d.caption("arguments  ·  " + c.name());
					d.excerpt(c.args(), true);
					empty = false;
				}
			}
			if (t.toolResult() != null && !t.toolResult().isBlank()) {
				d.caption(t.error() ? "error result" : "result");
				d.excerpt(t.toolResult(), true);
				empty = false;
			}
			if (empty) d.caption("(empty)");
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Tools: every definition the model receives, by where it comes from
	// ------------------------------------------------------------------

	/**
	 * Every tool definition the model receives, grouped by where it comes from
	 * (a skill's tools appear only once the skill is loaded); then any
	 * configured tool that did not resolve.
	 */
	private Readout tools(AgentContext.Report r) {
		Readout d = tools;
		String source = null;
		for (AgentContext.Tool t : r.tools()) {
			String s = (t.source() != null) ? t.source() : "other";
			if (!s.equals(source)) {
				source = s;
				long n = r.tools().stream().filter(u -> s.equals((u.source() != null) ? u.source() : "other")).count();
				d.section(sourceTitle(s) + "  ·  " + n);
			}
			// The section already names the source; the operation and declaring skill are what's new here.
			if (t.name() != null) d.anchor(toolAnchor(t.name()));
			d.entry((t.name() != null) ? t.name() : "(tool)", null, t.operation(),
				(t.skill() != null) ? skillRef(t.skill()) : null);
			if (t.description() == null || t.description().isBlank()) d.caption("(no description)");
			else d.excerpt(t.description(), false);
		}
		if (!r.unavailable().isEmpty()) {
			d.section("Unavailable  ·  " + r.unavailable().size());
			for (String u : r.unavailable()) d.note(u);
		}
		if (r.tools().isEmpty() && r.unavailable().isEmpty()) d.note("No tools offered.");
		return d;
	}

	private static String sourceTitle(String source) {
		return switch (source) {
			case "harness" -> "Harness";
			case "default" -> "Default";
			case "config" -> "Configured";
			case "skill" -> "From loaded skills";
			default -> source;
		};
	}

	// ------------------------------------------------------------------
	// Skills: the discovery surface behind the model's [Skills] index
	// ------------------------------------------------------------------

	private Readout skills(List<SkillIndex.Skill> index, AgentContext.Report r) {
		Readout d = skills;
		if (index.isEmpty()) return d.note("The agent names no skillsets, so there is nothing for it to discover.");
		Map<String, AgentContext.Load> loaded = new HashMap<>();
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) loaded.put(l.ref(), l);
		}
		d.note("Every skill in the agent's skillsets, in the order they are searched, then every skill "
			+ "those would reveal once loaded, grouped by where it lives: the names and descriptions the "
			+ "model's [Skills] index carries. A loaded skill's instructions are in the context and its "
			+ "tools in the palette; the rest load on demand.");
		String skillset = null;
		for (SkillIndex.Skill s : index) {
			if (!s.skillset().equals(skillset)) {
				skillset = s.skillset();
				String set = skillset;
				d.anchor(skillsetAnchor(set));
				d.section(set + "  ·  " + index.stream().filter(k -> set.equals(k.skillset())).count());
			}
			List<Object> meta = new ArrayList<>();
			AgentContext.Load l = loaded.get(s.path());
			if (l != null) {
				String text = "loaded" + (l.status() != null ? "  ·  " + l.status() : "");
				meta.add(loadRefs.contains(s.path()) ? to(loads, loadAnchor(s.path()), text) : text);
			}
			if (s.shadowed()) meta.add("shadowed — an earlier skillset has this name");
			if (!s.tools().isEmpty()) meta.add(s.tools().size() + (s.tools().size() == 1 ? " tool" : " tools"));
			if (!s.children().isEmpty()) meta.add("reveals " + s.children().size() + " more");
			meta.add(s.path());
			d.anchor(skillAnchor(s.path()));
			d.entry(s.name(), s.shadowed() ? Styles.MUTED : null, meta.toArray());

			d.excerpt((s.description() != null) ? s.description() : "(no description)", false);
			if (!s.tools().isEmpty()) {
				d.caption("tools it grants");
				d.lines(true, s.tools().stream().map(this::toolRef).toList());
			}
			if (!s.children().isEmpty()) {
				d.caption("what it reveals");
				d.lines(true, s.children().stream().map(this::skillRef).toList());
			}
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Loaded entries
	// ------------------------------------------------------------------

	private Readout loads(AgentContext.Report r) {
		Readout d = loads;
		if (r.loads().isEmpty()) return d.note("Nothing loaded.");
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) d.anchor(loadAnchor(l.ref()));
			d.entry((l.ref() != null) ? l.ref() : "(entry)", null, l.kind(), l.status(),
				(l.ref() != null && skillPaths.contains(l.ref())) ? to(skills, skillAnchor(l.ref()), "the skill") : null);
			StringBuilder accounting = new StringBuilder(String.format("%,d bytes", l.bytes()));
			if (l.budget() > 0) accounting.append(String.format("  of a %,d-byte budget", l.budget()));
			if (l.truncated()) accounting.append("  ·  truncated");
			if (l.deduplicated()) accounting.append("  ·  deduplicated");
			d.prose(accounting.toString());
		}
		return d;
	}

	private static String shortId(String id) {
		return (id.length() > 12) ? id.substring(0, 12) + "…" : id;
	}
}
