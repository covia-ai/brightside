package brightside.ui.components;

import java.awt.Graphics;
import java.awt.Graphics2D;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * A word or two on its tone's faint ground, rounded at the ends, with an
 * optional icon: how often an event repeats, that a view is a preview. Small
 * type; the text is shown literally.
 */
@SuppressWarnings("serial")
public class Chip extends StatusLabel {

	/** {@code icon}: a {@link Lucide} name, or null for none. */
	public Chip(String text, String icon, Tone tone) {
		super(text, icon, tone, 12);
		Styles.classes(this, Styles.SMALL);
		setIconTextGap(4);
		setBorder(new FlatEmptyBorder(2, (icon != null) ? 8 : 9, 2, 9));
		setOpaque(false);
	}

	/** An accent chip without an icon. */
	public Chip(String text) {
		this(text, null, Tone.ACCENT);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(tone().tint());
			g2.fill(FlatUIUtils.createComponentRectangle(0, 0, getWidth(), getHeight(), getHeight()));
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
