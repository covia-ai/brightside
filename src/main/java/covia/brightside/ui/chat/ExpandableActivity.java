package covia.brightside.ui.chat;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import covia.brightside.SessionHistory;

/**
 * A collapsed "N tool steps" chip that expands to show a turn's intermediate
 * work — the assistant's "let me try…" narration and each tool call's name,
 * ✓/✕ outcome and (truncated) result. Collapsed by default so the final reply
 * is what the eye lands on, with the detail one click away.
 */
@SuppressWarnings("serial")
final class ExpandableActivity extends JPanel {

	private final JLabel header;
	private final JPanel body;
	private final int toolCount;
	private final Runnable onToggle;
	private boolean expanded;

	/**
	 * @param a        the grouped narration and tool steps for one turn
	 * @param onToggle run after expand/collapse so the host column can re-lay out
	 */
	ExpandableActivity(SessionHistory.Activity a, Runnable onToggle) {
		this.onToggle = onToggle;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		toolCount = (int) a.steps().stream().filter(SessionHistory.Step::tool).count();

		header = new JLabel();
		header.putClientProperty("FlatLaf.styleClass", "small");
		header.setForeground(ChatStyle.muted());
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				toggle();
			}
		});
		updateHeader();

		body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(2, 12, 6, 4));
		body.setVisible(false);
		body.setAlignmentX(LEFT_ALIGNMENT);
		for (SessionHistory.Step s : a.steps()) body.add(stepComponent(s));

		add(header);
		add(body);
	}

	private String summary() {
		if (toolCount == 1) return "1 tool step";
		if (toolCount > 1) return toolCount + " tool steps";
		return "details";
	}

	private void updateHeader() {
		header.setText((expanded ? "▾  " : "▸  ") + summary());
	}

	private void toggle() {
		expanded = !expanded;
		body.setVisible(expanded);
		updateHeader();
		revalidate();
		if (onToggle != null) onToggle.run();
	}

	private Component stepComponent(SessionHistory.Step s) {
		if (!s.tool()) {
			return ChatStyle.htmlLabel(s.detail(), ChatStyle.muted(), true);
		}
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		p.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = new JLabel((s.error() ? "✕ " : "✓ ") + s.title());
		title.setForeground(s.error() ? ChatStyle.ERROR : ChatStyle.OK);
		title.putClientProperty("FlatLaf.styleClass", "small");
		title.setAlignmentX(LEFT_ALIGNMENT);
		p.add(title);
		if (s.detail() != null && !s.detail().isBlank()) {
			p.add(ChatStyle.htmlLabel(ChatStyle.truncate(s.detail(), 800), ChatStyle.muted(), false));
		}
		return p;
	}

	@Override
	public Dimension getMaximumSize() {
		Dimension p = getPreferredSize();
		return new Dimension(Math.min(p.width, 680), p.height);
	}
}
