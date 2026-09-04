package brightside.ui.inspect;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;

import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;

/**
 * The read-only pieces the inbox and the inspectors share, built from
 * {@code brightside.ui.components}: an accent heading, a body block and a raw
 * (JSON) view — each selectable, like everything around it. The inspectors'
 * own tabs are {@link brightside.ui.components.Readout} documents.
 */
public final class Blocks {

	private Blocks() {
	}

	/** A bold heading in the accent, spaced from the block above. */
	public static SelectableText heading(String text) {
		SelectableText t = new SelectableText(text).bold().tone(Styles.ACCENT);
		t.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		return t;
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
