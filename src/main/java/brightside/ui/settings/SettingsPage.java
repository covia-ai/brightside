package brightside.ui.settings;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.HintLabel;
import brightside.ui.components.Labels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * Common structure shared by every settings page: a scrolling two-column form on
 * top and a fixed action bar at the bottom (a status note and one primary button)
 * that stays put while the form scrolls. Subclasses populate the form with
 * {@link #addField} and {@link #addSpan}, drive the single {@link #primary}
 * action, and report status with {@link #setNote}. A page has no opening
 * paragraph: each explanation lives at the control it explains, as a hint, a
 * tooltip, or a note only where a consequence must be seen before acting.
 *
 * <p>A page that calls {@link #group} sets what follows on a {@link Card} — the
 * settings that belong together, under an optional heading, each labelled row
 * ruled off from the one above — until the next group or {@link #endGroup}.
 * Cards are opt-in: a page that never groups (Schedule, whose content is its
 * own cards) lays out flat as before.
 */
@SuppressWarnings("serial")
abstract class SettingsPage extends JPanel {

	/** The columns of a form: labels, then the fields. */
	private static final String COLUMNS = "[]16[grow,fill]";
	/** In a card: a common least label width, so neighbouring cards' fields line up. */
	private static final String CARD_COLUMNS = "[140:pref]16[grow,fill]";

	private final JPanel form = new JPanel(new MigLayout("insets 24 28 14 28, fillx, wrap 2", COLUMNS, ""));
	/** Where rows go: the form, or the card of the group being filled. */
	private JPanel target = form;
	/** Whether the current card has a row yet, so the next labelled row is ruled off from it. */
	private boolean cardHasRow;
	private boolean anyGroup;
	protected final JLabel note = Labels.small(" ");
	protected final JButton primary;

	/** {@code primaryLabel}: the one action's label, or null for a page with nothing to save (General), which then has no action bar. */
	protected SettingsPage(String primaryLabel) {
		super(new BorderLayout());
		primary = (primaryLabel != null) ? Buttons.primary(primaryLabel) : null;
		add(Scrolls.vertical(form), BorderLayout.CENTER);
		if (primary != null) add(actionBar(note, primary), BorderLayout.SOUTH);
	}

	/**
	 * Starts a group: the rows that follow sit on one card, under
	 * {@code title} (null for none). Capped in width, so on a wide window the
	 * cards keep a readable measure rather than stretching edge to edge.
	 */
	protected final void group(String title) {
		// The space between groups goes above the heading, or above the card when there is none.
		int gap = anyGroup ? 20 : 0;
		if (title != null) {
			form.add(Labels.heading(title), "span 2, gapbottom 2, gaptop " + gap);
			gap = 0;
		}
		Card card = new Card();
		card.setLayout(new MigLayout("insets 12 16 12 16, fillx, wrap 2, gapy 10, hidemode 3", CARD_COLUMNS, ""));
		form.add(card, "span 2, growx, wmin 0, wmax 720, gaptop " + gap);
		target = card;
		cardHasRow = false;
		anyGroup = true;
	}

	/** Back to the flat form, below the last card. */
	protected final void endGroup() {
		target = form;
	}

	/** A label + field on one row (field grows, capped so it doesn't sprawl). */
	protected final void addField(String label, JComponent field) {
		row(label(label), "", field, "growx, wmax 400");
	}

	/** A label with a ⓘ hint + field on one row; the label is returned so its text or hint can follow the app's state. */
	protected final HintLabel addField(String label, String hint, JComponent field) {
		HintLabel l = hintLabel(label, hint);
		row(l, "", field, "growx, wmax 400");
		return l;
	}

	/** A label with a ⓘ hint + a value row that takes whatever width it is given (an identity, a key). */
	protected final HintLabel addValueRow(String label, String hint, JComponent value) {
		HintLabel l = hintLabel(label, hint);
		row(l, "", value, "growx, wmin 0");
		return l;
	}

	/** A label (top-aligned) + a taller/multi-line value on one row. */
	protected final void addFieldTop(String label, JComponent value) {
		row(label(label), "aligny top, gaptop 3", value, "growx, wmin 0");
	}

	/** A component spanning the full width of the form. */
	protected final void addSpan(JComponent c) {
		span(c, "span 2, growx, wmin 0");
	}

	/** A component spanning both columns but at its natural size, left-aligned. */
	protected final void addSpanLeft(JComponent c) {
		span(c, "span 2");
	}

	/** A full-width component with extra MigLayout constraints (e.g. {@code "h 96!"}). */
	protected final void addSpan(JComponent c, String extra) {
		span(c, "span 2, growx, wmin 0, " + extra);
	}

	/** A labelled row; on a card, ruled off from the row above. */
	private void row(JComponent label, String labelConstraints, JComponent field, String fieldConstraints) {
		if (target != form && cardHasRow) target.add(new JSeparator(), "span 2, growx");
		target.add(label, labelConstraints);
		target.add(field, fieldConstraints);
		cardHasRow = true;
	}

	/** A full-width part of a row group: a list, a note, a row of buttons — never ruled off on its own. */
	private void span(JComponent c, String constraints) {
		target.add(c, constraints);
		cardHasRow = true;
	}

	/** On a card a label reads at full strength; on the flat form, muted beside its field. */
	private JLabel label(String text) {
		return (target != form) ? Labels.text(text) : Labels.muted(text);
	}

	private HintLabel hintLabel(String text, String hint) {
		HintLabel l = new HintLabel(text, hint);
		return (target != form) ? l.unmuted() : l;
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
