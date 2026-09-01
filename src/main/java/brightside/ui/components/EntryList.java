package brightside.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;

/**
 * A two-column list of entries — a summary beside its content — for the
 * inspectors: a message and its text, a tool and its description, a loaded
 * entry and its accounting. The summary column keeps one width so contents
 * align down the list; a {@linkplain #section section} row spans both columns
 * and introduces the entries after it; consecutive entries are parted by a
 * hairline. Put it in {@link Scrolls#vertical}.
 *
 * <p>Contents wrap at the width of their column. The list tells every
 * {@link SelectableText} in a content cell that width itself, at layout
 * time, so a wrapped text's height is a function of the column and not of
 * whatever width it was last measured at — the feedback that otherwise makes
 * rows balloon.
 */
@SuppressWarnings("serial")
public class EntryList extends JPanel {

	/** The summary column, in unscaled pixels. */
	public static final int SUMMARY_WIDTH = 210;
	private static final int GAP = 16;

	private final List<JComponent> contents = new ArrayList<>();
	private final List<JComponent> spanning = new ArrayList<>();
	private boolean lastWasEntry;

	public EntryList() {
		this(SUMMARY_WIDTH);
	}

	public EntryList(int summaryWidth) {
		// MigLayout scales pixel constraints for the display itself; give it unscaled numbers.
		super(new MigLayout("insets 0, gap 0 0, fillx, wrap 2",
			"[" + summaryWidth + "!, left]" + GAP + "[grow, fill]", ""));
		setOpaque(false);
	}

	/** A heading across both columns, introducing the entries that follow. */
	public JLabel section(String title) {
		JLabel l = Styles.classes(Labels.heading(title), Styles.STRONG, Styles.ACCENT);
		add(l, "span 2, growx, wmin 0, gaptop 14, gapbottom 2");
		lastWasEntry = false;
		return l;
	}

	/** A small, wrapping note across both columns — a caveat, an empty state. */
	public SelectableText note(String text) {
		SelectableText t = SelectableText.description(text).small();
		add(t, "span 2, growx, wmin 0, gapbottom 4");
		spanning.add(t);
		lastWasEntry = false;
		return t;
	}

	/** One entry: its summary beside its content. */
	public void entry(JComponent summary, JComponent content) {
		if (lastWasEntry) add(Panels.rule(), "span 2, growx, gaptop 8, gapbottom 8");
		add(summary, "aligny top, wmin 0");
		add(content, "aligny top, growx, wmin 0");
		contents.add(content);
		lastWasEntry = true;
	}

	/** A summary: a bold title, in {@code tone} when one is given, over small muted lines. */
	public static JPanel summary(String title, String tone, String... meta) {
		JPanel p = Panels.column();
		JLabel t = Labels.heading(title);
		if (tone != null) Styles.add(t, tone);
		p.add(t);
		for (String m : meta) {
			if (m != null && !m.isBlank()) p.add(Labels.small(m));
		}
		return p;
	}

	/**
	 * Lays the grid out, then tells every wrapping text the width its cell
	 * actually got and lays out once more with the heights that follow. A
	 * text whose width is unchanged is left alone, so this settles.
	 */
	@Override
	public void doLayout() {
		super.doLayout();
		boolean changed = false;
		for (JComponent c : contents) changed |= wrap(c, c.getWidth());
		for (JComponent c : spanning) changed |= wrap(c, c.getWidth());
		if (changed) super.doLayout();
	}

	private static boolean wrap(Component c, int width) {
		if (width <= 0) return false;
		boolean changed = false;
		if (c instanceof SelectableText t && t.wrapWidth() != width) {
			t.wrapAt(width);
			changed = true;
		}
		if (c instanceof Container container) {
			for (Component child : container.getComponents()) changed |= wrap(child, width);
		}
		return changed;
	}
}
