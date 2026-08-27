package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Compact assistant-side progress bubble for an in-flight turn.
 *
 * <p>The summary is deliberately supplied by real send/cycle events. It must
 * never be manufactured from elapsed time: once Covia exposes its live agent
 * tap, safe narration and tool-name events can update this same component.
 */
@SuppressWarnings("serial")
final class ThinkingBubble extends JPanel {

	private static final long SHOW_ELAPSED_AFTER_MS = 8_000;

	private final TypingIndicator indicator = new TypingIndicator(ChatStyle.muted());
	private final JLabel summary = new JLabel();
	private final JLabel elapsed = new JLabel();
	private final Timer clock;
	private long startedAt;

	ThinkingBubble(String initialSummary) {
		super(new BorderLayout(10, 0));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

		summary.setForeground(ChatStyle.foreground());
		summary.putClientProperty("FlatLaf.styleClass", "small");
		setSummary(initialSummary);

		elapsed.setForeground(ChatStyle.muted());
		elapsed.putClientProperty("FlatLaf.styleClass", "small");
		elapsed.setVisible(false);

		add(indicator, BorderLayout.WEST);
		add(summary, BorderLayout.CENTER);
		add(elapsed, BorderLayout.EAST);

		clock = new Timer(1_000, e -> updateElapsed());
	}

	void start() {
		startedAt = System.nanoTime();
		elapsed.setVisible(false);
		elapsed.setText("");
		indicator.start();
		clock.start();
	}

	void stop() {
		clock.stop();
		indicator.stop();
	}

	/** Replace the brief display-safe description of the current real activity. */
	void setSummary(String text) {
		if (text == null || text.isBlank()) return;
		summary.setText(text);
		revalidate();
		repaint();
	}

	private void updateElapsed() {
		long ms = (System.nanoTime() - startedAt) / 1_000_000;
		if (ms < SHOW_ELAPSED_AFTER_MS) return;
		long seconds = ms / 1_000;
		elapsed.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
		if (!elapsed.isVisible()) {
			elapsed.setVisible(true);
			revalidate();
		}
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(ChatStyle.assistantBg());
		g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
		g2.dispose();
		super.paintComponent(g);
	}
}
