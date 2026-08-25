package covia.brightside.ui.chat;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/** The scrolling column of rows; tracks the viewport width so bubbles can reflow and align. */
@SuppressWarnings("serial")
final class MessageColumn extends JPanel implements Scrollable {
	MessageColumn() {
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
	}

	void setAvailableWidth(int viewportWidth) {
		for (Component row : getComponents()) {
			if (row instanceof JPanel p) {
				for (Component c : p.getComponents()) {
					if (c instanceof Bubble b) b.setAvailableWidth(viewportWidth);
				}
			}
		}
		revalidate();
	}

	void clear() {
		removeAll();
	}

	@Override
	public Dimension getPreferredScrollableViewportSize() {
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
		return 24;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
		return r.height;
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
