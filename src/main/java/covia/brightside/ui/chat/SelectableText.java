package covia.brightside.ui.chat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;

/**
 * A wrapping run of read-only, mouse-selectable text — used for the narration
 * and tool results inside an {@link ExpandableActivity} so they can be
 * selected and copied (a {@code JLabel} cannot).
 *
 * <p>Like {@link Bubble}'s text, it never takes keyboard focus (the chat input
 * keeps it) and shows no insert caret, but the selection is painted even
 * without focus. It wraps at a fixed width so it lays out predictably in a
 * vertical stack.
 */
@SuppressWarnings("serial")
final class SelectableText extends JTextArea {

	private final int wrapWidth;

	SelectableText(String text, Color fg, boolean italic, int wrapWidth) {
		super(text);
		this.wrapWidth = wrapWidth;
		setEditable(false);
		setLineWrap(true);
		setWrapStyleWord(true);
		setOpaque(false);
		setForeground(fg);
		setFocusable(false);
		setBorder(BorderFactory.createEmptyBorder());
		setFont(getFont().deriveFont(italic ? Font.ITALIC : Font.PLAIN, getFont().getSize2D() - 1f));
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
		setCaret(caret);
		caret.setSelectionVisible(true);
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	@Override
	public Dimension getPreferredSize() {
		// Wrap at a fixed width and report the resulting height, so a vertical
		// BoxLayout gives it exactly the room it needs.
		setSize(wrapWidth, Short.MAX_VALUE);
		int h = super.getPreferredSize().height;
		return new Dimension(wrapWidth, h);
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}
}
