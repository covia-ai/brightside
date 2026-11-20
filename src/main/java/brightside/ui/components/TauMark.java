package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;

import javax.swing.Icon;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * The τ mark as a glyph, drawn in the font at any size. The
 * {@linkplain #brand brand} version carries the τ gradient and belongs only
 * to brand moments (onboarding's Welcome and Finish, and About); everywhere
 * else, such as empty states, the glyph is drawn in the theme's accent text
 * colour. Never on the Record, the Vault, the Peers map, agent rows,
 * refusals, or errors.
 */
public final class TauMark implements Icon {

	/** The τ gradient, left to right. */
	private static final Color[] GRADIENT = { new Color(0x72C0FD), new Color(0x7FB3FD), new Color(0x8D99FC),
		new Color(0x9D84FC), new Color(0xA975FA), new Color(0xB070FC) };

	private final int size;
	private final boolean brand;

	private TauMark(int size, boolean brand) {
		this.size = size;
		this.brand = brand;
	}

	/** The brand moment's τ, in the gradient, {@code size} points square. */
	public static TauMark brand(int size) {
		return new TauMark(size, true);
	}

	/** The everyday τ, in the accent text colour, {@code size} points square. */
	public static TauMark plain(int size) {
		return new TauMark(size, false);
	}

	public boolean isBrand() {
		return brand;
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			int s = getIconWidth();
			Font base = UIManager.getFont("defaultFont");
			if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
			Font f = base.deriveFont(Font.BOLD, s * 1.15f);
			GlyphVector gv = f.createGlyphVector(g2.getFontRenderContext(), "τ");
			Rectangle2D b = gv.getVisualBounds();
			// Centred on its own ink, so the glyph sits in the middle of its square whatever the font's metrics.
			float gx = (float) (x + (s - b.getWidth()) / 2 - b.getX());
			float gy = (float) (y + (s - b.getHeight()) / 2 - b.getY());
			if (brand) {
				float[] stops = new float[GRADIENT.length];
				for (int i = 0; i < stops.length; i++) stops[i] = i / (float) (stops.length - 1);
				g2.setPaint(new LinearGradientPaint(x, y, x + s, y + s, stops, GRADIENT));
			} else {
				g2.setColor(Theme.accentText());
			}
			g2.fill(gv.getOutline(gx, gy));
		} finally {
			g2.dispose();
		}
	}

	@Override
	public int getIconWidth() {
		return UIScale.scale(size);
	}

	@Override
	public int getIconHeight() {
		return UIScale.scale(size);
	}
}
