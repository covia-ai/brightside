package brightside.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

import com.formdev.flatlaf.util.UIScale;

/** Transparent layout containers, a key/value row and the rule between blocks. */
public final class Panels {

	/** The key column of {@link #keyValue} rows, in unscaled pixels. */
	private static final int KEY_WIDTH = 130;

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

	/** A key beside its value, keys aligned in a column of such rows. */
	public static JPanel keyValue(String key, JComponent value) {
		JPanel row = new JPanel(new BorderLayout(UIScale.scale(12), 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel k = Labels.muted(key);
		k.setPreferredSize(new Dimension(UIScale.scale(KEY_WIDTH), k.getPreferredSize().height));
		row.add(k, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** A full-width hairline between blocks in a column. */
	public static JSeparator rule() {
		JSeparator s = new JSeparator();
		s.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
		return s;
	}
}
