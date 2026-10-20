package brightside.ui.components;

import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

import com.formdev.flatlaf.FlatClientProperties;

/** What the app's text fields do beyond taking text: let a pasted secret be checked, point out the one at fault. */
public final class Fields {

	private Fields() {
	}

	/**
	 * A field for pasting a secret (an API key, a bot token): hidden as it is
	 * typed, with FlatLaf's reveal button to check it before saving.
	 */
	public static <F extends JPasswordField> F secret(F field) {
		return Styles.style(field, "showRevealButton: true");
	}

	/** Outlines {@code field} as the one at fault until its text next changes, and puts the caret there. */
	public static void flag(JTextComponent field) {
		flag(field, field);
	}

	/**
	 * Outlines {@code outlined} (a text area's scroll pane, which carries its
	 * border) as at fault until {@code edited}'s text next changes.
	 */
	public static void flag(JComponent outlined, JTextComponent edited) {
		outlined.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
		edited.requestFocusInWindow();
		Document document = edited.getDocument();
		document.addDocumentListener(new DocumentListener() {
			private void clear() {
				outlined.putClientProperty(FlatClientProperties.OUTLINE, null);
				document.removeDocumentListener(this);
			}

			@Override
			public void insertUpdate(DocumentEvent e) {
				clear();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				clear();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				clear();
			}
		});
	}
}
