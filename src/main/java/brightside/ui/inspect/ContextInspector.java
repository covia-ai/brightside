package brightside.ui.inspect;

import static brightside.ui.inspect.Blocks.column;
import static brightside.ui.inspect.Blocks.kv;
import static brightside.ui.inspect.Blocks.raw;

import java.awt.BorderLayout;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import brightside.AgentContext;
import brightside.SessionHistory;
import brightside.SkillIndex;
import brightside.ui.components.EntryList;
import brightside.ui.components.Excerpt;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;

/**
 * A read-only view of the exact context an agent sends its model (from
 * {@link AgentContext}). Tabs: <em>Overview</em> (model, budget, counts),
 * <em>Context</em> (every assembled message, by band — head, live surface,
 * conversation, tool loop, tail), <em>Cycle detail</em> (the stored turns),
 * <em>Tools</em> (every definition the model receives: callable now by
 * source, then the gates skills declare), <em>Skills</em> (every skill the
 * agent can discover, by skillset — the surface behind the model's index),
 * <em>Loaded</em> (the context entries and their accounting) and <em>Raw</em>
 * (the untouched report).
 *
 * <p>Each list is an {@link EntryList} — a summary beside its content — with
 * long content clamped in an {@link Excerpt}. Everything shown is selectable
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

		List<JScrollPane> panes = new ArrayList<>();
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Overview", overview(report));
		tabs.addTab("Context (" + report.messages().size() + ")", scrolling(messages(report), panes));
		tabs.addTab("Cycle detail (" + turns.size() + ")", scrolling(cycle(turns), panes));
		tabs.addTab("Tools (" + report.tools().size() + ")", scrolling(tools(report), panes));
		tabs.addTab("Skills (" + shown + ")", scrolling(skills(skills, report), panes));
		tabs.addTab("Loaded (" + report.loads().size() + ")", scrolling(loads(report), panes));
		tabs.addTab("Raw", raw(report.rawJson()));
		add(tabs, BorderLayout.CENTER);

		// Every list opens at its top, whatever asked to be scrolled into view
		// while the window was appearing.
		SwingUtilities.invokeLater(() -> {
			for (JScrollPane pane : panes) pane.getViewport().setViewPosition(new Point(0, 0));
		});
	}

	private static JScrollPane scrolling(JComponent content, List<JScrollPane> panes) {
		JScrollPane pane = Scrolls.vertical(content);
		panes.add(pane);
		return pane;
	}

	// ------------------------------------------------------------------
	// Overview
	// ------------------------------------------------------------------

	private static JComponent overview(AgentContext.Report r) {
		JPanel p = column();
		p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
		p.add(kv("Model", r.model().isBlank() ? "—" : r.model()));

		int pct = r.budgetPercent();
		SelectableText budget = new SelectableText(String.format("%,d of %,d bytes  ·  %d%%",
			r.budgetUsed(), r.budgetBytes(), pct));
		if (pct >= 100) budget.tone(Styles.WARNING);
		p.add(Panels.keyValue("Context budget", budget));

		p.add(kv("Session tokens", (r.sessionTokens() != null) ? r.sessionTokens() : "—"));
		p.add(kv("Messages", messagesSummary(r)));
		p.add(kv("Tools", toolsSummary(r)));
		p.add(kv("Loaded entries", Integer.toString(r.loads().size())));

		SelectableText note = SelectableText.description("Exactly what the assistant's model receives for this "
			+ "conversation, assembled as for a live reply but not sent. The budget is the model's declared "
			+ "context size — a guide the assembler warns against, not a cap.").small();
		note.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		p.add(note);
		return p;
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

	private static JComponent messages(AgentContext.Report r) {
		EntryList list = entries();
		if (r.messages().isEmpty()) {
			list.note("No messages.");
			return list;
		}
		for (AgentContext.Band band : r.bands()) {
			list.section(band.name() + "  ·  " + span(band));
			for (int i = band.from(); i < band.to(); i++) {
				AgentContext.Message m = r.messages().get(i);
				List<String> meta = new ArrayList<>();
				if (m.name() != null) meta.add(m.name() + (m.id() != null ? "  (" + shortId(m.id()) + ")" : ""));
				for (AgentContext.Call c : m.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
				meta.add("#" + i);
				JComponent summary = EntryList.summary(m.role(), m.error() ? Styles.ERROR : null,
					meta.toArray(String[]::new));

				JPanel content = Panels.column();
				if (!m.text().isBlank()) content.add(excerpt(m.text(), false, list));
				for (AgentContext.Call c : m.calls()) {
					if (c.args() != null && !c.args().isBlank()) {
						content.add(caption("arguments  ·  " + c.name()));
						content.add(excerpt(c.args(), true, list));
					}
				}
				if (m.result() != null) {
					content.add(caption(m.error() ? "error result" : "result"));
					content.add(excerpt(m.result(), true, list));
				}
				if (content.getComponentCount() == 0) content.add(Labels.small("(empty)"));
				list.entry(summary, content);
			}
			if (r.cacheMarks().contains((long) band.to())) {
				list.note("A cached prefix ends here: the messages above are stable across inferences.");
			}
		}
		return list;
	}

	private static String span(AgentContext.Band b) {
		return (b.size() == 1) ? "message " + b.from()
			: "messages " + b.from() + "–" + (b.to() - 1);
	}

	// ------------------------------------------------------------------
	// Cycle detail: the stored turns
	// ------------------------------------------------------------------

	private static JComponent cycle(List<SessionHistory.RawTurn> turns) {
		EntryList list = entries();
		if (turns.isEmpty()) {
			list.note("No turns recorded for this conversation.");
			return list;
		}
		int i = 0;
		for (SessionHistory.RawTurn t : turns) {
			List<String> meta = new ArrayList<>();
			if (t.meta() != null && !t.meta().isBlank()) meta.add(t.meta());
			for (SessionHistory.RawCall c : t.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
			meta.add("#" + i++);
			JComponent summary = EntryList.summary(t.role(), t.error() ? Styles.ERROR : null,
				meta.toArray(String[]::new));

			JPanel content = Panels.column();
			if (t.content() != null && !t.content().isBlank()) content.add(excerpt(t.content(), false, list));
			for (SessionHistory.RawCall c : t.calls()) {
				if (c.args() != null && !c.args().isBlank()) {
					content.add(caption("arguments  ·  " + c.name()));
					content.add(excerpt(c.args(), true, list));
				}
			}
			if (t.toolResult() != null && !t.toolResult().isBlank()) {
				content.add(caption(t.error() ? "error result" : "result"));
				content.add(excerpt(t.toolResult(), true, list));
			}
			if (content.getComponentCount() == 0) content.add(Labels.small("(empty)"));
			list.entry(summary, content);
		}
		return list;
	}

	// ------------------------------------------------------------------
	// Tools: every definition the model receives, by where it comes from
	// ------------------------------------------------------------------

	/**
	 * Every tool definition the model receives, grouped by where it comes from
	 * (a skill's tools appear only once the skill is loaded); then any
	 * configured tool that did not resolve.
	 */
	private static JComponent tools(AgentContext.Report r) {
		EntryList list = entries();
		String source = null;
		for (AgentContext.Tool t : r.tools()) {
			String s = (t.source() != null) ? t.source() : "other";
			if (!s.equals(source)) {
				source = s;
				list.section(sourceTitle(s) + "  ·  " + r.tools().stream().filter(u -> s.equals(u.source() != null ? u.source() : "other")).count());
			}
			list.entry(toolSummary(t), toolDescription(t.description(), list));
		}
		if (!r.unavailable().isEmpty()) {
			list.section("Unavailable  ·  " + r.unavailable().size());
			for (String u : r.unavailable()) list.note(u);
		}
		if (r.tools().isEmpty() && r.unavailable().isEmpty()) list.note("No tools offered.");
		return list;
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

	private static JComponent skills(List<SkillIndex.Skill> skills, AgentContext.Report r) {
		EntryList list = entries();
		if (skills.isEmpty()) {
			list.note("The agent names no skillsets, so there is nothing for it to discover.");
			return list;
		}
		Map<String, AgentContext.Load> loaded = new HashMap<>();
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) loaded.put(l.ref(), l);
		}
		list.note("Every skill in the agent's skillsets, in the order they are searched, then every skill "
			+ "those would reveal once loaded, grouped by where it lives: the names and descriptions the "
			+ "model's [Skills] index carries. A loaded skill's instructions are in the context and its "
			+ "tools in the palette; the rest load on demand.");
		String skillset = null;
		for (SkillIndex.Skill s : skills) {
			if (!s.skillset().equals(skillset)) {
				skillset = s.skillset();
				String set = skillset;
				list.section(set + "  ·  " + skills.stream().filter(k -> set.equals(k.skillset())).count());
			}
			List<String> meta = new ArrayList<>();
			AgentContext.Load l = loaded.get(s.path());
			if (l != null) meta.add("loaded" + (l.status() != null ? "  ·  " + l.status() : ""));
			if (s.shadowed()) meta.add("shadowed — an earlier skillset has this name");
			if (!s.tools().isEmpty()) meta.add(s.tools().size() + (s.tools().size() == 1 ? " tool" : " tools"));
			if (!s.children().isEmpty()) meta.add("reveals " + s.children().size() + " more");
			meta.add(s.path());

			JPanel content = Panels.column();
			content.add(excerpt((s.description() != null) ? s.description() : "(no description)", false, list));
			if (!s.tools().isEmpty()) {
				content.add(caption("tools it grants"));
				content.add(excerpt(String.join("\n", s.tools()), true, list));
			}
			if (!s.children().isEmpty()) {
				content.add(caption("what it reveals"));
				content.add(excerpt(String.join("\n", s.children()), true, list));
			}
			list.entry(EntryList.summary(s.name(), s.shadowed() ? Styles.MUTED : null, meta.toArray(String[]::new)),
				content);
		}
		return list;
	}

	private static JComponent toolSummary(AgentContext.Tool t) {
		List<String> meta = new ArrayList<>();
		// The section already names the source; the operation and declaring skill are what's new here.
		if (t.operation() != null) meta.add(t.operation());
		if (t.skill() != null) meta.add(t.skill());
		return EntryList.summary((t.name() != null) ? t.name() : "(tool)", null, meta.toArray(String[]::new));
	}

	private static JComponent toolDescription(String description, EntryList list) {
		String text = (description != null) ? description : "";
		return text.isBlank() ? Labels.small("(no description)") : excerpt(text, false, list);
	}

	// ------------------------------------------------------------------
	// Loaded entries
	// ------------------------------------------------------------------

	private static JComponent loads(AgentContext.Report r) {
		EntryList list = entries();
		if (r.loads().isEmpty()) {
			list.note("Nothing loaded.");
			return list;
		}
		for (AgentContext.Load l : r.loads()) {
			List<String> meta = new ArrayList<>();
			if (l.kind() != null) meta.add(l.kind());
			if (l.status() != null) meta.add(l.status());
			JComponent summary = EntryList.summary((l.ref() != null) ? l.ref() : "(entry)", null,
				meta.toArray(String[]::new));
			StringBuilder accounting = new StringBuilder(String.format("%,d bytes", l.bytes()));
			if (l.budget() > 0) accounting.append(String.format("  of a %,d-byte budget", l.budget()));
			if (l.truncated()) accounting.append("  ·  truncated");
			if (l.deduplicated()) accounting.append("  ·  deduplicated");
			list.entry(summary, new SelectableText(accounting.toString()));
		}
		return list;
	}

	// ------------------------------------------------------------------
	// Pieces
	// ------------------------------------------------------------------

	private static EntryList entries() {
		EntryList list = new EntryList();
		list.setBorder(BorderFactory.createEmptyBorder(6, 14, 16, 14));
		return list;
	}

	/** Content clamped to a few lines; showing all of it re-lays the list out. */
	private static Excerpt excerpt(String text, boolean mono, EntryList list) {
		return new Excerpt(text, mono).onToggle(list::revalidate);
	}

	private static JLabel caption(String text) {
		JLabel l = Labels.caption(text);
		l.setBorder(BorderFactory.createEmptyBorder(6, 0, 1, 0));
		return l;
	}

	private static String shortId(String id) {
		return (id.length() > 12) ? id.substring(0, 12) + "…" : id;
	}
}
