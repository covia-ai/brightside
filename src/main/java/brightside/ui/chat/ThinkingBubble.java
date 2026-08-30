package brightside.ui.chat;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.Timer;

import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * Compact assistant-side progress bubble for an in-flight turn.
 *
 * <p>The summary is deliberately supplied by real send/cycle events. It must
 * never be manufactured from elapsed time: once Covia exposes its live agent
 * tap, safe narration and tool-name events can update this same component.
 */
@SuppressWarnings("serial")
final class ThinkingBubble extends Card {

	private static final long SHOW_ELAPSED_AFTER_MS = 8_000;

	private final TypingIndicator indicator = new TypingIndicator(Theme.muted());
	private final JLabel summary = Styles.classes(Labels.text(""), Styles.SMALL);
	private final JLabel elapsed = Labels.small("");
	private final Timer clock;
	private long startedAt;

	ThinkingBubble(String initialSummary) {
		super(20);
		setLayout(new BorderLayout(10, 0));
		setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

		setSummary(initialSummary);
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
}
