package brightside.ui.components;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.util.UIScale;

/**
 * The app's labels, each a named role rather than an ad-hoc font and colour.
 * Every one shows its text literally: much of what the app labels — agent
 * names, conversation and request titles — is written by someone else, and a
 * label that interpreted {@code <html>} would render it as markup. Only
 * {@link #html} opts in, for the app's own copy.
 */
public final class Labels {

	/** Swing's {@code BasicHTML.htmlDisable} client property, which is not public. */
	private static final String HTML_DISABLE = "html.disable";

	private Labels() {
	}

	/** Plain text in the standard type. */
	public static JLabel text(String text) {
		return plain(new JLabel(text));
	}

	/** Secondary text at full size. */
	public static JLabel muted(String text) {
		return Styles.classes(text(text), Styles.MUTED);
	}

	/** A small, muted line: metadata, status, a hint. */
	public static JLabel small(String text) {
		return Styles.classes(text(text), Styles.SMALL, Styles.MUTED);
	}

	/** A small line in a chosen tone — {@link Styles#ACCENT}, {@link Styles#ERROR}, … */
	public static JLabel small(String text, String tone) {
		return Styles.classes(text(text), Styles.SMALL, tone);
	}

	/** The smallest, muted: a caption over a detail. */
	public static JLabel caption(String text) {
		return Styles.classes(text(text), Styles.MINI, Styles.MUTED);
	}

	/** A bold line at text size: a heading inside a block. */
	public static JLabel heading(String text) {
		return Styles.classes(text(text), Styles.STRONG);
	}

	/** A section heading, a step up from the text. */
	public static JLabel section(String text) {
		return Styles.classes(text(text), Styles.SECTION);
	}

	/** A screen title. */
	public static JLabel title(String text) {
		return Styles.classes(text(text), Styles.TITLE);
	}

	/** A label showing only an icon. */
	public static JLabel icon(Icon icon) {
		return new JLabel(icon);
	}

	/**
	 * The app's own copy as HTML, wrapped at {@code width} and aligned
	 * {@link SwingConstants#CENTER} or {@link SwingConstants#LEFT}. Never for text
	 * from an agent or a peer — use {@link #text} or {@link SelectableText}.
	 */
	public static JLabel html(String html, int width, int align) {
		return new JLabel(wrap(html, width, align), align);
	}

	/** The HTML {@link #html} shows — for updating such a label's text later. */
	public static String wrap(String html, int width, int align) {
		String css = "text-align:" + (align == SwingConstants.CENTER ? "center" : "left")
			+ "; width:" + UIScale.scale(width) + "px;";
		return "<html><div style='" + css + "'>" + html + "</div></html>";
	}

	/** Turns off HTML interpretation so the component shows its text literally. */
	public static <C extends JComponent> C plain(C component) {
		component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
		return component;
	}
}
