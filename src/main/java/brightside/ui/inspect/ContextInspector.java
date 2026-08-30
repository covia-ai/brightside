package brightside.ui.inspect;

import static brightside.ui.inspect.Blocks.body;
import static brightside.ui.inspect.Blocks.column;
import static brightside.ui.inspect.Blocks.heading;
import static brightside.ui.inspect.Blocks.kv;
import static brightside.ui.inspect.Blocks.raw;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import brightside.AgentContext;
import brightside.SessionHistory;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;

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
		tabs.addTab("Context (" + report.messages().size() + ")", Scrolls.vertical(messages(report)));
		tabs.addTab("Cycle detail (" + turns.size() + ")", Scrolls.vertical(cycle(turns)));
		tabs.addTab("Tools (" + report.tools().size() + ")", Scrolls.vertical(tools(report)));
		tabs.addTab("Skills (" + report.loads().size() + ")", Scrolls.vertical(loads(report)));
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
		JLabel note = Labels.small("This is exactly what the assistant's model receives for this conversation — "
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
			p.add(roleHeading(head, m.error()));
			if (!m.text().isBlank()) p.add(body(m.text(), false));
			for (AgentContext.Call c : m.calls()) {
				p.add(Labels.small("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "")));
				if (c.args() != null && !c.args().isBlank()) p.add(body(c.args(), true));
			}
			if (m.result() != null) {
				p.add(Labels.small(m.error() ? "error result" : "result"));
				p.add(body(m.result(), true));
			}
			p.add(Panels.rule());
		}
		if (r.messages().isEmpty()) p.add(Labels.small("No messages."));
		return p;
	}

	private static JComponent cycle(List<SessionHistory.RawTurn> turns) {
		JPanel p = column();
		if (turns.isEmpty()) {
			p.add(Labels.small("No turns recorded for this conversation."));
			return p;
		}
		for (SessionHistory.RawTurn t : turns) {
			String head = t.role();
			if (t.meta() != null && !t.meta().isBlank()) head += "   ·  " + t.meta();
			p.add(roleHeading(head, t.error()));
			if (t.content() != null && !t.content().isBlank()) p.add(body(t.content(), false));
			for (SessionHistory.RawCall c : t.calls()) {
				p.add(Labels.small("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "")));
				if (c.args() != null && !c.args().isBlank()) p.add(body(c.args(), true));
			}
			if (t.toolResult() != null && !t.toolResult().isBlank()) {
				p.add(Labels.small(t.error() ? "error result" : "result"));
				p.add(body(t.toolResult(), true));
			}
			p.add(Panels.rule());
		}
		return p;
	}

	/** A heading, in the error tone when the message or turn failed. */
	private static JLabel roleHeading(String text, boolean error) {
		JLabel h = heading(text);
		if (error) Styles.classes(h, Styles.STRONG, Styles.ERROR);
		return h;
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
			p.add(Panels.rule());
		}
		if (!r.unavailable().isEmpty()) {
			p.add(heading("Unavailable"));
			p.add(body(String.join("\n", r.unavailable()), false));
		}
		if (r.tools().isEmpty() && r.unavailable().isEmpty()) p.add(Labels.small("No tools offered."));
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
			p.add(Labels.small(meta.toString()));
			p.add(Panels.rule());
		}
		if (r.loads().isEmpty()) p.add(Labels.small("Nothing loaded."));
		return p;
	}

}
