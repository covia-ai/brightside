package brightside.ui.components;

import javax.swing.BorderFactory;
import javax.swing.border.Border;

import com.formdev.flatlaf.util.UIScale;

/** Borders that carry the theme's hairline. Plain padding is {@link BorderFactory#createEmptyBorder}. */
public final class Borders {

	private Borders() {
	}

	/**
	 * A hairline in the theme's separator colour on the chosen sides (each flag
	 * true for a line), scaled with the UI.
	 */
	public static Border hairline(boolean top, boolean left, boolean bottom, boolean right) {
		int t = UIScale.scale(1);
		return BorderFactory.createMatteBorder(top ? t : 0, left ? t : 0, bottom ? t : 0, right ? t : 0, Theme.line());
	}

	/** A hairline above. */
	public static Border hairlineTop() {
		return hairline(true, false, false, false);
	}

	/** A hairline on the trailing edge — the edge of a side pane. */
	public static Border hairlineRight() {
		return hairline(false, false, false, true);
	}
}
