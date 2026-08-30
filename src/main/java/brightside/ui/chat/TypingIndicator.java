package brightside.ui.chat;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.Timer;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Theme;

/** A three-dot "typing…" animation shown on the assistant side while a reply is pending. */
@SuppressWarnings("serial")
final class TypingIndicator extends JComponent {
	private static final int COUNT = 3;
	private static final int DOT = 8;
	private static final int GAP = 6;

	private final Timer timer;
	private int phase;

	TypingIndicator() {
		setOpaque(false);
		setPreferredSize(new Dimension(UIScale.scale(COUNT * DOT + (COUNT - 1) * GAP), UIScale.scale(DOT + 6)));
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
		try {
			FlatUIUtils.setRenderingHints(g2);
			int dot = UIScale.scale(DOT);
			int gap = UIScale.scale(GAP);
			int y = (getHeight() - dot) / 2;
			for (int i = 0; i < COUNT; i++) {
				boolean active = (i == phase);
				g2.setColor(Theme.fade(Theme.muted(), active ? 0.92f : 0.43f));
				g2.fillOval(i * (dot + gap), y - (active ? UIScale.scale(2) : 0), dot, dot);
			}
		} finally {
			g2.dispose();
		}
	}
}
