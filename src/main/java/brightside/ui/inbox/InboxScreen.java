package brightside.ui.inbox;

import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Inbox;
import brightside.ui.components.Chip;
import brightside.ui.components.EmptyState;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.ScreenHeader;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Tone;

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
	private final ScreenHeader header = new ScreenHeader("Inbox");
	/** How many requests are open, beside the title; hidden when none are. */
	private final Chip waiting = new Chip(" ");
	private final JLabel notice = Labels.small(" ");
	private final EmptyState empty = new EmptyState("inbox", Tone.MUTED, "Nothing is waiting for you",
		"When one of your agents needs a decision only you can make, it asks here.").bare();
	/** Cards by owner and request id, in display order. */
	private final Map<String, RequestCard> cards = new LinkedHashMap<>();
	private List<Inbox.Request> requests = List.of();
	private boolean dirty;

	public InboxScreen(RequestForm.Listener listener) {
		super(new BorderLayout());
		this.listener = listener;

		waiting.setToolTipText("Requests waiting for your decision");
		waiting.setVisible(false);
		header.detail(waiting);

		column.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
		empty.setAlignmentX(LEFT_ALIGNMENT);

		notice.setBorder(BorderFactory.createEmptyBorder(6, 18, 8, 18));

		add(header, BorderLayout.NORTH);
		add(Scrolls.vertical(Scrolls.hugTop(column)), BorderLayout.CENTER);
		JPanel notices = Panels.column();
		notice.setAlignmentX(LEFT_ALIGNMENT);
		notices.add(notice);
		add(notices, BorderLayout.SOUTH);
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) renderIfShowing();
		});
		setRequests(List.of());
	}

	/**
	 * Replace the requests (open first). A card whose request is unchanged is
	 * kept as it is — expanded or not, with whatever has been typed into it; a
	 * changed request gets a fresh card that inherits the old one's fold state.
	 */
	public void setRequests(List<Inbox.Request> requests) {
		this.requests = List.copyOf(requests);
		dirty = true;
		renderIfShowing();
	}

	/** Preserve cards while hidden and reconcile the latest snapshot when shown. */
	private void renderIfShowing() {
		if (!dirty || !isShowing()) return;
		dirty = false;
		Map<String, RequestCard> next = new LinkedHashMap<>();
		for (Inbox.Request r : requests) {
			String key = InboxBinding.key(r);
			RequestCard old = cards.get(key);
			RequestCard card;
			if (old != null && old.request().equals(r)) {
				card = old;
			} else {
				boolean expanded = (old != null) ? old.expanded() : r.open();
				card = new RequestCard(r, listener, expanded);
			}
			next.put(key, card);
		}
		cards.clear();
		cards.putAll(next);
		long open = requests.stream().filter(Inbox.Request::open).count();
		waiting.setText(open + " waiting");
		waiting.setVisible(open > 0);

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

	/** Principal changes and logout discard drafts, even while the screen is hidden. */
	public void clear() {
		cards.clear();
		column.removeAll();
		notice.setText(" ");
		setRequests(List.of());
	}

	/** A response completes only its own form; other requests may still be sending. */
	public void showNotice(Inbox.Request request, String text) {
		notice.setText(text);
		RequestCard card = cards.get(InboxBinding.key(request));
		if (card != null) card.form().setBusy(false);
	}

}
