package brightside.ui.inbox;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Inbox;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Scrolls;

/**
 * The <b>Inbox</b>: every request waiting for the owner's decision, and the
 * ones already dealt with, as one scrolling column of {@link RequestCard}s —
 * open ones first and expanded, resolved ones collapsed. Each card is its own
 * bordered block, so long requests stay readable and never run into each
 * other; the header of any card folds it away or opens it again.
 *
 * <p>Dumb: it renders the requests it is given and reports decisions through
 * the {@link RequestForm.Listener}; the app performs the responses. A card
 * being filled in survives a refresh unless its request changed underneath.
 */
@SuppressWarnings("serial")
public final class InboxScreen extends JPanel {

	private final RequestForm.Listener listener;
	private final JPanel column = Panels.column();
	private final JLabel notice = Labels.small(" ");
	private final JLabel empty = Labels.muted("Nothing is waiting for you.");
	/** Cards by request id, in display order. */
	private final Map<String, RequestCard> cards = new LinkedHashMap<>();

	public InboxScreen(RequestForm.Listener listener) {
		super(new BorderLayout());
		this.listener = listener;

		JLabel header = Labels.small("Inbox");
		header.setBorder(BorderFactory.createEmptyBorder(10, 18, 4, 18));

		column.setBorder(BorderFactory.createEmptyBorder(6, 18, 12, 18));
		empty.setBorder(BorderFactory.createEmptyBorder(18, 0, 18, 0));
		empty.setAlignmentX(LEFT_ALIGNMENT);

		notice.setBorder(BorderFactory.createEmptyBorder(6, 18, 8, 18));

		add(header, BorderLayout.NORTH);
		add(Scrolls.vertical(Scrolls.hugTop(column)), BorderLayout.CENTER);
		add(notice, BorderLayout.SOUTH);
		setRequests(List.of());
	}

	/**
	 * Replace the requests (open first). A card whose request is unchanged is
	 * kept as it is — expanded or not, with whatever has been typed into it; a
	 * changed request gets a fresh card that inherits the old one's fold state.
	 */
	public void setRequests(List<Inbox.Request> requests) {
		Map<String, RequestCard> next = new LinkedHashMap<>();
		for (Inbox.Request r : requests) {
			RequestCard old = cards.get(r.id());
			RequestCard card;
			if (old != null && old.request().equals(r)) {
				card = old;
			} else {
				boolean expanded = (old != null) ? old.expanded() : r.open();
				card = new RequestCard(r, listener, expanded);
			}
			next.put(r.id(), card);
		}
		cards.clear();
		cards.putAll(next);

		column.removeAll();
		if (cards.isEmpty()) {
			column.add(empty);
		} else {
			boolean first = true;
			for (RequestCard card : cards.values()) {
				if (!first) column.add(Box.createVerticalStrut(10));
				column.add(card);
				first = false;
			}
		}
		column.revalidate();
		column.repaint();
	}

	/** A line under the column: the result of the last answer or rejection. Frees any form that was sending. */
	public void showNotice(String text) {
		notice.setText(text);
		for (RequestCard card : cards.values()) card.form().setBusy(false);
	}

	/** The card for a request id, or null. Package-visible for the app's own checks. */
	Component card(String id) {
		return cards.get(id);
	}
}
