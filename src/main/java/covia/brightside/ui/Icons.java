package covia.brightside.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/**
 * The BrightSide mark, painted at whatever size is asked for: a Covia-purple
 * disc with a sun rising over its top-right edge — the bright side. Painted
 * rather than shipped as a binary, so it is headless-safe and crisp at every
 * tray and title-bar size.
 */
public final class Icons {

	/** Covia primary purple (brand colour). */
	public static final Color COVIA_PURPLE = new Color(0x6B, 0x46, 0xC1);
	/** The bright side. */
	public static final Color BRIGHT = new Color(0xFF, 0xC8, 0x3D);

	private static final int[] APP_SIZES = { 16, 20, 24, 32, 48, 64, 128, 256 };

	private Icons() {
	}

	/** Paints the mark at {@code size} pixels square. */
	public static Image icon(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			float pad = Math.max(1f, size * 0.04f);
			Ellipse2D disc = new Ellipse2D.Float(pad, pad, size - 2 * pad, size - 2 * pad);
			g.setColor(COVIA_PURPLE);
			g.fill(disc);

			// The sun, clipped to the disc so it reads as light on the rim.
			g.setClip(disc);
			float d = size * 0.78f;
			g.setColor(BRIGHT);
			g.fill(new Ellipse2D.Float(size * 0.42f, -size * 0.22f, d, d));
		} finally {
			g.dispose();
		}
		return img;
	}

	/** The mark at every size a window manager might ask for. */
	public static List<Image> appIcons() {
		return Arrays.stream(APP_SIZES).mapToObj(Icons::icon).toList();
	}
}
