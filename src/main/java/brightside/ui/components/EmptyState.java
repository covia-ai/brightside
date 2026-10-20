package brightside.ui.components;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import net.miginfocom.swing.MigLayout;

/**
 * What a list shows when it has nothing to list, or could not be read: a
 * {@link Badge}, a short title, a sentence on what to do and, where there is
 * one, the button that does it — centred on a {@link Card}, or on nothing
 * when it fills an empty pane ({@link #bare}). The title and sentence are the
 * app's own copy; {@linkplain Labels#escape escape} anything else put in the
 * sentence.
 */
@SuppressWarnings("serial")
public final class EmptyState extends Card {

	/** {@code body}: HTML, or null for a title alone. */
	public EmptyState(String icon, Tone tone, String title, String body) {
		super(14);
		setLayout(new MigLayout("insets 28 24 28 24, fillx, wrap 1, gapy 6", "[center]", ""));
		add(new Badge(icon, tone, 38), "gapbottom 6");
		add(Labels.lead(title));
		if (body != null) {
			JLabel b = Labels.html(body, 380, SwingConstants.CENTER);
			Styles.classes(b, Styles.MUTED);
			add(b);
		}
	}

	/** Without the card behind it: for a pane that is otherwise empty. */
	public EmptyState bare() {
		fill(() -> null);
		return this;
	}

	/** The one thing to do about it, as a button under the sentence. */
	public EmptyState action(String label, Runnable action) {
		JButton b = Buttons.plain(label);
		b.addActionListener(e -> action.run());
		add(b, "gaptop 10");
		return this;
	}
}
