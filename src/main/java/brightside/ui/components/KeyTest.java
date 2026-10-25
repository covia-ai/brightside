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
	/** Bumped whenever the key or model changes, so a result for the old ones is dropped. */
	private int generation;

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
		button.setToolTipText("Send one tiny request to check the key and model work (usually costs a fraction of a cent)");
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

	/** {@code text} broken at spaces into lines no wider than {@code room}; a single overlong word stands alone. */
	static java.util.List<String> lines(String text, java.awt.FontMetrics fm, int room) {
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.trim().split("\\s+")) {
			String candidate = (line.length() == 0) ? word : line + " " + word;
			if (line.length() > 0 && fm.stringWidth(candidate) > room) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0) out.add(line.toString());
		return out;
	}

	private void rewrap() {
		answer.render();
		hint.render();
	}

	/** Enables the button, unless a test is already running. */
	public void setTestEnabled(boolean on) {
		wanted = on;
		button.setEnabled(on && !running);
	}

	/**
	 * Clears the last answer: the key or model it described has changed. A test
	 * still running is abandoned, not shown: its answer would describe the old
	 * key or model under the new one.
	 */
	public void reset() {
		generation++;
		answer.show(null, Tone.MUTED, null);
		hint.show(null, Tone.MUTED, null);
	}

	private void run() {
		Supplier<KeyCheck.Result> test = prepare.get();
		int asked = ++generation;
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
				if (asked != generation) return; // the key or model changed meanwhile
				KeyCheck.Result r;
				try {
					r = get();
				} catch (Exception e) {
					answer.show("The test couldn't run: " + KeyCheck.mask(e.getMessage(), null), Tone.ERROR, "circle-alert");
					return;
				}
				answer.show(r.message(), r.ok() ? Tone.SUCCESS : Tone.ERROR, r.ok() ? "circle-check" : "circle-alert");
				hint.show(r.hint(), Tone.MUTED, null);
			}
		}.execute();
	}

	/**
	 * One line of the answer, its icon right beside the text and both in a tone
	 * that follows the theme. Text wider than the room it has is broken into
	 * lines here, by this label's own font, so no line can be wider than the
	 * row: the room is the width the layout gives the panel (capped at
	 * {@code width}). HTML only carries the line breaks; the text is escaped.
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
				Labels.plain(this);
				setText(" ");
			} else {
				java.util.List<String> lines = KeyTest.lines(text, getFontMetrics(getFont()), room());
				if (lines.size() == 1) {
					Labels.plain(this);
					setText(text);
				} else {
					putClientProperty("html.disable", null);
					StringBuilder html = new StringBuilder("<html><div style='text-align:")
						.append(align == SwingConstants.CENTER ? "center" : "left").append("'>");
					for (int i = 0; i < lines.size(); i++) {
						if (i > 0) html.append("<br>");
						html.append(Labels.escape(lines.get(i)));
					}
					setText(html.append("</div></html>").toString());
				}
			}
			revalidate();
			repaint();
		}

		/** Pixels a line may take: the panel's width less the icon, capped at {@code width}. */
		private int room() {
			int cap = UIScale.scale(width);
			Container p = getParent();
			int given = (p != null && p.getWidth() > 0) ? p.getWidth() : cap;
			int icon = (getIcon() != null) ? getIcon().getIconWidth() + getIconTextGap() : 0;
			// A little slack: HTML lays text out a hair wider than FontMetrics measures it.
			return Math.max(UIScale.scale(120), Math.min(cap, given) - icon - UIScale.scale(12));
		}

		@Override
		public Color getForeground() {
			return (tone != null) ? tone.colour() : super.getForeground();
		}
	}
}
