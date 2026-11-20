package brightside.ui.inbox;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Inbox;
import brightside.ui.components.Card;
import brightside.ui.components.StatusBadge;
import brightside.ui.components.Disclosure;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;

/**
 * One request as a decision card in the Inbox column: a rounded card on the
 * card ground with a 3 px bar while it is open, resolved ones sit quietly on
 * the panel, holding a
 * {@link Disclosure} whose header (chevron, title, who and when, a status chip)
 * collapses or expands the body, the {@link RequestForm}. Cards are what keep
 * long requests readable and separate: each is its own bordered block, and
 * only the ones you are dealing with need to be open.
 */
@SuppressWarnings("serial")
final class RequestCard extends Card {

	private final Inbox.Request request;
	private final RequestForm form;
	private final Disclosure disclosure;

	RequestCard(Inbox.Request request, RequestForm.Listener listener, boolean expanded) {
		super(brightside.ui.components.Theme.cardArc());
		this.request = request;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		// Room for the 3 px bar on the left of an open request.
		setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 1));

		JLabel title = Labels.text(request.title());
		// Two points of slack, so a width rounded down never cuts the title's last letter.
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		Styles.style(title, request.open() ? "font: bold -1" : "font: -1");
		JPanel text = Panels.column();
		text.add(title);
		text.add(Labels.small(RequestForm.meta(request)));

		form = new RequestForm(request, listener);
		form.setBorder(BorderFactory.createEmptyBorder(0, 18, 14, 18));

		disclosure = new Disclosure(text, form).trailing(chip(request));
		disclosure.header().setMargin(new Insets(10, 12, 10, 12));
		disclosure.header().setToolTipText(request.open() ? "Show or hide this request" : "Show or hide the resolved request");
		disclosure.setExpanded(expanded);
		add(disclosure);
	}

	Inbox.Request request() {
		return request;
	}

	RequestForm form() {
		return form;
	}

	/** Runs {@code action} after the card folds or unfolds. */
	void onToggle(Runnable action) {
		disclosure.onToggle(action);
	}

	boolean expanded() {
		return disclosure.isExpanded();
	}

	void setExpanded(boolean expanded) {
		disclosure.setExpanded(expanded);
	}

	/** The amber "Waiting on you" badge while open; otherwise the outcome, quietly, with its icon. */
	private static JLabel chip(Inbox.Request r) {
		if (r.open()) return StatusBadge.waiting();
		StatusLabel outcome = switch (r.status() == null ? "" : r.status()) {
			case "answered" -> new StatusLabel("Answered", "circle-check", Tone.MUTED);
			case "rejected" -> new StatusLabel("Rejected", "circle-x", Tone.MUTED);
			case "expired" -> new StatusLabel("Expired", "hourglass", Tone.MUTED);
			case "cancelled" -> new StatusLabel("Withdrawn", "circle-x", Tone.MUTED);
			default -> new StatusLabel(String.valueOf(r.status()), "history", Tone.MUTED);
		};
		Styles.classes(outcome, Styles.SMALL);
		outcome.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
		return outcome;
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/**
	 * A decision card: the card ground (white on Light, raised navy on Dark)
	 * with a hairline edge; resolved requests sit back on the panel.
	 */
	@Override
	protected Color fill() {
		return request.open() ? Theme.card() : Theme.panel();
	}

	@Override
	protected Color outline() {
		return Theme.line();
	}

	/** An open request carries a 3 px bar on its left, in the theme's accent bar colour. */
	@Override
	protected void paintComponent(java.awt.Graphics g) {
		super.paintComponent(g);
		if (!request.open()) return;
		java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
		try {
			com.formdev.flatlaf.ui.FlatUIUtils.setRenderingHints(g2);
			int w = com.formdev.flatlaf.util.UIScale.scale(3);
			int inset = com.formdev.flatlaf.util.UIScale.scale(8);
			g2.setColor(Theme.sidebarBar());
			g2.fillRoundRect(0, inset, w, getHeight() - 2 * inset, w, w);
		} finally {
			g2.dispose();
		}
	}
}
