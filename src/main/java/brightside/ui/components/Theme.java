package brightside.ui.components;

import java.awt.Color;

import javax.swing.UIManager;

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

	/** The contrast white text reaches on {@link #accentDark}: WCAG's AAA for body text. */
	public static final double AAA_CONTRAST = 7.0;
	/** The contrast {@link #accentText} reaches on its ground: WCAG's AA for body text. */
	public static final double AA_CONTRAST = 4.5;
	private static Color darkFor, darkOf;

	/**
	 * The accent as a background for light text: darkened in HSL, hue and
	 * saturation kept, until white reads on it at {@value #AAA_CONTRAST}:1 —
	 * so every accent on offer, and any chosen, comes out deep. The user's
	 * bubbles paint with it; the accent itself stays for selection, focus and
	 * the main buttons. Painted on the event thread, so memoised without a lock.
	 */
	public static Color accentDark() {
		Color a = accent();
		if (!a.equals(darkFor)) {
			darkOf = darkenFor(a, Color.WHITE, AAA_CONTRAST);
			darkFor = a;
		}
		return darkOf;
	}

	/**
	 * The accent as text or an icon on the panel's accent tint (the tinted
	 * button, the navigation's selected look): the accent moved in lightness
	 * — lighter on a dark theme, darker on a light one, hue and saturation
	 * kept — until it reads there at {@value #AA_CONTRAST}:1. Put into the
	 * defaults by the look-and-feel setup; worked out here when it is not
	 * there.
	 */
	public static Color accentText() {
		Color put = UIManager.getColor("Brightside.accent.text");
		if (put != null) return put;
		return readableOn(accent(), panel(), AA_CONTRAST);
	}

	/**
	 * {@code base} moved in lightness away from {@code ground} — lighter on a
	 * dark ground, darker on a light one, hue and saturation kept — a step at
	 * a time until it reads there at {@code contrast}:1, or reaches the end.
	 */
	public static Color readableOn(Color base, Color ground, double contrast) {
		// Below this luminance a ground contrasts more with white than with black.
		boolean lighten = luminance(ground) < 0.18;
		Color c = base;
		for (int i = 0; i < 100 && contrast(ground, c) < contrast; i++) {
			c = lighten ? ColorFunctions.lighten(c, 0.01f) : ColorFunctions.darken(c, 0.01f);
		}
		return c;
	}

	/** {@code base} darkened a step at a time until {@code text} reads on it at {@code contrast}:1, or it is black. */
	static Color darkenFor(Color base, Color text, double contrast) {
		Color c = base;
		for (int i = 0; i < 100 && contrast(text, c) < contrast; i++) {
			c = ColorFunctions.darken(c, 0.01f);
		}
		return c;
	}

	/** The WCAG contrast ratio between two colours: 1 (the same) to 21 (black on white). */
	public static double contrast(Color a, Color b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	/** Relative luminance as WCAG defines it, 0 (black) to 1 (white). */
	private static double luminance(Color c) {
		return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
	}

	private static double linear(int channel) {
		double s = channel / 255.0;
		return (s <= 0.03928) ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
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

	/** Behind code in rendered Markdown: a further step off the surface it sits on. */
	public static Color codeBackground() {
		return FlatUIUtils.getUIColor("Brightside.code.background", 0x3A3A3A);
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
