package brightside.ui.components;

import java.awt.Component;
import java.util.Arrays;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A long run of read-only text shown clamped to a few lines, with a link to
 * show the whole of it and fold it back. For the content column of an
 * {@link EntryList}, where one entry — a skill body, a tool result — must not
 * push every other off the screen. Selectable and copyable, wrapping, in
 * the standard or the monospaced face.
 */
@SuppressWarnings("serial")
public class Excerpt extends JPanel {

	/** The default clamp: this many lines, and this many characters. */
	public static final int DEFAULT_LINES = 6;
	public static final int DEFAULT_CHARS = 600;

	private final String full;
	/** The leading part shown while folded, or null when all of it fits. */
	private final String clamped;
	private final SelectableText text;
	private final PressButton toggle;
	private boolean expanded;
	private Runnable onToggle;

	public Excerpt(String content, boolean mono) {
		this(content, mono, DEFAULT_LINES, DEFAULT_CHARS);
	}

	/**
	 * @param content  the whole text
	 * @param mono     the monospaced face (code, JSON) rather than prose
	 * @param maxLines lines shown while folded
	 * @param maxChars characters shown while folded, whatever the line count
	 */
	public Excerpt(String content, boolean mono, int maxLines, int maxChars) {
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		full = (content != null) ? content : "";
		clamped = clamp(full, maxLines, maxChars);
		text = mono ? SelectableText.technical("").small() : new SelectableText("");
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(text);
		if (clamped != null) {
			toggle = Buttons.link("", this::toggle);
			toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(toggle);
		} else {
			toggle = null;
		}
		apply();
	}

	/** Whether the text is longer than the clamp — i.e. there is a link to show. */
	public boolean isClamped() {
		return clamped != null;
	}

	public boolean isExpanded() {
		return expanded;
	}

	/** Runs after every show-all or show-less, so a host can reflow. */
	public Excerpt onToggle(Runnable action) {
		this.onToggle = action;
		return this;
	}

	public void toggle() {
		expanded = !expanded;
		apply();
		if (onToggle != null) onToggle.run();
	}

	private void apply() {
		text.setText((expanded || clamped == null) ? full : clamped + " …");
		if (toggle != null) {
			int lines = full.split("\n", -1).length;
			toggle.setText(expanded ? "Show less"
				: (lines > 1) ? String.format("Show all  ·  %,d lines", lines)
				: String.format("Show all  ·  %,d characters", full.length()));
		}
		revalidate();
		repaint();
	}

	/**
	 * The leading part of {@code text} within {@code maxLines} lines and
	 * {@code maxChars} characters — cut at a space where one falls in the
	 * second half — or null when all of it already fits.
	 */
	static String clamp(String text, int maxLines, int maxChars) {
		String[] lines = text.split("\n", -1);
		boolean cutLines = lines.length > maxLines;
		String head = cutLines ? String.join("\n", Arrays.copyOf(lines, maxLines)) : text;
		if (head.length() > maxChars) {
			int space = head.lastIndexOf(' ', maxChars);
			head = head.substring(0, (space > maxChars / 2) ? space : maxChars);
			return head.stripTrailing();
		}
		return cutLines ? head.stripTrailing() : null;
	}
}
