package brightside.ui.components;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

import net.miginfocom.swing.MigLayout;

/**
 * One figure on a {@link Card}: a {@link Badge} for what it counts, a caption,
 * the figure itself and a line of detail — "Next up · In 5 minutes · Morning
 * check-in". Tiles sit side by side in a summary strip; one that leads to the
 * thing it counts is {@linkplain #onPress pressable}.
 */
@SuppressWarnings("serial")
public final class StatTile extends Card {

	/** {@code detail}: a line under the figure, or null for none (the tile keeps its height). */
	public StatTile(String icon, Tone tone, String caption, String value, String detail) {
		super(12);
		setLayout(new MigLayout("insets 12 14 12 14, wrap 2, gapy 1", "[]12[grow,fill]", ""));
		add(new Badge(icon, tone, 29), "spany 3, aligny center");
		add(Labels.small(caption), "wmin 0");
		add(Labels.lead(value), "wmin 0");
		add(Labels.small((detail == null || detail.isBlank()) ? " " : detail), "wmin 0");
	}

	/**
	 * Makes the tile a way in to what it counts: a press runs {@code action},
	 * the pointer turns to a hand over it and an accent edge shows while it is
	 * there. {@code tooltip} says where it goes.
	 */
	public StatTile onPress(String tooltip, Runnable action) {
		setToolTipText(tooltip);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				outline(() -> Theme.fade(Theme.accent(), 0.45f));
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (!contains(e.getPoint())) outline((Color) null);
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) action.run();
			}
		});
		return this;
	}
}
