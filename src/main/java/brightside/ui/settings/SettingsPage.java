package brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;

/**
 * Common structure shared by every settings page: a scrolling two-column form on
 * top and a fixed action bar at the bottom (a status note and one primary button)
 * that stays put while the form scrolls. Subclasses populate the form with
 * {@link #addDescription}, {@link #addField} and {@link #addSpan}, drive the single
 * {@link #primary} action, and report status with {@link #setNote}.
 */
@SuppressWarnings("serial")
abstract class SettingsPage extends JPanel {

	protected static final Color ERROR = new Color(0xE5, 0x53, 0x53);

	private final JPanel form = new JPanel(
		new MigLayout("insets 24 28 14 28, fillx, wrap 2", "[]16[grow,fill]", ""));
	protected final JLabel note = SettingsUI.note();
	protected final JButton primary;

	protected SettingsPage(String primaryLabel) {
		super(new BorderLayout());
		primary = SettingsUI.primary(primaryLabel);
		add(SettingsUI.formScroll(form), BorderLayout.CENTER);
		add(SettingsUI.actionBar(note, primary), BorderLayout.SOUTH);
	}

	/** A full-width, wrapping description at the top of the page. */
	protected final void addDescription(String text) {
		form.add(SettingsUI.description(text), "span 2, growx, wmin 0, gapbottom 14");
	}

	/** A label + field on one row (field grows, capped so it doesn't sprawl). */
	protected final void addField(String label, JComponent field) {
		form.add(SettingsUI.label(label));
		form.add(field, "growx, wmax 400");
	}

	/** A label (top-aligned) + a taller/multi-line value on one row. */
	protected final void addFieldTop(String label, JComponent value) {
		form.add(SettingsUI.label(label), "aligny top, gaptop 3");
		form.add(value, "growx, wmin 0");
	}

	/** A component spanning the full width of the form. */
	protected final void addSpan(JComponent c) {
		form.add(c, "span 2, growx, wmin 0");
	}

	/** A component spanning both columns but at its natural size, left-aligned. */
	protected final void addSpanLeft(JComponent c) {
		form.add(c, "span 2");
	}

	/** A full-width component with extra MigLayout constraints (e.g. {@code "h 96!"}). */
	protected final void addSpan(JComponent c, String extra) {
		form.add(c, "span 2, growx, wmin 0, " + extra);
	}

	protected final void onPrimary(Runnable action) {
		primary.addActionListener(e -> action.run());
	}

	protected final void setNote(String text, boolean error) {
		note.setForeground(error ? ERROR : SettingsUI.muted());
		note.setText(text);
	}

	protected final void clearNote() {
		setNote(" ", false);
	}
}
