package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.Timer;

import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * Compact assistant-side progress bubble for an in-flight turn.
 *
 * <p>The summary is deliberately supplied by real send/cycle events. It must
 * never be manufactured from elapsed time: once Covia exposes its live agent
 * tap, safe narration and tool-name events can update this same component.
 *
 * <p>A reply has no deadline — a turn takes as long as it takes — so once one
 * has run for a while the bubble offers a small stop control. What stopping
 * means is the owner's call ({@code ChatPanel} confirms, then cancels the chat
 * job); this component only shows the control and reports the press.
 */
@SuppressWarnings("serial")
final class ThinkingBubble extends Card {

	private static final long SHOW_ELAPSED_AFTER_MS = 8_000;
	/** A turn this long is worth a way out. */
	private static final long SHOW_STOP_AFTER_MS = 20_000;

	private final TypingIndicator indicator = new TypingIndicator();
	private final JLabel summary = Styles.classes(Labels.text(""), Styles.SMALL);
	private final JLabel elapsed = Labels.small("");
	private final Component stopGap = Box.createHorizontalStrut(6);
	private final JButton stop;
	private final Timer clock;
	private long startedAt;

	/**
	 * @param onStop called on the event thread when the stop control is pressed
	 */
	ThinkingBubble(String initialSummary, Runnable onStop) {
		super(20);
		setLayout(new BorderLayout(10, 0));
		setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

		setSummary(initialSummary);
		elapsed.setVisible(false);

		stop = Buttons.icon(Lucide.icon("x", 14, Theme::muted), "Stop waiting for this reply");
		// Compact enough to sit on the text line without growing the bubble, and
		// never the focus owner: the composer keeps it either way.
		Styles.style(stop, "toolbar.margin: 1,1,1,1");
		stop.setFocusable(false);
		stop.setVisible(false);
		stopGap.setVisible(false);
		stop.addActionListener(e -> onStop.run());

		Box trailing = Box.createHorizontalBox();
		trailing.add(elapsed);
		trailing.add(stopGap);
		trailing.add(stop);

		add(indicator, BorderLayout.WEST);
		add(summary, BorderLayout.CENTER);
		add(trailing, BorderLayout.EAST);

		clock = new Timer(1_000, e -> tick());
	}

	void start() {
		startedAt = System.nanoTime();
		elapsed.setVisible(false);
		elapsed.setText("");
		stop.setVisible(false);
		stopGap.setVisible(false);
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

	private void tick() {
		long ms = (System.nanoTime() - startedAt) / 1_000_000;
		if (ms < SHOW_ELAPSED_AFTER_MS) return;
		long seconds = ms / 1_000;
		elapsed.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
		boolean changed = false;
		if (!elapsed.isVisible()) {
			elapsed.setVisible(true);
			changed = true;
		}
		if (ms >= SHOW_STOP_AFTER_MS && !stop.isVisible()) {
			stopGap.setVisible(true);
			stop.setVisible(true);
			changed = true;
		}
		if (changed) revalidate();
	}
}
