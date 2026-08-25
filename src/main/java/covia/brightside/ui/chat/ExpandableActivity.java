package covia.brightside.ui.chat;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import covia.brightside.SessionHistory;

/**
 * A collapsed "N tool steps" chip that expands to show a turn's intermediate
 * work — the assistant's "let me try…" narration and each tool call's name,
 * ✓/✕ outcome and (truncated) result. Collapsed by default so the final reply
 * is what the eye lands on, with the detail one click away. The narration and
 * results are {@link SelectableText} so they can be selected and copied.
 */
@SuppressWarnings("serial")
final class ExpandableActivity extends JPanel {

	private static final int WRAP = 440;

	private final JLabel header;
	private final JPanel body;
	private final int toolCount;
	private final Runnable onToggle;
	private final Consumer<JTextArea> selectionSink;
	private boolean expanded;

	/**
	 * @param a             the grouped narration and tool steps for one turn
	 * @param onToggle      run after expand/collapse so the host column can re-lay out
	 * @param selectionSink notified with a text area when it holds a selection, so
	 *                      the panel's copy shortcut can pick it up
	 */
	ExpandableActivity(SessionHistory.Activity a, Runnable onToggle, Consumer<JTextArea> selectionSink) {
		this.onToggle = onToggle;
		this.selectionSink = selectionSink;
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
			return selectable(s.detail(), true);
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
			p.add(selectable(ChatStyle.truncate(s.detail(), 800), false));
		}
		return p;
	}

	/** A selectable run of muted text that reports its selection to the panel. */
	private SelectableText selectable(String text, boolean italic) {
		SelectableText t = new SelectableText(text, ChatStyle.muted(), italic, WRAP);
		if (selectionSink != null) {
			t.addCaretListener(e -> {
				if (e.getDot() != e.getMark()) selectionSink.accept(t);
			});
		}
		return t;
	}

	@Override
	public Dimension getMaximumSize() {
		Dimension p = getPreferredSize();
		return new Dimension(Math.min(p.width, 680), p.height);
	}
}
