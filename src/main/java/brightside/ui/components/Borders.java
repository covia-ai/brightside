package brightside.ui.components;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

import com.formdev.flatlaf.util.UIScale;

/**
 * Borders that carry the theme's hairline, read when painted so they follow a
 * theme change. Plain padding is {@link BorderFactory#createEmptyBorder}.
 */
public final class Borders {

	private Borders() {
	}

	/** A hairline in the theme's separator colour on the chosen sides, scaled with the UI. */
	public static Border hairline(boolean top, boolean left, boolean bottom, boolean right) {
		return new Hairline(top, left, bottom, right);
	}

	/** A hairline above. */
	public static Border hairlineTop() {
		return hairline(true, false, false, false);
	}

	/** A hairline on the trailing edge — the edge of a side pane. */
	public static Border hairlineRight() {
		return hairline(false, false, false, true);
	}

	/** A hairline box with inner padding: the edge of a bare input that has no scroll pane to draw one. */
	public static Border field() {
		return BorderFactory.createCompoundBorder(hairline(true, true, true, true),
			BorderFactory.createEmptyBorder(4, 6, 4, 6));
	}

	@SuppressWarnings("serial")
	private static final class Hairline extends AbstractBorder {
		private final boolean top;
		private final boolean left;
		private final boolean bottom;
		private final boolean right;

		Hairline(boolean top, boolean left, boolean bottom, boolean right) {
			this.top = top;
			this.left = left;
			this.bottom = bottom;
			this.right = right;
		}

		@Override
		public Insets getBorderInsets(Component c, Insets insets) {
			int t = UIScale.scale(1);
			insets.set(top ? t : 0, left ? t : 0, bottom ? t : 0, right ? t : 0);
			return insets;
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
			Graphics g2 = g.create();
			try {
				g2.setColor(Theme.line());
				int t = UIScale.scale(1);
				if (top) g2.fillRect(x, y, w, t);
				if (bottom) g2.fillRect(x, y + h - t, w, t);
				if (left) g2.fillRect(x, y, t, h);
				if (right) g2.fillRect(x + w - t, y, t, h);
			} finally {
				g2.dispose();
			}
		}
	}
}
