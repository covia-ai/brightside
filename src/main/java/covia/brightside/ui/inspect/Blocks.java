package covia.brightside.ui.inspect;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/** The read-only building blocks the inspectors and the inbox share: columns, key/value rows, headings, wrapping text. */
public final class Blocks {

	private Blocks() {
	}

	public static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		p.setOpaque(false);
		return p;
	}

	public static JScrollPane scroll(JComponent inner) {
		JScrollPane sp = new JScrollPane(inner);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(24);
		return sp;
	}

	public static JPanel kv(String key, String value) {
		JPanel row = new JPanel(new BorderLayout(12, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel k = new JLabel(key);
		k.setForeground(muted());
		k.setPreferredSize(new Dimension(130, k.getPreferredSize().height));
		JTextArea v = body(value, false);
		row.add(k, BorderLayout.WEST);
		row.add(v, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	public static JLabel heading(String text) {
		JLabel l = new JLabel(text);
		l.setFont(l.getFont().deriveFont(Font.BOLD));
		l.setForeground(accentText());
		l.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A read-only, wrapping, selectable block. Focusable, so native copy works in the dialog. */
	public static JTextArea body(String text, boolean mono) {
		JTextArea ta = new JTextArea(text);
		ta.setEditable(false);
		ta.setLineWrap(!mono);
		ta.setWrapStyleWord(true);
		ta.setOpaque(false);
		ta.setBorder(null);
		if (mono) ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		ta.setAlignmentX(Component.LEFT_ALIGNMENT);
		return ta;
	}

	public static JLabel small(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setForeground(muted());
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	public static Component divider() {
		JPanel d = new JPanel();
		d.setOpaque(true);
		d.setBackground(line());
		d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		d.setAlignmentX(Component.LEFT_ALIGNMENT);
		d.setBorder(BorderFactory.createEmptyBorder());
		return d;
	}

	/** A scrolling monospaced view of raw text (JSON). */
	public static JScrollPane raw(String text) {
		JTextArea ta = new JTextArea(text);
		ta.setEditable(false);
		ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		ta.setCaretPosition(0);
		JScrollPane sp = new JScrollPane(ta);
		sp.setBorder(BorderFactory.createEmptyBorder());
		return sp;
	}

	public static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	static Color accentText() {
		Color c = UIManager.getColor("Component.accentColor");
		return (c != null) ? c : UIManager.getColor("Label.foreground");
	}

	static Color line() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}

	public static Color errorColor() {
		return new Color(0xE5, 0x53, 0x53);
	}
}
