package covia.brightside.ui.inbox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import covia.brightside.Inbox;
import covia.brightside.ui.LAF;
import covia.brightside.ui.chat.ConversationList;

/**
 * The <b>Inbox</b> screen: every request in the owner's {@code h/} inbox, open
 * ones first, in a list on the left; the selected request in full on the right
 * ({@link RequestForm}). One place for everything any agent is waiting on.
 *
 * <p>Renders what it is given; the app keeps it fresh from the lattice and
 * performs the responses.
 */
@SuppressWarnings("serial")
public final class InboxScreen extends JPanel {

	private static final int LIST_WIDTH = 300;

	private final RequestForm.Listener listener;
	private final DefaultListModel<Inbox.Request> model = new DefaultListModel<>();
	private final JList<Inbox.Request> list = new JList<>(model);
	private final JPanel detail = new JPanel(new BorderLayout());
	private final JLabel notice = new JLabel(" ");
	private RequestForm form;
	private String selectedId;
	private boolean syncing;

	public InboxScreen(RequestForm.Listener listener) {
		super(new BorderLayout());
		this.listener = listener;

		JLabel header = new JLabel("Inbox");
		header.putClientProperty("FlatLaf.styleClass", "small");
		header.setForeground(muted());
		header.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 10));

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new Row());
		list.setOpaque(false);
		list.addListSelectionListener(e -> {
			if (syncing || e.getValueIsAdjusting()) return;
			Inbox.Request r = list.getSelectedValue();
			selectedId = (r != null) ? r.id() : null;
			render(r);
		});
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);

		JPanel left = new JPanel(new BorderLayout());
		left.setOpaque(false);
		left.setPreferredSize(new Dimension(LIST_WIDTH, 0));
		left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, line()));
		left.add(header, BorderLayout.NORTH);
		left.add(scroll, BorderLayout.CENTER);

		detail.setOpaque(false);
		JScrollPane detailScroll = new JScrollPane(detail);
		detailScroll.setBorder(BorderFactory.createEmptyBorder());
		detailScroll.getVerticalScrollBar().setUnitIncrement(24);
		notice.putClientProperty("FlatLaf.styleClass", "small");
		notice.putClientProperty("html.disable", Boolean.TRUE);
		notice.setForeground(muted());
		notice.setBorder(BorderFactory.createEmptyBorder(6, 18, 8, 18));
		JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		right.add(detailScroll, BorderLayout.CENTER);
		right.add(notice, BorderLayout.SOUTH);

		add(left, BorderLayout.WEST);
		add(right, BorderLayout.CENTER);
		render(null);
	}

	/** Replace the list (open first). Keeps the selection; a form being filled in stays put unless its request changed. */
	public void setRequests(List<Inbox.Request> requests) {
		Inbox.Request keep = null;
		syncing = true;
		try {
			model.clear();
			for (Inbox.Request r : requests) {
				model.addElement(r);
				if (r.id().equals(selectedId)) keep = r;
			}
			if (keep == null && !requests.isEmpty()) keep = requests.get(0);
			if (keep != null) list.setSelectedValue(keep, true);
		} finally {
			syncing = false;
		}
		selectedId = (keep != null) ? keep.id() : null;
		if (keep != null && form != null && keep.equals(form.request())) return;
		render(keep);
	}

	/** A line under the request: the result of the last answer or rejection. */
	public void showNotice(String text) {
		notice.setText(text);
		if (form != null) form.setBusy(false);
	}

	private void render(Inbox.Request r) {
		detail.removeAll();
		form = null;
		if (r == null) {
			JLabel empty = new JLabel(model.isEmpty() ? "Nothing is waiting for you." : "Select a request.");
			empty.setForeground(muted());
			empty.setBorder(BorderFactory.createEmptyBorder(24, 18, 24, 18));
			detail.add(empty, BorderLayout.NORTH);
		} else {
			form = new RequestForm(r, listener);
			detail.add(form, BorderLayout.NORTH);
		}
		detail.revalidate();
		detail.repaint();
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	private static Color line() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}

	/** One request row: title over "who · when · status"; open requests in bold. Plain text only. */
	private static final class Row extends JPanel implements ListCellRenderer<Inbox.Request> {
		private final JLabel title = new JLabel();
		private final JLabel meta = new JLabel();

		Row() {
			super(new BorderLayout(0, 2));
			setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
			title.putClientProperty("html.disable", Boolean.TRUE);
			meta.putClientProperty("html.disable", Boolean.TRUE);
			meta.putClientProperty("FlatLaf.styleClass", "small");
			add(title, BorderLayout.CENTER);
			add(meta, BorderLayout.SOUTH);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Inbox.Request> l, Inbox.Request r,
				int index, boolean selected, boolean focus) {
			title.setText(r.title());
			title.setFont(title.getFont().deriveFont(r.open() ? Font.BOLD : Font.PLAIN));
			String who = (r.agent() != null) ? r.agent() : (r.from() != null) ? r.from() : "unknown";
			meta.setText(who + "  ·  " + ConversationList.relativeTime(r.created())
				+ (r.open() ? "" : "  ·  " + r.status()));
			setOpaque(selected);
			setBackground(selected ? LAF.ACCENT : null);
			Color fg = selected ? Color.WHITE : UIManager.getColor("Label.foreground");
			title.setForeground(fg);
			meta.setForeground(selected ? Color.WHITE : muted());
			return this;
		}
	}
}
