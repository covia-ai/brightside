package brightside.ui.components;

import java.awt.Color;
import java.util.function.Supplier;

import javax.swing.UIManager;

/**
 * The tones a status, badge or chip can take, each a colour for its text and
 * icon and a faint ground of the same colour behind a {@link Badge} or a
 * {@link Chip}. Both are the theme's: the colours come through {@link Theme}
 * and the grounds are the {@code Brightside.*.tint} UI defaults in
 * {@code brightside/ui/FlatLaf.properties}, read when painting so they follow
 * a theme change.
 */
public enum Tone {

	/** Brightside's own: a kind of thing, something running, something new. */
	ACCENT(Styles.ACCENT, Theme::accentText, "Brightside.accent.tint"),
	SUCCESS(Styles.SUCCESS, Theme::success, "Brightside.success.tint"),
	WARNING(Styles.WARNING, Theme::warning, "Brightside.warning.tint"),
	ERROR(Styles.ERROR, Theme::error, "Brightside.error.tint"),
	/** Nothing to report: not run yet, cancelled, paused. */
	MUTED(Styles.MUTED, Theme::muted, "Brightside.muted.tint");

	private final String style;
	private final Supplier<Color> colour;
	private final String tintKey;

	Tone(String style, Supplier<Color> colour, String tintKey) {
		this.style = style;
		this.colour = colour;
		this.tintKey = tintKey;
	}

	/** The tone's {@linkplain Styles style class}, for text that wears it. */
	public String style() {
		return style;
	}

	/** The tone as text or an icon. */
	public Color colour() {
		return colour.get();
	}

	/** The tone's faint ground: behind a badge, a chip. */
	public Color tint() {
		Color tint = UIManager.getColor(tintKey);
		return (tint != null) ? tint : Theme.fade(colour(), 0.16f);
	}
}
