package brightside.ui.components;

import java.awt.Color;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.ColorFunctions;

/**
 * The colours of the current theme, read from the UI defaults FlatLaf resolved
 * from the theme and from {@code brightside/ui/FlatLaf.properties} — never
 * cached, so a component that paints with them follows a theme change.
 *
 * <p>Prefer a {@linkplain Styles style class} where one fits (a muted label, an
 * error note): the theme then owns the colour outright. Read a colour here for
 * custom painting and for states that switch at runtime.
 */
public final class Theme {

	private Theme() {
	}

	/** The Brightside accent ({@code @accentColor}). */
	public static Color accent() {
		return FlatUIUtils.getUIColor("Component.accentColor", 0x7C6CF5);
	}

	/** Ordinary text. */
	public static Color foreground() {
		return FlatUIUtils.getUIColor("Label.foreground", 0xDDDDDD);
	}

	/** Secondary text: captions, metadata, hints. */
	public static Color muted() {
		return FlatUIUtils.getUIColor("Label.disabledForeground", 0x888888);
	}

	/** Hairlines and separators. */
	public static Color line() {
		return FlatUIUtils.getUIColor("Separator.foreground", 0x555555);
	}

	/** The panel background the screens sit on. */
	public static Color panel() {
		return FlatUIUtils.getUIColor("Panel.background", 0x1E1E1E);
	}

	/** An elevated surface a step off the panel: bubbles, cards, chips. */
	public static Color surface() {
		return FlatUIUtils.getUIColor("Brightside.surface", 0x2B2B2B);
	}

	public static Color error() {
		return FlatUIUtils.getUIColor("Brightside.error", 0xE55353);
	}

	public static Color success() {
		return FlatUIUtils.getUIColor("Brightside.success", 0x3FB950);
	}

	public static Color warning() {
		return FlatUIUtils.getUIColor("Brightside.warning", 0xE58A3A);
	}

	public static boolean isDark() {
		return FlatLaf.isLafDark();
	}

	/** {@code base} moved {@code amount} (0–1) of the way towards {@code towards}. */
	public static Color blend(Color base, Color towards, float amount) {
		return ColorFunctions.mix(towards, base, amount);
	}

	/** {@code colour} at {@code alpha} (0–1) opacity. */
	public static Color fade(Color colour, float alpha) {
		return ColorFunctions.fade(colour, alpha);
	}
}
