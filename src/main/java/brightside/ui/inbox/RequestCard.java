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
import brightside.ui.components.Chip;
import brightside.ui.components.Disclosure;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;

/**
 * One request as a card in the Inbox column: a rounded, tinted {@link Card} —
 * open requests carry the accent tint, resolved ones sit quietly — holding a
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
		super(Card.ARC);
		this.request = request;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));

		JLabel title = Labels.text(request.title());
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

	boolean expanded() {
		return disclosure.isExpanded();
	}

	void setExpanded(boolean expanded) {
		disclosure.setExpanded(expanded);
	}

	/** "Waiting for you" as an accent chip while open; otherwise the outcome, quietly, with its icon. */
	private static JLabel chip(Inbox.Request r) {
		if (r.open()) return new Chip("Waiting for you");
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

	/** An accent tint while the request is open, a faint one once resolved. */
	@Override
	protected Color fill() {
		Color base = Theme.panel();
		return request.open() ? Theme.blend(base, Theme.accent(), 0.10f) : Theme.blend(base, Theme.foreground(), 0.03f);
	}

	/** A hairline edge: accent-tinted while open, the theme's line once resolved. */
	@Override
	protected Color outline() {
		return request.open() ? Theme.blend(Theme.panel(), Theme.accent(), 0.45f) : Theme.line();
	}
}
