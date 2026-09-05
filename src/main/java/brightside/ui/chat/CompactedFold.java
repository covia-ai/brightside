package brightside.ui.chat;

import java.awt.Dimension;
import java.awt.Insets;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import brightside.SessionHistory;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Disclosure;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.SelectableText;

/**
 * A quiet, collapsed marker where a compacted stretch of the conversation was,
 * opening to the summary the assistant wrote in its place. The transcript is
 * the conversation, not the assistant's context: the summary is written for
 * its own later turns, so it stays a click away rather than sitting among the
 * messages as though it had been said. Same shape as
 * {@link ExpandableActivity}, the chip for a turn's tool use, so the eye reads
 * both as the assistant's working rather than its words. The summary is a
 * {@link SelectableText} so it can be selected and copied; a right-click
 * offers <em>Copy summary</em>.
 */
@SuppressWarnings("serial")
final class CompactedFold extends JPanel {

	static final String LABEL = "Earlier conversation summarised";
	private static final int WRAP = 600;
	private static final int CHEVRON = 12;

	/**
	 * @param s             the summary standing for the archived turns
	 * @param onToggle      run after expand/collapse so the host column can re-lay out
	 * @param selectionSink notified with the summary's text area when it holds a
	 *                      selection, so the panel's copy shortcut can pick it up
	 */
	CompactedFold(SessionHistory.Summary s, Runnable onToggle, Consumer<JTextArea> selectionSink) {
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		SelectableText summary = new SelectableText(s.text()).unfocusable().muted().small().wrapAt(WRAP);
		if (selectionSink != null) {
			summary.addCaretListener(e -> {
				if (e.getDot() != e.getMark()) selectionSink.accept(summary);
			});
		}
		JPanel body = Panels.column();
		body.setBorder(BorderFactory.createEmptyBorder(2, 12, 6, 4));
		body.add(summary);

		Disclosure fold = new Disclosure(Labels.small(LABEL), body, CHEVRON)
			.compact()
			.onToggle(() -> {
				revalidate();
				if (onToggle != null) onToggle.run();
			})
			.onPopup(() -> menu(s.text()));
		fold.header().setMargin(new Insets(2, 6, 2, 6));
		add(fold);
	}

	private static JPopupMenu menu(String text) {
		JPopupMenu m = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy summary");
		copy.addActionListener(e -> Clipboard.copy(text));
		m.add(copy);
		return m;
	}

	@Override
	public Dimension getMaximumSize() {
		Dimension p = getPreferredSize();
		return new Dimension(Math.min(p.width, 680), p.height);
	}
}
