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
	/** Who the app is acting as: a request from this identity can't be approved by it. */
	private String actingDid;
	/** The card holding the view's one filled primary: the one being worked on. */
	private RequestCard active;
	private final java.util.Set<RequestCard> wired = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

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
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				renderIfShowing();
				// On opening the Inbox, the card being worked on, and its primary, are in view.
				if (isShowing() && active != null) {
					javax.swing.SwingUtilities.invokeLater(() -> active.scrollRectToVisible(
						new java.awt.Rectangle(0, 0, active.getWidth(), Math.min(active.getHeight(), 400))));
				}
			}
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
		render();
	}

	/** Reconciles the cards now, shown or not: for tests. */
	void renderNow() {
		dirty = true;
		render();
	}

	private void render() {
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
		String soonest = summary(requests, System.currentTimeMillis());
		waiting.setText(soonest != null ? soonest : " ");
		waiting.setVisible(open > 0 && soonest != null);
		for (RequestCard card : cards.values()) {
			card.form().setSelfRequest(card.request().open() && actingDid != null && actingDid.equals(card.request().from()));
			if (wired.add(card)) {
				card.form().onEngaged(() -> activate(card));
				card.onToggle(() -> {
					if (card.expanded()) activate(card);
				});
			}
		}
		if (active == null || !cards.containsValue(active) || !active.expanded()) {
			active = cards.values().stream().filter(c -> c.request().open() && c.expanded()).findFirst().orElse(null);
		}
		activate(active);

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

	/** Moves the view's one filled primary to {@code card}; every other card shows its action as a secondary. */
	private void activate(RequestCard card) {
		active = card;
		for (RequestCard c : cards.values()) c.form().setPrimaryActive(c == card);
	}

	/** Brings {@code request}'s card forward: unfolded, holding the primary, and scrolled into view. */
	public void focus(Inbox.Request request) {
		RequestCard card = cards.get(InboxBinding.key(request));
		if (card == null) return;
		card.setExpanded(true);
		activate(card);
		javax.swing.SwingUtilities.invokeLater(() -> card.scrollRectToVisible(
			new java.awt.Rectangle(0, 0, card.getWidth(), Math.min(card.getHeight(), 400))));
	}

	/** Who the app is acting as, by DID, for the self-approval guard; null when signed out. */
	public void setActing(String did) {
		actingDid = did;
		dirty = true;
		renderIfShowing();
	}

	/**
	 * "Next expires in 28 min": the soonest expiry among the open requests, or
	 * null when none expires. The count is the status strip's and the sidebar's.
	 */
	static String summary(List<Inbox.Request> requests, long now) {
		long next = requests.stream().filter(Inbox.Request::open).mapToLong(Inbox.Request::expires)
			.filter(t -> t > now).min().orElse(0);
		if (next == 0) return null;
		long mins = Math.max(1, (next - now + 59_999) / 60_000);
		String in = (mins < 120) ? mins + " min" : (mins / 60) + " h";
		return "Next expires in " + in;
	}

	/** The requests' cards in order, for tests. */
	List<RequestCard> cardsShown() {
		return List.copyOf(cards.values());
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
