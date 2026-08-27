package covia.brightside.ui.settings;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;

import net.miginfocom.swing.MigLayout;

import covia.brightside.ui.LAF;

/**
 * Shared building blocks for the settings tabs so every screen uses the same
 * components the same way: a scrolling form area, muted left-aligned field labels,
 * full-width selectable text, an accent primary button, and a fixed action bar at
 * the bottom (status note on the left, one primary action on the right) that stays
 * put while the form scrolls.
 */
final class SettingsUI {

	private SettingsUI() {
	}

	static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	static Color separator() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : muted();
	}

	/** A field label (left-aligned in the label column). */
	static JLabel label(String text) {
		JLabel l = new JLabel(text);
		l.setForeground(muted());
		return l;
	}

	/** The accent primary action button (Save / Generate). */
	static JButton primary(String text) {
		JButton b = new JButton(text);
		b.putClientProperty("JButton.buttonType", "roundRect");
		b.setBackground(LAF.ACCENT);
		b.setForeground(Color.WHITE);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** A read-only, selectable, wrapping text block (standard component, copyable). */
	static JTextArea selectable(String text) {
		JTextArea a = new JTextArea(text);
		a.setEditable(false);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setOpaque(false);
		a.setBorder(null);
		a.setFont(UIManager.getFont("Label.font"));
		return a;
	}

	/** A read-only selectable value in the JVM's cross-platform logical mono font. */
	static JTextArea technicalValue(String text) {
		JTextArea a = selectable(text);
		a.setFont(LAF.monospaced(a.getFont()));
		return a;
	}

	/** Applies the technical-value font while preserving a component's size/style. */
	static Font technicalFont(Font base) {
		return LAF.monospaced(base);
	}

	/** A muted description for the top of a screen (full-width, wrapping). */
	static JTextArea description(String text) {
		JTextArea a = selectable(text);
		a.setForeground(muted());
		return a;
	}

	/** A status note (small, muted). */
	static JLabel note() {
		JLabel l = new JLabel(" ");
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setForeground(muted());
		return l;
	}

	/** Wrap a form in a borderless, transparent vertical scroll pane. */
	static JScrollPane formScroll(JComponent form) {
		JScrollPane sp = new JScrollPane(form,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null);
		sp.setOpaque(false);
		sp.getViewport().setOpaque(false);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	/**
	 * A fixed action bar for the bottom of a settings screen (excluded from the
	 * scroll): the status note on the left, one primary action on the right, with a
	 * separator line above.
	 */
	static JComponent actionBar(JComponent note, JButton primary) {
		JPanel bar = new JPanel(new MigLayout("insets 10 28 12 28, fillx", "[grow]12[]", ""));
		bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separator()));
		bar.add(note, "growx");
		bar.add(primary, "");
		return bar;
	}
}
