package brightside.ui.components;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

import javax.swing.JLabel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * A round badge holding an icon in its {@link Tone} on that tone's faint
 * ground: what kind of thing a row or a tile is, read at a glance. Sized in
 * unscaled pixels, like FlatLaf's own components, and scaled with the UI.
 */
@SuppressWarnings("serial")
public final class Badge extends JLabel {

	private final Tone tone;
	private final int size;

	/** {@code size}: the diameter, of which the icon takes five-eighths. */
	public Badge(String icon, Tone tone, int size) {
		super(Lucide.icon(icon, Math.round(size * 5 / 8f), tone::colour), CENTER);
		this.tone = tone;
		this.size = size;
		setOpaque(false);
	}

	@Override
	public Dimension getPreferredSize() {
		int s = UIScale.scale(size);
		return new Dimension(s, s);
	}

	@Override
	public Dimension getMinimumSize() {
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(tone.tint());
			g2.fill(new Ellipse2D.Float(0, 0, getWidth(), getHeight()));
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
