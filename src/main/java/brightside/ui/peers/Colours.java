package brightside.ui.peers;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.function.Supplier;

import javax.swing.Icon;

import com.formdev.flatlaf.util.UIScale;

import brightside.peers.MapSettings;
import brightside.ui.components.Theme;

/**
 * The colours of bodies on the Peers map, shared with the rest of the app so
 * an agent looks the same wherever it appears: on the map as a moon, on its
 * card, and in the Sessions agent list. Each is chosen by key, so it is the
 * same on every launch.
 */
public final class Colours {

	/** Planet, moon and star colours, chosen by key. */
	static final Color[] PALETTE = { new Color(0x5EC8F2), new Color(0x7FE0C4), new Color(0xF2A65E),
		new Color(0xE58BB4), new Color(0xB79CF2), new Color(0xE8C66A), new Color(0x6FA8F7), new Color(0xA8B48A),
		new Color(0xF29F8E), new Color(0x8FD3A6) };

	private Colours() {
	}

	/** The colour for a key, usually a DID. */
	public static Color of(String key) {
		return PALETTE[(int) (MapSettings.hash(key, 4) * PALETTE.length)];
	}

	/**
	 * An agent's colour: the brand colour for your main assistant, else chosen
	 * by its key on the map, {@code <ownerDID>/g/<agentId>}.
	 */
	public static Color agent(String ownerDID, String agentId, boolean main) {
		return main ? Theme.accent() : of(ownerDID + "/g/" + agentId);
	}

	/** A small shaded moon in {@code colour}, as the map draws one, {@code size} pixels square before scaling. */
	public static Icon moon(Supplier<Color> colour, int size) {
		return new Icon() {
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				float s = getIconWidth(), r = s * 0.4f, cx = x + s / 2f, cy = y + s / 2f;
				Color hue = colour.get();
				g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r * 1.3f,
					new Point2D.Float(cx - r * 0.35f, cy - r * 0.35f), new float[] { 0f, 1f },
					new Color[] { Theme.blend(hue, Color.WHITE, 0.3f), Theme.blend(hue, Color.BLACK, 0.55f) },
					MultipleGradientPaint.CycleMethod.NO_CYCLE));
				g2.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
				g2.dispose();
			}

			@Override
			public int getIconWidth() {
				return UIScale.scale(size);
			}

			@Override
			public int getIconHeight() {
				return getIconWidth();
			}
		};
	}
}
