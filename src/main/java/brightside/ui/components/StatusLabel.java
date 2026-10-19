package brightside.ui.components;

import java.awt.Color;

import javax.swing.JLabel;

/**
 * Text in a {@link Tone}, with an optional icon before it in the same tone:
 * how a run went, whether a bot is connected. The colour is read when
 * painting, so it follows a theme change and a change of tone. Shows its text
 * literally, like every {@link Labels} label.
 */
@SuppressWarnings("serial")
public class StatusLabel extends JLabel {

	private Tone tone;

	/** {@code icon}: a {@link Lucide} name, or null for none. */
	public StatusLabel(String text, String icon, Tone tone) {
		this(text, icon, tone, 14);
	}

	StatusLabel(String text, String icon, Tone tone, int iconSize) {
		super(text);
		Labels.plain(this);
		this.tone = tone;
		if (icon != null) setIcon(Lucide.icon(icon, iconSize, () -> this.tone.colour()));
		setIconTextGap(6);
	}

	public Tone tone() {
		return tone;
	}

	/** Moves the label to another tone, its icon with it. */
	public StatusLabel tone(Tone tone) {
		this.tone = tone;
		repaint();
		return this;
	}

	@Override
	public Color getForeground() {
		// Null only while JLabel's constructor installs the defaults.
		return (tone != null) ? tone.colour() : super.getForeground();
	}
}
