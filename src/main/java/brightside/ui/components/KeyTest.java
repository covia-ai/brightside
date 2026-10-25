package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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

	/**
	 * {@code width}: the widest the answer runs before wrapping, in unscaled
	 * pixels; narrower when the space it is given is narrower. {@code centred}
	 * for a centred column.
	 */
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
		// Wrap to the space actually given, again whenever it, or anything
		// around it, changes size.
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				rewrap();
			}
		});
		addHierarchyBoundsListener(new java.awt.event.HierarchyBoundsAdapter() {
			@Override
			public void ancestorResized(java.awt.event.HierarchyEvent e) {
				rewrap();
			}
		});
	}

	private void rewrap() {
		answer.render();
		hint.render();
	}

	/**
	 * The width the answer may use: from this panel's left edge to the inner
	 * right edge of the container holding it. Measured on the container, not on
	 * this panel, because this panel grows to fit what it holds — measuring
	 * it would let a long line widen the panel past what is visible.
	 */
	int room() {
		Container outer = getParent();
		if (outer == null || outer.getWidth() <= 0) return -1;
		return outer.getWidth() - getX() - outer.getInsets().right;
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
	 * that follows the theme. Text wider than the room it has wraps: the room
	 * is what is visible beside the panel ({@link KeyTest#room()}), capped at
	 * {@code width}. The text is always
	 * escaped, so HTML is used only to wrap.
	 */
	private static final class Answer extends JLabel {

		private final int width;
		private final int align;
		private Tone tone = Tone.MUTED;
		private String text;
		private String iconName;

		Answer(int width, int align) {
			super(" ");
			this.width = width;
			this.align = align;
			setHorizontalAlignment(align);
			setIconTextGap(6);
		}

		/** {@code text} null or blank: an empty line that keeps its height. */
		void show(String text, Tone tone, String icon) {
			this.text = text;
			this.tone = tone;
			this.iconName = icon;
			render();
		}

		void render() {
			setIcon(iconName == null ? null : Lucide.icon(iconName, 14, () -> this.tone.colour()));
			if (text == null || text.isBlank()) {
				setText(" ");
			} else {
				int room = room();
				if (getFontMetrics(getFont()).stringWidth(text) <= room) {
					Labels.plain(this);
					setText(text);
				} else {
					putClientProperty("html.disable", null);
					int unscaled = Math.max(120, Math.round(room / UIScale.getUserScaleFactor()));
					setText(Labels.wrap(Labels.escape(text), unscaled, align));
				}
			}
			revalidate();
			repaint();
		}

		/** Pixels the text may take: the visible room less the icon, capped at {@code width}. */
		private int room() {
			int cap = UIScale.scale(width);
			int visible = (getParent() instanceof KeyTest k) ? k.room() : -1;
			if (visible <= 0) return cap;
			int icon = (getIcon() != null) ? getIcon().getIconWidth() + getIconTextGap() : 0;
			return Math.max(UIScale.scale(120), Math.min(cap, visible - icon - UIScale.scale(8)));
		}

		@Override
		public Color getForeground() {
			return (tone != null) ? tone.colour() : super.getForeground();
		}
	}
}
