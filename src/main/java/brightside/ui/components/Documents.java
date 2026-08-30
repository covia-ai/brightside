package brightside.ui.components;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/** Text-change wiring without the three-method {@link DocumentListener} ceremony. */
public final class Documents {

	private Documents() {
	}

	/** Runs {@code action} whenever the field's text changes, however it changes. */
	public static void onChange(JTextComponent field, Runnable action) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				action.run();
			}
		});
	}
}
