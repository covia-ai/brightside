package brightside.ui.components;

import java.awt.Color;
import java.util.function.Supplier;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * The app's icon set: <a href="https://lucide.dev">Lucide</a> (ISC licence,
 * {@code icons/lucide/LICENSE}), rendered by FlatLaf Extras' {@link FlatSVGIcon}
 * — vector, so crisp at every DPI, and tinted through a colour filter so one
 * file serves muted, foreground and accent states. Lucide draws with
 * {@code currentColor}; the filter maps whatever colour that resolves to onto
 * the requested one.
 */
public final class Lucide {

	private Lucide() {
	}

	/** The icon {@code name} ({@code icons/lucide/<name>.svg}) at {@code size} pixels square in a fixed {@code color}. */
	public static FlatSVGIcon icon(String name, int size, Color color) {
		return icon(name, size, () -> color);
	}

	/**
	 * The icon in a colour read afresh on every paint — pass a {@link Theme}
	 * accessor ({@code Theme::muted}) and the icon follows a theme change.
	 */
	public static FlatSVGIcon icon(String name, int size, Supplier<Color> color) {
		FlatSVGIcon icon = new FlatSVGIcon("icons/lucide/" + name + ".svg", size, size);
		icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color.get()));
		return icon;
	}
}
