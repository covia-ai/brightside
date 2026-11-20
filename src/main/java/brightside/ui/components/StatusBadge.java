package brightside.ui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JLabel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * A status as a pill: icon plus label plus colour, never colour alone. The
 * named statuses read the same wherever they appear (a job, a tool step, a
 * Record row, an Inbox request): {@link #running}, {@link #signed},
 * {@link #waiting} and {@link #refused}, the last always naming the capability
 * that was missing ("Refused: billing.write") and calm, never red. Colours are
 * the theme's {@code Brightside.status.*} keys, read when painting.
 */
@SuppressWarnings("serial")
public final class StatusBadge extends JLabel {

	private static final int ICON = 12;
	private final Tone tone;

	/** {@code icon}: a {@link Lucide} name, or null for none. */
	public StatusBadge(String text, String icon, Tone tone) {
		super(text);
		this.tone = tone;
		Styles.classes(this, Styles.SMALL, Styles.STRONG);
		if (icon != null) setIcon(Lucide.icon(icon, ICON, tone::colour));
		setIconTextGap(UIScale.scale(5));
		setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 9));
		setOpaque(false);
		getAccessibleContext().setAccessibleName(text);
	}

	public static StatusBadge running() {
		return new StatusBadge("Running", "loader-circle", Tone.RUNNING);
	}

	public static StatusBadge signed() {
		return new StatusBadge("Signed", "check", Tone.SIGNED);
	}

	public static StatusBadge waiting() {
		return new StatusBadge("Waiting on you", "hourglass", Tone.WAITING);
	}

	/** A job or tool call that ran out of time and will not go on by itself. */
	public static StatusBadge timedOut() {
		return new StatusBadge("Timed out", "clock", Tone.TIMED_OUT);
	}

	/** Refused, naming the capability that was missing; the capability is required. */
	public static StatusBadge refused(String capability) {
		if (capability == null || capability.isBlank()) {
			throw new IllegalArgumentException("A refusal names the capability that was missing");
		}
		return new StatusBadge("Refused: " + capability, "ban", Tone.REFUSED);
	}

	public Tone tone() {
		return tone;
	}

	@Override
	public Color getForeground() {
		return (tone != null) ? tone.text() : super.getForeground();
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			float arc = getHeight();
			g2.setColor(tone.tint());
			g2.fill(FlatUIUtils.createComponentRectangle(0, 0, getWidth(), getHeight(), arc));
			Color border = tone.border();
			if (border != null) {
				g2.setColor(border);
				FlatUIUtils.paintOutline(g2, 0, 0, getWidth(), getHeight(), UIScale.scale(1f), arc);
			}
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
