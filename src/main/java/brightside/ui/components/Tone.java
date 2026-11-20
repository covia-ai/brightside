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
	MUTED(Styles.MUTED, Theme::muted, "Brightside.muted.tint"),
	/** Running, queued: a blue dot on a light blue ground. */
	RUNNING(null, () -> status("running", "icon"), "Brightside.status.running.background", "running"),
	/** Recorded and signed, or answered: a check on a light purple ground. */
	SIGNED(null, () -> status("signed", "icon"), "Brightside.status.signed.background", "signed"),
	/** Needs you: an Inbox request open, a job paused for approval. Navy on amber. */
	WAITING(null, () -> status("waiting", "icon"), "Brightside.status.waiting.background", "waiting"),
	/**
	 * A capability was missing: the mechanism, never danger, so calm and never
	 * red, with an outline; its label always names the capability.
	 */
	REFUSED(null, () -> status("refused", "icon"), "Brightside.status.refused.background", "refused");

	private final String style;
	private final Supplier<Color> colour;
	private final String tintKey;
	/** For the named status badges: the {@code Brightside.status.<name>.*} keys; null for the older tones. */
	private final String status;

	Tone(String style, Supplier<Color> colour, String tintKey) {
		this(style, colour, tintKey, null);
	}

	Tone(String style, Supplier<Color> colour, String tintKey, String status) {
		this.style = style;
		this.colour = colour;
		this.tintKey = tintKey;
		this.status = status;
	}

	private static Color status(String name, String part) {
		Color c = UIManager.getColor("Brightside.status." + name + "." + part);
		return (c != null) ? c : Theme.foreground();
	}

	/** True for the named statuses (Running, Signed, Waiting on you, Refused), which a {@link StatusBadge} shows. */
	public boolean isStatus() {
		return status != null;
	}

	/** The colour of a badge's label: the tone itself, or for a named status its own text colour (navy on amber). */
	public Color text() {
		return (status != null) ? status(status, "foreground") : colour();
	}

	/** An outline around a badge in this tone, or null for none: only Refused has one. */
	public Color border() {
		return (status != null) ? UIManager.getColor("Brightside.status." + status + ".border") : null;
	}

	/** The tone's {@linkplain Styles style class}, for text that wears it; null for the named statuses, which paint their own. */
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
