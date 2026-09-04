package brightside.ui.inspect;

import static brightside.ui.inspect.Blocks.raw;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

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
 * and copyable.
 */
@SuppressWarnings("serial")
public final class ContextInspector extends JPanel {

	/**
	 * @param skills the skills the agent can discover ({@link SkillIndex}) — the
	 *               surface behind the index the model sees; may be empty
	 */
	public ContextInspector(AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> skills) {
		super(new BorderLayout());
		long shown = skills.stream().filter(s -> !s.shadowed()).count();

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Overview", Scrolls.vertical(overview(report)));
		tabs.addTab("Context (" + report.messages().size() + ")", Scrolls.vertical(messages(report)));
		tabs.addTab("Cycle detail (" + turns.size() + ")", Scrolls.vertical(cycle(turns)));
		tabs.addTab("Tools (" + report.tools().size() + ")", Scrolls.vertical(tools(report)));
		tabs.addTab("Skills (" + shown + ")", Scrolls.vertical(skills(skills, report)));
		tabs.addTab("Loaded (" + report.loads().size() + ")", Scrolls.vertical(loads(report)));
		tabs.addTab("Raw", raw(report.rawJson()));
		add(tabs, BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Overview
	// ------------------------------------------------------------------

	private static Readout overview(AgentContext.Report r) {
		Readout d = new Readout();
		d.pair("Model", r.model().isBlank() ? "—" : r.model());
		int pct = r.budgetPercent();
		d.pair("Context budget", String.format("%,d of %,d bytes  ·  %d%%", r.budgetUsed(), r.budgetBytes(), pct),
			(pct >= 100) ? Styles.WARNING : null);
		d.pair("Session tokens", (r.sessionTokens() != null) ? r.sessionTokens() : "—");
		d.pair("Messages", messagesSummary(r));
		d.pair("Tools", toolsSummary(r));
		d.pair("Loaded entries", Integer.toString(r.loads().size()));
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

	private static Readout messages(AgentContext.Report r) {
		Readout d = new Readout();
		if (r.messages().isEmpty()) return d.note("No messages.");
		for (AgentContext.Band band : r.bands()) {
			d.section(band.name() + "  ·  " + span(band));
			for (int i = band.from(); i < band.to(); i++) {
				AgentContext.Message m = r.messages().get(i);
				List<String> meta = new ArrayList<>();
				if (m.name() != null) meta.add(m.name() + (m.id() != null ? "  (" + shortId(m.id()) + ")" : ""));
				for (AgentContext.Call c : m.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
				meta.add("#" + i);
				d.entry(m.role(), m.error() ? Styles.ERROR : null, meta.toArray(String[]::new));

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

	private static Readout cycle(List<SessionHistory.RawTurn> turns) {
		Readout d = new Readout();
		if (turns.isEmpty()) return d.note("No turns recorded for this conversation.");
		int i = 0;
		for (SessionHistory.RawTurn t : turns) {
			List<String> meta = new ArrayList<>();
			if (t.meta() != null && !t.meta().isBlank()) meta.add(t.meta());
			for (SessionHistory.RawCall c : t.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
			meta.add("#" + i++);
			d.entry(t.role(), t.error() ? Styles.ERROR : null, meta.toArray(String[]::new));

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
	private static Readout tools(AgentContext.Report r) {
		Readout d = new Readout();
		String source = null;
		for (AgentContext.Tool t : r.tools()) {
			String s = (t.source() != null) ? t.source() : "other";
			if (!s.equals(source)) {
				source = s;
				long n = r.tools().stream().filter(u -> s.equals((u.source() != null) ? u.source() : "other")).count();
				d.section(sourceTitle(s) + "  ·  " + n);
			}
			// The section already names the source; the operation and declaring skill are what's new here.
			d.entry((t.name() != null) ? t.name() : "(tool)", null, t.operation(), t.skill());
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

	private static Readout skills(List<SkillIndex.Skill> skills, AgentContext.Report r) {
		Readout d = new Readout();
		if (skills.isEmpty()) return d.note("The agent names no skillsets, so there is nothing for it to discover.");
		Map<String, AgentContext.Load> loaded = new HashMap<>();
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) loaded.put(l.ref(), l);
		}
		d.note("Every skill in the agent's skillsets, in the order they are searched, then every skill "
			+ "those would reveal once loaded, grouped by where it lives: the names and descriptions the "
			+ "model's [Skills] index carries. A loaded skill's instructions are in the context and its "
			+ "tools in the palette; the rest load on demand.");
		String skillset = null;
		for (SkillIndex.Skill s : skills) {
			if (!s.skillset().equals(skillset)) {
				skillset = s.skillset();
				String set = skillset;
				d.section(set + "  ·  " + skills.stream().filter(k -> set.equals(k.skillset())).count());
			}
			List<String> meta = new ArrayList<>();
			AgentContext.Load l = loaded.get(s.path());
			if (l != null) meta.add("loaded" + (l.status() != null ? "  ·  " + l.status() : ""));
			if (s.shadowed()) meta.add("shadowed — an earlier skillset has this name");
			if (!s.tools().isEmpty()) meta.add(s.tools().size() + (s.tools().size() == 1 ? " tool" : " tools"));
			if (!s.children().isEmpty()) meta.add("reveals " + s.children().size() + " more");
			meta.add(s.path());
			d.entry(s.name(), s.shadowed() ? Styles.MUTED : null, meta.toArray(String[]::new));

			d.excerpt((s.description() != null) ? s.description() : "(no description)", false);
			if (!s.tools().isEmpty()) {
				d.caption("tools it grants");
				d.excerpt(String.join("\n", s.tools()), true);
			}
			if (!s.children().isEmpty()) {
				d.caption("what it reveals");
				d.excerpt(String.join("\n", s.children()), true);
			}
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Loaded entries
	// ------------------------------------------------------------------

	private static Readout loads(AgentContext.Report r) {
		Readout d = new Readout();
		if (r.loads().isEmpty()) return d.note("Nothing loaded.");
		for (AgentContext.Load l : r.loads()) {
			d.entry((l.ref() != null) ? l.ref() : "(entry)", null, l.kind(), l.status());
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
