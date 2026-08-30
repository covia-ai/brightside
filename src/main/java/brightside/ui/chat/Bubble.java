package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;

import brightside.ui.components.Card;
import brightside.ui.components.SelectableText;

/**
 * A rounded message bubble — a {@link Card} — wrapping a run of
 * {@link SelectableText}. The text is read-only: the bubble never takes
 * keyboard focus (so the chat input keeps it) and shows no insert caret, but
 * stays mouse-selectable with the selection painted even without focus. Its
 * width reflows to a share of the viewport.
 *
 * <p>The bubble is deliberately dumb — selection tracking and the context menu
 * are wired by {@link ChatPanel} onto {@link #textArea()}, so a bubble can be
 * reused wherever a rounded, selectable run of text is wanted.
 */
@SuppressWarnings("serial")
final class Bubble extends Card {
	private static final int ARC = 20;
	private static final int PAD_H = 14;
	private static final int PAD_V = 10;
	// The text view needs a hair more width than FontMetrics measures, or it wraps
	// the last word at its own natural width — leaving a too-thin, extra-line bubble.
	private static final int WRAP_SLACK = 6;

	private final SelectableText ta;
	private int maxWidth = 460;

	/** {@code bg} null paints the theme's surface (the assistant's side). */
	Bubble(String text, Color bg, Color fg) {
		super(ARC);
		setLayout(new BorderLayout());
		fill(bg);
		ta = new SelectableText(text).unfocusable().colour(fg).size(1f);
		ta.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
		add(ta, BorderLayout.CENTER);
	}

	/** The bubble's text area — for wiring selection tracking and a context menu. */
	JTextArea textArea() {
		return ta;
	}

	void setAvailableWidth(int viewportWidth) {
		int w = (viewportWidth > 0) ? (int) (viewportWidth * 0.78) : 460;
		maxWidth = Math.max(200, Math.min(660, w));
		revalidate();
	}

	@Override
	public Dimension getPreferredSize() {
		int maxTextWidth = Math.max(40, maxWidth - 2 * PAD_H);
		FontMetrics fm = ta.getFontMetrics(ta.getFont());
		int longest = 0;
		for (String line : ta.getText().split("\n", -1)) {
			longest = Math.max(longest, fm.stringWidth(line));
		}
		int textWidth = Math.min(longest + WRAP_SLACK, maxTextWidth);
		// The text area's border already supplies PAD_H/PAD_V. Size the area to
		// the complete bubble width and return its complete preferred height;
		// adding the padding again leaves a conspicuous blank band at the bottom.
		int bubbleWidth = textWidth + 2 * PAD_H;
		ta.setSize(bubbleWidth, Short.MAX_VALUE);
		return new Dimension(bubbleWidth, ta.getPreferredSize().height);
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}
}
