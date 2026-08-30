package brightside.ui.chat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;

/** A three-dot "typing…" animation shown on the assistant side while a reply is pending. */
@SuppressWarnings("serial")
final class TypingIndicator extends JComponent {
	private static final int COUNT = 3;
	private static final int DOT = 8;
	private static final int GAP = 6;

	private final Color color;
	private final Timer timer;
	private int phase;

	TypingIndicator(Color color) {
		this.color = color;
		setOpaque(false);
		setPreferredSize(new Dimension(COUNT * DOT + (COUNT - 1) * GAP, DOT + 6));
		// COUNT+1 phases: each dot lifts in turn, then a brief rest.
		timer = new Timer(280, e -> {
			phase = (phase + 1) % (COUNT + 1);
			repaint();
		});
	}

	void start() {
		phase = 0;
		timer.start();
	}

	void stop() {
		timer.stop();
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int y = (getHeight() - DOT) / 2;
		for (int i = 0; i < COUNT; i++) {
			boolean active = (i == phase);
			int alpha = active ? 235 : 110;
			int lift = active ? 2 : 0;
			g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
			g2.fillOval(i * (DOT + GAP), y - lift, DOT, DOT);
		}
		g2.dispose();
	}
}
