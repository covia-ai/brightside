package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;

/**
 * A rounded message bubble wrapping a wrapping, selectable text area. The text
 * is read-only: the bubble never takes keyboard focus (so the chat input keeps
 * it) and shows no insert caret, but stays mouse-selectable with the selection
 * painted even without focus. Its width reflows to a share of the viewport.
 *
 * <p>The bubble is deliberately dumb — selection tracking and the context menu
 * are wired by {@link ChatPanel} onto {@link #textArea()}, so a bubble can be
 * reused wherever a rounded, selectable run of text is wanted.
 */
@SuppressWarnings("serial")
final class Bubble extends JPanel {
	private static final int ARC = 20;
	private static final int PAD_H = 14;
	private static final int PAD_V = 10;
	// The text view needs a hair more width than FontMetrics measures, or it wraps
	// the last word at its own natural width — leaving a too-thin, extra-line bubble.
	private static final int WRAP_SLACK = 6;

	private final JTextArea ta;
	private final Color bg;
	private int maxWidth = 460;

	Bubble(String text, Color bg, Color fg) {
		super(new BorderLayout());
		this.bg = bg;
		setOpaque(false);
		ta = new JTextArea(text);
		ta.setEditable(false);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setOpaque(false);
		ta.setForeground(fg);
		// A read-only bubble never takes keyboard focus (so the input keeps it)
		// and shows no insert caret — but stays mouse-selectable, with the
		// selection painted even without focus.
		ta.setFocusable(false);
		DefaultCaret caret = new DefaultCaret() {
			@Override
			public void setVisible(boolean visible) {
				super.setVisible(false);
			}

			@Override
			public void setSelectionVisible(boolean visible) {
				super.setSelectionVisible(true);
			}
		};
		caret.setBlinkRate(0);
		ta.setCaret(caret);
		caret.setSelectionVisible(true);
		ta.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
		ta.setFont(ta.getFont().deriveFont(ta.getFont().getSize2D() + 1f));
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

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(bg);
		g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
		g2.dispose();
		super.paintComponent(g);
	}
}
