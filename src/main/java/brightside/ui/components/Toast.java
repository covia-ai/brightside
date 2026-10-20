package brightside.ui.components;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.util.Animator;
import com.formdev.flatlaf.util.UIScale;

/**
 * A word of confirmation that comes and goes by itself — "Copied" — shown
 * just above the control that did it, so it is seen where the eye already
 * is. For an outcome nobody needs to act on or read again; anything lasting
 * belongs in the screen itself. One at a time in a window: a new one replaces
 * the last.
 */
@SuppressWarnings("serial")
public final class Toast extends Card {

	private static final int HOLD_MS = 1400;
	private static final int FADE_MS = 220;

	private final Timer hold;
	private float alpha = 1f;

	private Toast(String text, String icon, Tone tone) {
		super(999);
		fill(Theme::surface);
		outline(Theme::line);
		setLayout(new BorderLayout());
		setBorder(new FlatEmptyBorder(5, 12, 5, 14));
		StatusLabel label = new StatusLabel(text, icon, tone);
		Styles.classes(label, Styles.SMALL);
		add(label);
		hold = new Timer(HOLD_MS, e -> fade());
		hold.setRepeats(false);
	}

	/** "Copied", with a tick, just above {@code near}. */
	public static void copied(JComponent near) {
		show(near, "Copied", "check", Tone.SUCCESS);
	}

	/** {@code text}, with {@code icon} in {@code tone}, just above {@code near} (below it at the window's top). */
	public static void show(JComponent near, String text, String icon, Tone tone) {
		JRootPane root = SwingUtilities.getRootPane(near);
		if (root == null || !near.isShowing()) return;
		JLayeredPane layers = root.getLayeredPane();
		for (Component c : layers.getComponentsInLayer(JLayeredPane.POPUP_LAYER)) {
			if (c instanceof Toast t) t.dismiss();
		}
		Toast toast = new Toast(text, icon, tone);
		Dimension size = toast.getPreferredSize();
		int gap = UIScale.scale(6);
		Point top = SwingUtilities.convertPoint(near, near.getWidth() / 2, 0, layers);
		int x = Math.max(gap, Math.min(top.x - size.width / 2, layers.getWidth() - size.width - gap));
		int y = top.y - size.height - gap;
		if (y < gap) y = top.y + near.getHeight() + gap;
		toast.setBounds(x, y, size.width, size.height);
		layers.add(toast, JLayeredPane.POPUP_LAYER);
		layers.repaint(toast.getBounds());
		toast.hold.start();
	}

	private void fade() {
		if (!Animator.useAnimation()) {
			dismiss();
			return;
		}
		new Animator(FADE_MS, fraction -> {
			alpha = 1f - fraction;
			repaint();
		}, this::dismiss).start();
	}

	private void dismiss() {
		hold.stop();
		if (getParent() == null) return;
		Rectangle bounds = getBounds();
		Container parent = getParent();
		parent.remove(this);
		parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	@Override
	public void paint(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
			super.paint(g2);
		} finally {
			g2.dispose();
		}
	}
}
