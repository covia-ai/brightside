package brightside.ui.chat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import brightside.SessionHistory;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Disclosure;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * A collapsed "N tool steps" chip that expands to show a turn's intermediate
 * work — the assistant's "let me try…" narration and, for each tool call, a
 * further expandable row revealing the call's arguments and its result.
 * Collapsed by default so the final reply is what the eye lands on, with the
 * detail one (or two) clicks away. Both levels are {@link Disclosure}s;
 * narration and results are {@link SelectableText} so they can be selected
 * and copied; the ✓/✕ marks are {@link Lucide} icons, not glyphs, so they
 * render in any UI font.
 */
@SuppressWarnings("serial")
final class ExpandableActivity extends JPanel {

	private static final int WRAP = 440;
	private static final int CHEVRON = 12;
	private static final int MARK = 12;
	private static final int DETAIL_LIMIT = 1000;

	private final Runnable onToggle;
	private final Consumer<JTextArea> selectionSink;

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

		int toolCount = (int) a.steps().stream().filter(SessionHistory.Step::tool).count();
		JPanel body = Panels.column();
		body.setBorder(BorderFactory.createEmptyBorder(2, 12, 6, 4));
		for (SessionHistory.Step s : a.steps()) body.add(s.tool() ? new ToolStep(s) : selectable(s.detail(), true));

		Disclosure chip = new Disclosure(Labels.small(summary(toolCount)), body, CHEVRON)
			.compact()
			.onToggle(this::relayout);
		chip.header().setMargin(new Insets(2, 6, 2, 6));
		add(chip);
	}

	private static String summary(int toolCount) {
		if (toolCount == 1) return "1 tool step";
		if (toolCount > 1) return toolCount + " tool steps";
		return "details";
	}

	/** Re-lay out this chip and let the host column reflow around it. */
	private void relayout() {
		revalidate();
		if (onToggle != null) onToggle.run();
	}

	/** A selectable run of muted text that reports its selection to the panel. */
	private SelectableText selectable(String text, boolean italic) {
		SelectableText t = new SelectableText(text).unfocusable().muted().small().wrapAt(WRAP);
		if (italic) t.italic();
		if (selectionSink != null) {
			t.addCaretListener(e -> {
				if (e.getDot() != e.getMark()) selectionSink.accept(t);
			});
		}
		return t;
	}

	/** The header of a tool step: a ✓/✕ mark and the tool's name. */
	private static JPanel stepHeader(SessionHistory.Step s) {
		JPanel row = Panels.row();
		Supplier<Color> tone = s.error() ? Theme::error : Theme::success;
		JLabel status = Labels.icon(Lucide.icon(s.error() ? "x" : "check", MARK, tone));
		status.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
		JLabel name = Styles.classes(Labels.text(s.title()), Styles.SMALL);
		if (s.error()) Styles.add(name, Styles.ERROR);
		row.add(status);
		row.add(name);
		return row;
	}

	/** The body of a tool step: its input arguments and its result, each under a caption. */
	private JPanel stepDetail(SessionHistory.Step s) {
		JPanel detail = Panels.column();
		detail.setBorder(BorderFactory.createEmptyBorder(0, 18, 4, 2));
		if (has(s.call())) {
			detail.add(caption("Input"));
			detail.add(selectable(truncate(s.call()), false));
		}
		if (has(s.detail())) {
			detail.add(caption("Result"));
			detail.add(selectable(truncate(s.detail()), false));
		}
		return detail;
	}

	private static JLabel caption(String text) {
		JLabel l = Labels.caption(text);
		l.setBorder(BorderFactory.createEmptyBorder(4, 0, 1, 0));
		return l;
	}

	private static boolean has(String s) {
		return s != null && !s.isBlank();
	}

	private static String truncate(String s) {
		return (s.length() <= DETAIL_LIMIT) ? s : s.substring(0, DETAIL_LIMIT) + "…";
	}

	@Override
	public Dimension getMaximumSize() {
		Dimension p = getPreferredSize();
		return new Dimension(Math.min(p.width, 680), p.height);
	}

	/** One tool call as its own expandable row; a right-click offers copy actions. */
	private final class ToolStep extends Disclosure {

		private final SessionHistory.Step step;

		ToolStep(SessionHistory.Step s) {
			super(stepHeader(s), stepDetail(s), CHEVRON);
			this.step = s;
			compact();
			onToggle(ExpandableActivity.this::relayout);
			onPopup(this::menu);
			header().setMargin(new Insets(1, 4, 1, 4));
			setBorder(BorderFactory.createEmptyBorder(1, 0, 2, 0));
		}

		/** Right-click menu: copy this tool call's input, result, or both. */
		private JPopupMenu menu() {
			JPopupMenu m = new JPopupMenu();
			boolean hasInput = has(step.call());
			boolean hasResult = has(step.detail());
			if (hasInput) m.add(item("Copy input", step.call()));
			if (hasResult) m.add(item("Copy result", step.detail()));
			if (hasInput && hasResult) m.add(item("Copy input & result", combined()));
			if (m.getComponentCount() == 0) m.add(item("Copy tool name", step.title()));
			return m;
		}

		private static JMenuItem item(String label, String text) {
			JMenuItem i = new JMenuItem(label);
			i.addActionListener(e -> Clipboard.copy(text));
			return i;
		}

		private String combined() {
			StringBuilder sb = new StringBuilder(step.title()).append('\n');
			if (has(step.call())) sb.append("\nInput:\n").append(step.call());
			if (has(step.detail())) sb.append("\nResult:\n").append(step.detail());
			return sb.toString();
		}
	}
}
