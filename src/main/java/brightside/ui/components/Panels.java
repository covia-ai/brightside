package brightside.ui.components;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;

/** Transparent layout containers and the rule between blocks. */
public final class Panels {

	private Panels() {
	}

	/** A transparent vertical stack whose children align left. */
	public static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A transparent horizontal row. */
	public static JPanel row() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** Centres {@code content} in whatever space the panel is given. */
	public static JPanel centred(JComponent content) {
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		p.add(content);
		return p;
	}

	/** A full-width hairline between blocks in a column. */
	public static JSeparator rule() {
		JSeparator s = new JSeparator();
		s.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
		return s;
	}

	/**
	 * Moves {@code page} into {@code holder}, shown. A page taken from a card
	 * deck may have been hidden there as a card not on view, and keeps that
	 * when it moves; in its new place it is the content, so it is shown.
	 */
	public static void adopt(java.awt.Container holder, java.awt.Component page, Object constraints) {
		holder.add(page, constraints);
		page.setVisible(true);
	}
}
