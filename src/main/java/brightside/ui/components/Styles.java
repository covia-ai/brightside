package brightside.ui.components;

import javax.swing.JComponent;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * FlatLaf style classes: named looks the theme defines once (in
 * {@code brightside/ui/FlatLaf.properties}, alongside FlatLaf's own) and a
 * component wears by name, so colour and type stay the theme's decision and
 * survive a theme change. Classes combine: {@code classes(label, SMALL, MUTED)}.
 */
public final class Styles {

	// Tone (colour).
	public static final String MUTED = "muted";
	public static final String ACCENT = "accent";
	public static final String ERROR = "error";
	public static final String SUCCESS = "success";
	public static final String WARNING = "warning";

	// Type. small/mini/medium/large/monospaced are FlatLaf's; the rest are ours.
	public static final String SMALL = "small";
	public static final String MINI = "mini";
	public static final String MEDIUM = "medium";
	public static final String LARGE = "large";
	public static final String MONOSPACED = "monospaced";
	public static final String STRONG = "strong";
	public static final String SECTION = "section";
	public static final String TITLE = "title";

	// Buttons.
	public static final String PRIMARY = "primary";
	public static final String SECONDARY = "secondary";
	public static final String LINK = "link";

	private Styles() {
	}

	/** Sets the component's style classes (replacing any), or clears them when none are given. */
	public static <C extends JComponent> C classes(C component, String... classes) {
		component.putClientProperty(FlatClientProperties.STYLE_CLASS,
			classes.length == 0 ? null : String.join(" ", classes));
		return component;
	}

	/** Adds style classes to whatever the component already wears. */
	public static <C extends JComponent> C add(C component, String... classes) {
		Object current = component.getClientProperty(FlatClientProperties.STYLE_CLASS);
		String joined = String.join(" ", classes);
		component.putClientProperty(FlatClientProperties.STYLE_CLASS,
			(current instanceof String s && !s.isBlank()) ? s + " " + joined : joined);
		return component;
	}

	/**
	 * A one-off style in FlatLaf's CSS-like syntax (for example {@code "font: bold -3"})
	 * for a look no class covers; null clears it.
	 */
	public static <C extends JComponent> C style(C component, String css) {
		component.putClientProperty(FlatClientProperties.STYLE, css);
		return component;
	}
}
