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
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import covia.brightside.SessionHistory;

/**
 * A collapsed "N tool steps" chip that expands to show a turn's intermediate
 * work — the assistant's "let me try…" narration and, for each tool call, a
 * further expandable row revealing the call's arguments and its result.
 * Collapsed by default so the final reply is what the eye lands on, with the
 * detail one (or two) clicks away. Narration and results are
 * {@link SelectableText} so they can be selected and copied. Disclosure
 * chevrons and the ✓/✕ marks are painted ({@link ChatIcons}), not glyphs, so
 * they render in any UI font.
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
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) toggle();
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
		header.setIcon(ChatIcons.chevron(expanded, ChatStyle.muted()));
		header.setText(summary());
	}

	private void toggle() {
		expanded = !expanded;
		body.setVisible(expanded);
		updateHeader();
		relayout();
	}

	/** Re-lay out this chip and let the host column reflow around it. */
	private void relayout() {
		revalidate();
		if (onToggle != null) onToggle.run();
	}

	private Component stepComponent(SessionHistory.Step s) {
		if (!s.tool()) return selectable(s.detail(), true);
		return new ToolStep(s);
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

	private static JLabel caption(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty("FlatLaf.styleClass", "mini");
		l.setForeground(ChatStyle.muted());
		l.setBorder(BorderFactory.createEmptyBorder(4, 0, 1, 0));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	@Override
	public Dimension getMaximumSize() {
		Dimension p = getPreferredSize();
		return new Dimension(Math.min(p.width, 680), p.height);
	}

	/** One tool call as its own expandable row: a header (✓/✕ + name), and a
	 *  body revealing the call's input arguments and its result. */
	private final class ToolStep extends JPanel {

		private final SessionHistory.Step step;
		private final JLabel chevron;
		private final JPanel detail;
		private boolean open;

		ToolStep(SessionHistory.Step s) {
			this.step = s;
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			setBorder(BorderFactory.createEmptyBorder(1, 0, 2, 0));

			chevron = new JLabel(ChatIcons.chevron(false, ChatStyle.muted()));
			JLabel status = new JLabel(ChatIcons.mark(!s.error(), s.error() ? ChatStyle.ERROR : ChatStyle.OK));
			status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 5));
			JLabel name = new JLabel(s.title());
			name.putClientProperty("FlatLaf.styleClass", "small");
			name.setForeground(s.error() ? ChatStyle.ERROR : ChatStyle.foreground());

			JPanel headerRow = new JPanel();
			headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.X_AXIS));
			headerRow.setOpaque(false);
			headerRow.setAlignmentX(LEFT_ALIGNMENT);
			headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			headerRow.add(chevron);
			headerRow.add(status);
			headerRow.add(name);
			// Left-click toggles; right-click offers copy actions (not a toggle).
			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (e.isPopupTrigger()) menu().show(e.getComponent(), e.getX(), e.getY());
					else if (SwingUtilities.isLeftMouseButton(e)) toggle();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					if (e.isPopupTrigger()) menu().show(e.getComponent(), e.getX(), e.getY());
				}
			};
			headerRow.addMouseListener(mouse);
			chevron.addMouseListener(mouse);
			status.addMouseListener(mouse);
			name.addMouseListener(mouse);

			detail = new JPanel();
			detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
			detail.setOpaque(false);
			detail.setBorder(BorderFactory.createEmptyBorder(0, 18, 4, 2));
			detail.setVisible(false);
			detail.setAlignmentX(LEFT_ALIGNMENT);
			if (s.call() != null && !s.call().isBlank()) {
				detail.add(caption("Input"));
				detail.add(selectable(ChatStyle.truncate(s.call(), 1000), false));
			}
			if (s.detail() != null && !s.detail().isBlank()) {
				detail.add(caption("Result"));
				detail.add(selectable(ChatStyle.truncate(s.detail(), 1000), false));
			}

			add(headerRow);
			add(detail);
		}

		private void toggle() {
			open = !open;
			detail.setVisible(open);
			chevron.setIcon(ChatIcons.chevron(open, ChatStyle.muted()));
			relayout();
		}

		/** Right-click menu: copy this tool call's input, result, or both. */
		private JPopupMenu menu() {
			JPopupMenu m = new JPopupMenu();
			boolean hasInput = step.call() != null && !step.call().isBlank();
			boolean hasResult = step.detail() != null && !step.detail().isBlank();
			if (hasInput) m.add(item("Copy input", step.call()));
			if (hasResult) m.add(item("Copy result", step.detail()));
			if (hasInput && hasResult) m.add(item("Copy input & result", combined()));
			if (m.getComponentCount() == 0) m.add(item("Copy tool name", step.title()));
			return m;
		}

		private JMenuItem item(String label, String text) {
			JMenuItem i = new JMenuItem(label);
			i.addActionListener(e -> copy(text));
			return i;
		}

		private String combined() {
			StringBuilder sb = new StringBuilder(step.title()).append('\n');
			if (step.call() != null && !step.call().isBlank()) sb.append("\nInput:\n").append(step.call());
			if (step.detail() != null && !step.detail().isBlank()) sb.append("\nResult:\n").append(step.detail());
			return sb.toString();
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	private static void copy(String text) {
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new java.awt.datatransfer.StringSelection(text), null);
	}
}
