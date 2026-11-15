package brightside.ui.components;

import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A view built the first time it is shown: for one that costs real time to
 * build (a large raw document) and may never be looked at, such as a tab
 * behind others. Until then it holds nothing.
 */
@SuppressWarnings("serial")
public final class Deferred extends JPanel {

	private Supplier<? extends JComponent> build;

	public Deferred(Supplier<? extends JComponent> build) {
		super(new BorderLayout());
		this.build = build;
		setOpaque(false);
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) built();
		});
	}

	/** Builds the view now, if it has not been built; event thread. */
	public void built() {
		if (build == null) return;
		Supplier<? extends JComponent> b = build;
		build = null;
		add(b.get(), BorderLayout.CENTER);
		revalidate();
		repaint();
	}
}
