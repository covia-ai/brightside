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
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import covia.brightside.AgentContext;
import covia.brightside.SessionHistory;

/**
 * A comprehensive, read-only view of the exact context an agent sends its model
 * (from {@link AgentContext}). Tabs: <em>Overview</em> (model, budget, counts),
 * <em>Context</em> (every assembled message — identity, the skills index, pinned
 * memory, loaded skill bodies, the conversation), <em>Tools</em> (the offered
 * palette with provenance), <em>Skills</em> (the loaded entries and their
 * accounting), and <em>Raw</em> (the untouched report).
 *
 * <p>Its own package so it can grow into a fuller inspector without crowding the
 * chat components. Everything shown is selectable and copyable.
 */
@SuppressWarnings("serial")
public final class ContextInspector extends JPanel {

	public ContextInspector(AgentContext.Report report, List<SessionHistory.RawTurn> turns) {
		super(new BorderLayout());
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Overview", overview(report));
		tabs.addTab("Context (" + report.messages().size() + ")", scroll(messages(report)));
		tabs.addTab("Cycle detail (" + turns.size() + ")", scroll(cycle(turns)));
		tabs.addTab("Tools (" + report.tools().size() + ")", scroll(tools(report)));
		tabs.addTab("Skills (" + report.loads().size() + ")", scroll(loads(report)));
		tabs.addTab("Raw", raw(report.rawJson()));
		add(tabs, BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Tabs
	// ------------------------------------------------------------------

	private static JComponent overview(AgentContext.Report r) {
		JPanel p = column();
		p.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
		p.add(kv("Model", r.model().isBlank() ? "—" : r.model()));
		p.add(kv("Budget", String.format("%,d of %,d bytes used  ·  %,d free",
			r.budgetUsed(), r.budgetBytes(), r.budgetRemaining())));
		p.add(kv("Session tokens", (r.sessionTokens() != null) ? r.sessionTokens() : "—"));
		p.add(kv("Messages", Integer.toString(r.messages().size())));
		p.add(kv("Tools offered", r.tools().size() + (r.unavailable().isEmpty()
			? "" : "  (" + r.unavailable().size() + " unavailable)")));
		p.add(kv("Loaded entries", Integer.toString(r.loads().size())));
		JLabel note = small("This is exactly what the assistant's model receives for this conversation — "
			+ "assembled the same way as a live reply, but without sending it.");
		note.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		p.add(note);
		return p;
	}

	/**
	 * Every message as the model sees it. A tool exchange is an assistant
	 * message whose only content is its calls, then a tool message whose result
	 * is structured — both rendered, so loaded context, job results and tool
	 * results are visible rather than two blank headings.
	 */
	private static JComponent messages(AgentContext.Report r) {
		JPanel p = column();
		for (AgentContext.Message m : r.messages()) {
			String head = m.role();
			if (m.name() != null) head += "   ·  " + m.name() + (m.id() != null ? "  (" + shortId(m.id()) + ")" : "");
			JLabel h = heading(head);
			if (m.error()) h.setForeground(errorColor());
			p.add(h);
			if (!m.text().isBlank()) p.add(body(m.text(), false));
			for (AgentContext.Call c : m.calls()) {
				p.add(small("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "")));
				if (c.args() != null && !c.args().isBlank()) p.add(body(c.args(), true));
			}
			if (m.result() != null) {
				p.add(small(m.error() ? "error result" : "result"));
				p.add(body(m.result(), true));
			}
			p.add(divider());
		}
		if (r.messages().isEmpty()) p.add(small("No messages."));
		return p;
	}

	private static JComponent cycle(List<SessionHistory.RawTurn> turns) {
		JPanel p = column();
		if (turns.isEmpty()) {
			p.add(small("No turns recorded for this conversation."));
			return p;
		}
		for (SessionHistory.RawTurn t : turns) {
			String head = t.role();
			if (t.meta() != null && !t.meta().isBlank()) head += "   ·  " + t.meta();
			JLabel h = heading(head);
			if (t.error()) h.setForeground(errorColor());
			p.add(h);
			if (t.content() != null && !t.content().isBlank()) p.add(body(t.content(), false));
			for (SessionHistory.RawCall c : t.calls()) {
				p.add(small("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "")));
				if (c.args() != null && !c.args().isBlank()) p.add(body(c.args(), true));
			}
			if (t.toolResult() != null && !t.toolResult().isBlank()) {
				p.add(small(t.error() ? "error result" : "result"));
				p.add(body(t.toolResult(), true));
			}
			p.add(divider());
		}
		return p;
	}

	private static String shortId(String id) {
		return (id.length() > 12) ? id.substring(0, 12) + "…" : id;
	}

	private static JComponent tools(AgentContext.Report r) {
		JPanel p = column();
		for (AgentContext.Tool t : r.tools()) {
			String title = (t.name() != null) ? t.name() : "(tool)";
			if (t.source() != null) title += "   · " + t.source();
			p.add(heading(title));
			if (t.description() != null && !t.description().isBlank()) p.add(body(t.description(), false));
			p.add(divider());
		}
		if (!r.unavailable().isEmpty()) {
			p.add(heading("Unavailable"));
			p.add(body(String.join("\n", r.unavailable()), false));
		}
		if (r.tools().isEmpty() && r.unavailable().isEmpty()) p.add(small("No tools offered."));
		return p;
	}

	private static JComponent loads(AgentContext.Report r) {
		JPanel p = column();
		for (AgentContext.Load l : r.loads()) {
			p.add(heading((l.ref() != null) ? l.ref() : "(entry)"));
			StringBuilder meta = new StringBuilder();
			if (l.kind() != null) meta.append(l.kind());
			if (l.status() != null) meta.append(meta.length() > 0 ? "  ·  " : "").append(l.status());
			meta.append(meta.length() > 0 ? "  ·  " : "").append(String.format("%,d / %,d bytes", l.bytes(), l.budget()));
			if (l.truncated()) meta.append("  ·  truncated");
			if (l.deduplicated()) meta.append("  ·  deduplicated");
			p.add(small(meta.toString()));
			p.add(divider());
		}
		if (r.loads().isEmpty()) p.add(small("Nothing loaded."));
		return p;
	}

}
