package brightside.ui.components;

import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import brightside.KeyCheck;

/**
 * A <b>Test</b> button beside an API key, and the answer under it: one tiny
 * model call ({@link KeyCheck}) run off the event thread, then "Works: … replied
 * in 0.8 s" or the reason it doesn't, in plain words. What to test — which
 * model, which key — the owner's {@code prepare} reads from its fields on the
 * event thread when the button is pressed, and returns the call to make off it.
 */
@SuppressWarnings("serial")
public final class KeyTest extends JPanel {

	private final JButton button = Buttons.small("Test");
	private final Answer answer;
	private final Supplier<Supplier<KeyCheck.Result>> prepare;
	private boolean wanted = true;
	private boolean running;

	/** {@code width}: where the answer wraps, in unscaled pixels; {@code centred} for a centred column. */
	public KeyTest(Supplier<Supplier<KeyCheck.Result>> prepare, int width, boolean centred) {
		super(new FlowLayout(centred ? FlowLayout.CENTER : FlowLayout.LEFT, 0, 0));
		this.prepare = prepare;
		this.answer = new Answer(width, centred ? SwingConstants.CENTER : SwingConstants.LEFT);
		setOpaque(false);
		button.setToolTipText("Send one tiny request to check the key and model work (costs a fraction of a cent)");
		button.addActionListener(e -> run());
		add(button);
		add(javax.swing.Box.createHorizontalStrut(10));
		add(answer);
	}

	/** Enables the button, unless a test is already running. */
	public void setTestEnabled(boolean on) {
		wanted = on;
		button.setEnabled(on && !running);
	}

	/** Clears the last answer: the key or model it described has changed. */
	public void reset() {
		if (!running) answer.show(" ", Tone.MUTED, null);
	}

	private void run() {
		Supplier<KeyCheck.Result> test = prepare.get();
		running = true;
		button.setEnabled(false);
		answer.show("Testing…", Tone.MUTED, "loader-circle");
		new SwingWorker<KeyCheck.Result, Void>() {
			@Override
			protected KeyCheck.Result doInBackground() {
				return test.get();
			}

			@Override
			protected void done() {
				running = false;
				button.setEnabled(wanted);
				KeyCheck.Result r;
				try {
					r = get();
				} catch (Exception e) {
					answer.show("The test couldn't run: " + e.getMessage(), Tone.ERROR, "circle-alert");
					return;
				}
				if (r.ok()) answer.show(r.message(), Tone.SUCCESS, "circle-check");
				else answer.show(r.message(), Tone.ERROR, "circle-alert");
			}
		}.execute();
	}

	/** The answer: wrapped text and an icon, both in a tone that follows the theme. */
	private static final class Answer extends JLabel {

		private final int width;
		private final int align;
		private Tone tone = Tone.MUTED;

		Answer(int width, int align) {
			super(" ");
			this.width = width;
			this.align = align;
			// HTML only to wrap: the text itself is always escaped (show()).
			setIconTextGap(6);
		}

		void show(String text, Tone tone, String icon) {
			this.tone = tone;
			setIcon(icon == null ? null : Lucide.icon(icon, 14, () -> this.tone.colour()));
			setText(" ".equals(text) ? " " : Labels.wrap(Labels.escape(text), width, align));
			repaint();
		}

		@Override
		public Color getForeground() {
			return (tone != null) ? tone.colour() : super.getForeground();
		}
	}
}
