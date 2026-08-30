package brightside.ui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;

import com.formdev.flatlaf.util.UIScale;

/** Scroll panes the way the app uses them: borderless, transparent, with a sensible wheel step. */
public final class Scrolls {

	private static final int STEP = 24;

	private Scrolls() {
	}

	/**
	 * Scrolls vertically only; the content is laid out at the viewport's width
	 * — always, so wrapping text reflows narrower as well as wider. (A plain
	 * panel would otherwise be laid out at its preferred width, which wrapped
	 * text areas only ever report as growing.)
	 */
	public static JScrollPane vertical(JComponent content) {
		JComponent view = (content instanceof Scrollable) ? content : new TracksWidth(content);
		return configure(new JScrollPane(view,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
	}

	/** Scrolls both ways, for content that must keep its width (raw text, code). */
	public static JScrollPane both(JComponent content) {
		return configure(new JScrollPane(content));
	}

	/**
	 * Wraps a column so it hugs the top of a taller viewport instead of being
	 * stretched down it — for a list of rows inside {@link #vertical}.
	 */
	public static JPanel hugTop(JComponent column) {
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.add(column, BorderLayout.NORTH);
		return holder;
	}

	private static JScrollPane configure(JScrollPane pane) {
		pane.setBorder(BorderFactory.createEmptyBorder());
		pane.setOpaque(false);
		pane.getViewport().setOpaque(false);
		pane.getVerticalScrollBar().setUnitIncrement(UIScale.scale(STEP));
		return pane;
	}

	/** A transparent wrapper that keeps its content as wide as the viewport and as tall as it needs. */
	@SuppressWarnings("serial")
	private static final class TracksWidth extends JPanel implements Scrollable {
		TracksWidth(JComponent content) {
			super(new BorderLayout());
			setOpaque(false);
			add(content, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return UIScale.scale(STEP);
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
