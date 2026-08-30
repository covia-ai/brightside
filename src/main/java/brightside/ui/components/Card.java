package brightside.ui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.function.Supplier;

import javax.swing.JPanel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * A rounded surface for content: a message bubble, an inbox card, a word chip,
 * the welcome panel. Fills with the theme's elevated {@linkplain Theme#surface
 * surface} unless told otherwise, and can carry a hairline outline. The arc and
 * outline scale with the UI like FlatLaf's own components.
 *
 * <p>Colours are read when painting — override {@link #fill()} and
 * {@link #outline()} for a card whose look depends on its state.
 */
@SuppressWarnings("serial")
public class Card extends JPanel {

	/** The arc used by bubbles, cards and chips unless one is given. */
	public static final int ARC = 14;

	private final int arc;
	private Supplier<Color> fill;
	private Supplier<Color> outline;

	public Card() {
		this(ARC);
	}

	public Card(int arc) {
		this.arc = arc;
		setOpaque(false);
	}

	/** A fixed fill colour; null (the default) paints the theme's surface. */
	public Card fill(Color colour) {
		return fill(colour == null ? null : () -> colour);
	}

	/** A fill read when painting — a {@link Theme} accessor such as {@code Theme::accent} follows a theme change. */
	public Card fill(Supplier<Color> colour) {
		this.fill = colour;
		repaint();
		return this;
	}

	/** A hairline around the card; null (the default) for none. */
	public Card outline(Color colour) {
		return outline(colour == null ? null : () -> colour);
	}

	/** An outline read when painting, so it follows a theme change. */
	public Card outline(Supplier<Color> colour) {
		this.outline = colour;
		repaint();
		return this;
	}

	protected Color fill() {
		return (fill != null) ? fill.get() : Theme.surface();
	}

	protected Color outline() {
		return (outline != null) ? outline.get() : null;
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			float a = UIScale.scale((float) arc);
			int w = getWidth();
			int h = getHeight();
			Color f = fill();
			if (f != null) {
				g2.setColor(f);
				g2.fill(FlatUIUtils.createComponentRectangle(0, 0, w, h, a));
			}
			Color o = outline();
			if (o != null) {
				g2.setColor(o);
				FlatUIUtils.paintOutline(g2, 0, 0, w, h, UIScale.scale(1f), a);
			}
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
