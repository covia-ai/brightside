package covia.brightside.ui.inspect;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

import covia.brightside.AgentContext;

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

	public ContextInspector(AgentContext.Report report) {
		super(new BorderLayout());
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Overview", overview(report));
		tabs.addTab("Context (" + report.messages().size() + ")", scroll(messages(report)));
		tabs.addTab("Tools (" + report.tools().size() + ")", scroll(tools(report)));
		tabs.addTab("Skills (" + report.loads().size() + ")", scroll(loads(report)));
		tabs.addTab("Raw", rawTab(report));
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

	private static JComponent messages(AgentContext.Report r) {
		JPanel p = column();
		for (AgentContext.Message m : r.messages()) {
			p.add(heading(m.role()));
			p.add(body(m.text(), false));
			p.add(divider());
		}
		if (r.messages().isEmpty()) p.add(small("No messages."));
		return p;
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

	private static JComponent rawTab(AgentContext.Report r) {
		JTextArea ta = new JTextArea(r.rawJson());
		ta.setEditable(false);
		ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		ta.setCaretPosition(0);
		JScrollPane sp = new JScrollPane(ta);
		sp.setBorder(BorderFactory.createEmptyBorder());
		return sp;
	}

	// ------------------------------------------------------------------
	// Building blocks
	// ------------------------------------------------------------------

	private static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		p.setOpaque(false);
		return p;
	}

	private static JScrollPane scroll(JComponent inner) {
		JScrollPane sp = new JScrollPane(inner);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(24);
		return sp;
	}

	private static JPanel kv(String key, String value) {
		JPanel row = new JPanel(new BorderLayout(12, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel k = new JLabel(key);
		k.setForeground(muted());
		k.setPreferredSize(new Dimension(130, k.getPreferredSize().height));
		JTextArea v = body(value, false);
		row.add(k, BorderLayout.WEST);
		row.add(v, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JLabel heading(String text) {
		JLabel l = new JLabel(text);
		l.setFont(l.getFont().deriveFont(Font.BOLD));
		l.setForeground(accentText());
		l.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/** A read-only, wrapping, selectable block. Focusable, so native copy works in the dialog. */
	private static JTextArea body(String text, boolean mono) {
		JTextArea ta = new JTextArea(text);
		ta.setEditable(false);
		ta.setLineWrap(!mono);
		ta.setWrapStyleWord(true);
		ta.setOpaque(false);
		ta.setBorder(null);
		if (mono) ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		ta.setAlignmentX(LEFT_ALIGNMENT);
		return ta;
	}

	private static JLabel small(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setForeground(muted());
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static Component divider() {
		JPanel d = new JPanel();
		d.setOpaque(true);
		d.setBackground(line());
		d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		d.setAlignmentX(LEFT_ALIGNMENT);
		d.setBorder(BorderFactory.createEmptyBorder());
		return d;
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	private static Color accentText() {
		Color c = UIManager.getColor("Component.accentColor");
		return (c != null) ? c : UIManager.getColor("Label.foreground");
	}

	private static Color line() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}
}
