package brightside.ui.components;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** The system clipboard. */
public final class Clipboard {

	private Clipboard() {
	}

	/** Puts {@code text} on the clipboard; nothing happens for null or empty text. */
	public static void copy(String text) {
		if (text == null || text.isEmpty()) return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}
}
