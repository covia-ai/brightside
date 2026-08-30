package brightside.ui.inspect;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;

/**
 * The read-only compositions the inspectors and the inbox share, built from
 * {@code brightside.ui.components}: a padded column, a key/value row, an
 * accent heading, a body block and a raw (JSON) view.
 */
public final class Blocks {

	private Blocks() {
	}

	/** A padded column of blocks. */
	public static JPanel column() {
		JPanel p = Panels.column();
		p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		return p;
	}

	/** A key beside a selectable value. */
	public static JPanel kv(String key, String value) {
		return Panels.keyValue(key, new SelectableText(value));
	}

	/** A bold heading in the accent, spaced from the block above. */
	public static JLabel heading(String text) {
		JLabel l = Styles.classes(Labels.heading(text), Styles.STRONG, Styles.ACCENT);
		l.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		return l;
	}

	/** A read-only, selectable block: wrapping prose, or unwrapped monospaced text. */
	public static SelectableText body(String text, boolean mono) {
		return mono ? SelectableText.technical(text).small().noWrap() : new SelectableText(text);
	}

	/** A scrolling monospaced view of raw text (JSON). */
	public static JScrollPane raw(String text) {
		SelectableText t = SelectableText.technical(text).small().noWrap();
		t.setCaretPosition(0);
		return Scrolls.both(t);
	}
}
