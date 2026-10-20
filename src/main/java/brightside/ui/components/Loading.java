package brightside.ui.components;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.Timer;

/**
 * Something is on its way: a turning loader beside a quiet line — "Loading
 * folder…". It turns only while it is on screen, so one left behind in a
 * hidden pane costs nothing.
 */
@SuppressWarnings("serial")
public final class Loading extends JLabel {

	/** A twelfth of a turn every 80 ms: about a turn a second, brisk without being busy. */
	private static final int STEP_MS = 80;
	private static final double STEP = Math.PI / 6;

	private final Timer timer;
	private double angle;

	public Loading(String text) {
		super(text);
		Labels.plain(this);
		Styles.classes(this, Styles.SMALL, Styles.MUTED);
		setIcon(new Turning(Lucide.icon("loader-circle", 16, Theme::muted)));
		setIconTextGap(8);
		timer = new Timer(STEP_MS, e -> {
			angle = (angle + STEP) % (2 * Math.PI);
			repaint();
		});
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
			if (isShowing()) timer.start();
			else timer.stop();
		});
	}

	/** {@code icon} drawn turned through the label's current angle about its centre. */
	private final class Turning implements Icon {
		private final Icon icon;

		Turning(Icon icon) {
			this.icon = icon;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.rotate(angle, x + getIconWidth() / 2.0, y + getIconHeight() / 2.0);
				icon.paintIcon(c, g2, x, y);
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return icon.getIconWidth();
		}

		@Override
		public int getIconHeight() {
			return icon.getIconHeight();
		}
	}
}
