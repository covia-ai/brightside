package brightside.ui.inbox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Inbox;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.StatusBadge;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.shell.ShellLayout;

/**
 * The Inbox panel: docked beside Home or Jobs on wide windows while requests
 * wait, on the chrome ground. Each waiting request is a small card (what it
 * asks, who asked, when it expires) that opens its full decision card in the
 * Inbox, where it is answered, so the view keeps its one primary there.
 */
@SuppressWarnings("serial")
public final class InboxPanel extends JPanel {

	private final JPanel column = Panels.column();
	private final JLabel heading = Labels.text("Inbox");
	private final Consumer<Inbox.Request> open;

	/** {@code open}: opens a request's card in the Inbox. */
	public InboxPanel(Consumer<Inbox.Request> open) {
		super(new BorderLayout());
		this.open = open;
		setPreferredSize(new Dimension(ShellLayout.SIDE, 0));
		setBorder(brightside.ui.components.Borders.hairline(false, true, false, false));
		Styles.classes(heading, Styles.SECTION);
		heading.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
		column.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		column.setOpaque(false);
		add(heading, BorderLayout.NORTH);
		add(Scrolls.vertical(Scrolls.hugTop(column)), BorderLayout.CENTER);
		getAccessibleContext().setAccessibleName("Inbox panel");
	}

	@Override
	public Color getBackground() {
		return Theme.chrome();
	}

	/** The open requests, newest first; the panel lists only those waiting. */
	public void setRequests(List<Inbox.Request> requests) {
		column.removeAll();
		List<Inbox.Request> open = requests.stream().filter(Inbox.Request::open).toList();
		heading.setText(open.isEmpty() ? "Inbox" : "Inbox · " + open.size() + " waiting");
		long now = System.currentTimeMillis();
		for (Inbox.Request r : open) {
			column.add(card(r, now));
			column.add(Box.createVerticalStrut(8));
		}
		column.revalidate();
		column.repaint();
	}

	/** How many are listed, for the window's layout and for tests. */
	public int waiting() {
		return (int) java.util.Arrays.stream(column.getComponents()).filter(c -> c instanceof Card).count();
	}

	private JPanel card(Inbox.Request r, long now) {
		Card card = new Card(Theme.cardArc()).fill(Theme::card);
		card.setLayout(new BorderLayout(0, 4));
		card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		card.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = Labels.text(r.title());
		Styles.classes(title, Styles.STRONG);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		JPanel top = new JPanel(new BorderLayout(8, 0));
		top.setOpaque(false);
		top.add(title, BorderLayout.CENTER);
		card.add(top, BorderLayout.NORTH);
		// Who asked, when, and when it expires: wraps, so the expiry is never cut off.
		brightside.ui.components.SelectableText meta = brightside.ui.components.SelectableText.description(
			RequestForm.meta(r)).small();
		card.add(meta, BorderLayout.CENTER);
		JPanel foot = new JPanel(new BorderLayout());
		foot.setOpaque(false);
		foot.add(StatusBadge.waiting(), BorderLayout.WEST);
		foot.add(Buttons.link("Answer in Inbox", () -> open.accept(r)), BorderLayout.EAST);
		card.add(foot, BorderLayout.SOUTH);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}
}
