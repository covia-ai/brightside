package brightside.ui.components;

import javax.swing.JLabel;
import javax.swing.SwingConstants;

import net.miginfocom.swing.MigLayout;

/**
 * What a list shows when it has nothing to list, or could not be read: a
 * {@link Badge}, a short title and a sentence on what to do, centred on a
 * {@link Card}. The title and sentence are the app's own copy.
 */
@SuppressWarnings("serial")
public final class EmptyState extends Card {

	public EmptyState(String icon, Tone tone, String title, String body) {
		super(14);
		setLayout(new MigLayout("insets 28 24 28 24, fillx, wrap 1, gapy 6", "[center]", ""));
		add(new Badge(icon, tone, 38), "gapbottom 6");
		add(Labels.lead(title));
		JLabel b = Labels.html(body, 380, SwingConstants.CENTER);
		Styles.classes(b, Styles.MUTED);
		add(b);
	}
}
