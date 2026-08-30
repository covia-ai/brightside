package brightside.ui.components;

import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;

import javax.swing.JTextArea;

import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * A multi-line input with a placeholder. FlatLaf paints
 * {@code JTextField.placeholderText} for single-line fields only, so a text
 * area that wants a hint — the chat composer, a pasted phrase or token —
 * paints its own, in the theme's placeholder colour, while it is empty.
 */
@SuppressWarnings("serial")
public class TextArea extends JTextArea {

	private String placeholder;

	public TextArea(int rows, int columns) {
		super(rows, columns);
	}

	/** The hint shown while the area is empty; null for none. */
	public TextArea placeholder(String text) {
		this.placeholder = text;
		repaint();
		return this;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (placeholder == null || placeholder.isEmpty() || getDocument().getLength() > 0) return;
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setFont(getFont());
			g2.setColor(FlatUIUtils.getUIColor("TextField.placeholderForeground", Theme.muted()));
			Insets in = getInsets();
			FontMetrics fm = g2.getFontMetrics();
			FlatUIUtils.drawString(this, g2, placeholder, in.left, in.top + fm.getAscent());
		} finally {
			g2.dispose();
		}
	}
}
