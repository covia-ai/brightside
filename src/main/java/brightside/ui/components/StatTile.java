package brightside.ui.components;

import net.miginfocom.swing.MigLayout;

/**
 * One figure on a {@link Card}: a {@link Badge} for what it counts, a caption,
 * the figure itself and a line of detail — "Next up · In 5 minutes · Morning
 * check-in". Tiles sit side by side in a summary strip.
 */
@SuppressWarnings("serial")
public final class StatTile extends Card {

	public StatTile(String icon, Tone tone, String caption, String value, String detail) {
		super(12);
		setLayout(new MigLayout("insets 12 14 12 14, wrap 2, gapy 1", "[]12[grow,fill]", ""));
		add(new Badge(icon, tone, 29), "spany 3, aligny center");
		add(Labels.small(caption), "wmin 0");
		add(Labels.lead(value), "wmin 0");
		add(Labels.small(detail), "wmin 0");
	}
}
