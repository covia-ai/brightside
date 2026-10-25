package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import com.formdev.flatlaf.util.UIScale;

import brightside.KeyCheck;

/**
 * A <b>Test</b> button beside an API key, and the answer under it: one tiny
 * model call ({@link KeyCheck}) run off the event thread, then "Works: … replied
 * in 0.8 s" or the reason it doesn't, in plain words, with any likely cause or
 * next step on the line below. What to test — which model, which key — the
 * owner's {@code prepare} reads from its fields on the event thread when the
 * button is pressed, and returns the call to make off it.
 */
@SuppressWarnings("serial")
public final class KeyTest extends JPanel {

	private final JButton button = Buttons.small("Test");
	private final Answer answer;
	private final Answer hint;
	private final Supplier<Supplier<KeyCheck.Result>> prepare;
	private boolean wanted = true;
	private boolean running;

	/** {@code width}: where the answer wraps, in unscaled pixels; {@code centred} for a centred column. */
	public KeyTest(Supplier<Supplier<KeyCheck.Result>> prepare, int width, boolean centred) {
		this.prepare = prepare;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		float x = centred ? Component.CENTER_ALIGNMENT : Component.LEFT_ALIGNMENT;
		int align = centred ? SwingConstants.CENTER : SwingConstants.LEFT;
		answer = new Answer(width, align);
		hint = Styles.classes(new Answer(width, align), Styles.SMALL);
		button.setToolTipText("Send one tiny request to check the key and model work (costs a fraction of a cent)");
		button.addActionListener(e -> run());
		for (JComponent c : new JComponent[] { button, answer, hint }) c.setAlignmentX(x);
		add(button);
		add(Box.createVerticalStrut(8));
		add(answer);
		add(Box.createVerticalStrut(4));
		add(hint);
	}

	/** Enables the button, unless a test is already running. */
	public void setTestEnabled(boolean on) {
		wanted = on;
		button.setEnabled(on && !running);
	}

	/** Clears the last answer: the key or model it described has changed. */
	public void reset() {
		if (running) return;
		answer.show(null, Tone.MUTED, null);
		hint.show(null, Tone.MUTED, null);
	}

	private void run() {
		Supplier<KeyCheck.Result> test = prepare.get();
		running = true;
		button.setEnabled(false);
		answer.show("Testing…", Tone.MUTED, "loader-circle");
		hint.show(null, Tone.MUTED, null);
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
				answer.show(r.message(), r.ok() ? Tone.SUCCESS : Tone.ERROR, r.ok() ? "circle-check" : "circle-alert");
				hint.show(r.hint(), Tone.MUTED, null);
			}
		}.execute();
	}

	/**
	 * One line of the answer, its icon right beside the text and both in a tone
	 * that follows the theme. Text too wide for {@code width} wraps; the text is
	 * always escaped, so HTML is used only to wrap.
	 */
	private static final class Answer extends JLabel {

		private final int width;
		private final int align;
		private Tone tone = Tone.MUTED;

		Answer(int width, int align) {
			super(" ");
			this.width = width;
			this.align = align;
			setHorizontalAlignment(align);
			setIconTextGap(6);
		}

		/** {@code text} null or blank: an empty line that keeps its height. */
		void show(String text, Tone tone, String icon) {
			this.tone = tone;
			setIcon(icon == null ? null : Lucide.icon(icon, 14, () -> this.tone.colour()));
			if (text == null || text.isBlank()) {
				setText(" ");
			} else if (getFontMetrics(getFont()).stringWidth(text) <= UIScale.scale(width)) {
				Labels.plain(this);
				setText(text);
			} else {
				putClientProperty("html.disable", null);
				setText(Labels.wrap(Labels.escape(text), width, align));
			}
			repaint();
		}

		@Override
		public Color getForeground() {
			return (tone != null) ? tone.colour() : super.getForeground();
		}
	}
}
