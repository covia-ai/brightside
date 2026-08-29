package covia.brightside.ui;

import java.awt.Color;

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

	/** The icon {@code name} ({@code icons/lucide/<name>.svg}) at {@code size} pixels square in {@code color}. */
	public static FlatSVGIcon icon(String name, int size, Color color) {
		FlatSVGIcon icon = new FlatSVGIcon("icons/lucide/" + name + ".svg", size, size);
		icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color));
		return icon;
	}
}
