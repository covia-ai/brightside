package brightside.ui.settings;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.HintLabel;
import brightside.ui.components.Labels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
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

	private final JPanel form = new JPanel(
		new MigLayout("insets 24 28 14 28, fillx, wrap 2", "[]16[grow,fill]", ""));
	protected final JLabel note = Labels.small(" ");
	protected final JButton primary;

	protected SettingsPage(String primaryLabel) {
		super(new BorderLayout());
		primary = Buttons.primary(primaryLabel);
		add(Scrolls.vertical(form), BorderLayout.CENTER);
		add(actionBar(note, primary), BorderLayout.SOUTH);
	}

	/** A full-width, wrapping description at the top of the page. */
	protected final void addDescription(String text) {
		form.add(SelectableText.description(text), "span 2, growx, wmin 0, gapbottom 14");
	}

	/** A label + field on one row (field grows, capped so it doesn't sprawl). */
	protected final void addField(String label, JComponent field) {
		form.add(Labels.muted(label));
		form.add(field, "growx, wmax 400");
	}

	/** A label with a ⓘ hint + field on one row; the label is returned so its text or hint can follow the app's state. */
	protected final HintLabel addField(String label, String hint, JComponent field) {
		HintLabel l = new HintLabel(label, hint);
		form.add(l);
		form.add(field, "growx, wmax 400");
		return l;
	}

	/** A label with a ⓘ hint + a value row that takes whatever width it is given (an identity, a key). */
	protected final HintLabel addValueRow(String label, String hint, JComponent value) {
		HintLabel l = new HintLabel(label, hint);
		form.add(l);
		form.add(value, "growx, wmin 0");
		return l;
	}

	/** A label (top-aligned) + a taller/multi-line value on one row. */
	protected final void addFieldTop(String label, JComponent value) {
		form.add(Labels.muted(label), "aligny top, gaptop 3");
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
		Styles.classes(note, Styles.SMALL, error ? Styles.ERROR : Styles.MUTED);
		note.setText(text);
	}

	protected final void clearNote() {
		setNote(" ", false);
	}

	/**
	 * The fixed action bar for the bottom of a settings screen (excluded from the
	 * scroll): the status note on the left, one primary action on the right, with
	 * a hairline above.
	 */
	private static JComponent actionBar(JComponent note, JButton primary) {
		JPanel bar = new JPanel(new MigLayout("insets 10 28 12 28, fillx", "[grow]12[]", ""));
		bar.setBorder(Borders.hairlineTop());
		bar.add(note, "growx");
		bar.add(primary, "");
		return bar;
	}
}
