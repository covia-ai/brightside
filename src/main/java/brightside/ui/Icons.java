package brightside.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/**
 * The Brightside mark, painted at whatever size is asked for: a Covia-purple
 * moon with its bright phase illuminated from the upper right. The terminator
 * joins opposite points on the moon's circumference, so it spans a true
 * diameter as parallel illumination must. Painted rather than shipped as a
 * binary, so it is headless-safe and crisp at every tray and title-bar size.
 *
 * <p>For use outside the app the same mark is checked in under
 * {@code src/main/resources/icons/brightside/}: {@code brightside.svg} with this
 * geometry in a 100-unit box, and PNGs rendered by this painter at 16–1024 px.
 * Change the geometry here and regenerate them (and the SVG's path) together.
 */
public final class Icons {

	/** Covia primary purple (brand colour). */
	public static final Color COVIA_PURPLE = new Color(0x6B, 0x46, 0xC1);
	/** The bright side. */
	public static final Color BRIGHT = new Color(0xFF, 0xC8, 0x3D);

	private static final int[] APP_SIZES = { 16, 20, 24, 32, 48, 64, 128, 256 };
	/** Standard cubic approximation of one quarter of a circle. */
	private static final double KAPPA = 0.5522847498307936;
	/** Terminator curvature as a fraction of the moon radius. */
	private static final double PHASE = 0.28;
	/** Rotate the illuminated phase so light arrives from the upper right. */
	private static final double LIGHT_ANGLE = -Math.PI / 4.0;

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

			g.setColor(BRIGHT);
			g.fill(brightPhase(size, pad));
		} finally {
			g.dispose();
		}
		return img;
	}

	/**
	 * The illuminated region in local moon coordinates, then rotated towards the
	 * light source. Both the outer limb and elliptical terminator run between the
	 * same two antipodal points; the visible light therefore spans the moon's
	 * complete diameter instead of looking like a smaller circle laid over it.
	 */
	static Shape brightPhase(int size, float pad) {
		double radius = (size - 2.0 * pad) / 2.0;
		double cx = size / 2.0;
		double cy = size / 2.0;
		double inner = radius * PHASE;

		Path2D.Double phase = new Path2D.Double();
		phase.moveTo(cx, cy - radius);
		// Illuminated outer semicircle: top -> right -> bottom.
		phase.curveTo(cx + KAPPA * radius, cy - radius,
			cx + radius, cy - KAPPA * radius, cx + radius, cy);
		phase.curveTo(cx + radius, cy + KAPPA * radius,
			cx + KAPPA * radius, cy + radius, cx, cy + radius);
		// Elliptical terminator: bottom -> centre-right -> top. Its major axis is
		// exactly the moon diameter, which is the parallel-light invariant.
		phase.curveTo(cx + KAPPA * inner, cy + radius,
			cx + inner, cy + KAPPA * radius, cx + inner, cy);
		phase.curveTo(cx + inner, cy - KAPPA * radius,
			cx + KAPPA * inner, cy - radius, cx, cy - radius);
		phase.closePath();

		return AffineTransform.getRotateInstance(LIGHT_ANGLE, cx, cy)
			.createTransformedShape(phase);
	}

	/** The mark at every size a window manager might ask for. */
	public static List<Image> appIcons() {
		return Arrays.stream(APP_SIZES).mapToObj(Icons::icon).toList();
	}
}
