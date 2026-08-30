package brightside.ui.components;

import java.awt.Cursor;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A field label with a small ⓘ beside it whose hover text explains the field
 * — the explanation is there for whoever wants it without taking up the form.
 * Both the text and the hint can change with the app's state.
 */
@SuppressWarnings("serial")
public final class HintLabel extends JPanel {

	private static final int ICON = 14;

	private final JLabel text;
	private final JLabel icon;

	public HintLabel(String label, String hint) {
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setOpaque(false);
		text = Labels.muted(label);
		icon = Labels.icon(Lucide.icon("info", ICON, Theme::muted));
		icon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add(text);
		add(Box.createHorizontalStrut(5));
		add(icon);
		setHint(hint);
	}

	public void setText(String label) {
		text.setText(label);
	}

	/** The hover text; null hides the ⓘ. */
	public void setHint(String hint) {
		icon.setToolTipText(hint);
		icon.setVisible(hint != null && !hint.isBlank());
	}
}
