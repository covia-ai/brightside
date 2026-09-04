package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import brightside.SessionHistory;
import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Scrolls;

/**
 * The conversation switcher: a "New conversation" button above a scrolling list
 * of past sessions (newest first), each a clickable row showing its title and
 * how long ago it was last active. The currently-open session is highlighted.
 *
 * <p>Kept dumb — it renders the sessions it's given and reports clicks through a
 * {@link Listener}; {@code BrightSide} owns starting a new chat and switching to
 * a past one.
 */
@SuppressWarnings("serial")
public final class ConversationList extends JPanel {

	/** Reports the user's intent; the app performs the action. */
	public interface Listener {
		void onNewConversation();

		void onSelectSession(String sessionId);

		/** New title, or empty/blank to clear back to the auto-derived one. */
		void onRenameSession(String sessionId, String newTitle);

		void onCopyTranscript(String sessionId);

		/** Show what the assistant's model actually receives for this conversation. */
		void onInspectSession(String sessionId);

		void onDeleteSession(String sessionId);
	}

	private static final int WIDTH = 240;

	private final Listener listener;
	private final JPanel rows = Panels.column();
	private final Map<String, Row> rowsById = new HashMap<>();
	private String selectedId;

	public ConversationList(Listener listener) {
		super(new BorderLayout());
		this.listener = listener;
		setOpaque(false);
		// Preferred width for the initial split; a small minimum so the split's
		// divider can narrow or fully collapse it.
		setPreferredSize(new Dimension(WIDTH, 0));
		setMinimumSize(new Dimension(0, 0));
		setBorder(BorderFactory.createCompoundBorder(
			Borders.hairlineRight(), BorderFactory.createEmptyBorder(10, 10, 10, 10)));

		JButton newChat = Buttons.primary("New conversation");
		newChat.setIcon(Lucide.icon("plus", 16, Color.WHITE));
		newChat.setFocusable(false);
		newChat.addActionListener(e -> listener.onNewConversation());
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		top.add(newChat, BorderLayout.CENTER);

		add(top, BorderLayout.NORTH);
		add(Scrolls.vertical(rows), BorderLayout.CENTER);
	}

	/** Replaces the list and highlights {@code selectedId} (null for none/new chat). */
	public void setSessions(List<SessionHistory.Session> sessions, String selectedId) {
		this.selectedId = selectedId;
		rows.removeAll();
		rowsById.clear();
		if (sessions.isEmpty()) {
			JLabel empty = Labels.small("No past conversations yet");
			empty.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			empty.setAlignmentX(LEFT_ALIGNMENT);
			rows.add(empty);
		} else {
			for (SessionHistory.Session s : sessions) rows.add(rowFor(s));
		}
		rows.add(Box.createVerticalGlue());
		rows.revalidate();
		rows.repaint();
	}

	/** Scrolls a conversation's row into view, once the list has laid out. */
	public void reveal(String sessionId) {
		Row row = rowsById.get(sessionId);
		if (row == null) return;
		SwingUtilities.invokeLater(() -> rows.scrollRectToVisible(row.getBounds()));
	}

	private Component rowFor(SessionHistory.Session s) {
		boolean selected = s.sessionId() != null && s.sessionId().equals(selectedId);
		Row row = new Row(s);
		row.setSelected(selected);
		row.onPress(() -> {
			if (!s.sessionId().equals(selectedId)) listener.onSelectSession(s.sessionId());
		});
		row.onPopup(() -> menuFor(s));
		if (s.sessionId() != null) rowsById.put(s.sessionId(), row);
		return row;
	}

	/** The right-click menu for one conversation. */
	private JPopupMenu menuFor(SessionHistory.Session s) {
		String sid = s.sessionId();
		JPopupMenu menu = new JPopupMenu();

		JMenuItem open = new JMenuItem("Open");
		open.setEnabled(!sid.equals(selectedId));
		open.addActionListener(e -> listener.onSelectSession(sid));
		menu.add(open);

		JMenuItem rename = new JMenuItem("Rename…");
		rename.addActionListener(e -> {
			String input = Dialogs.prompt(this, "Rename conversation", "Rename this conversation:", s.title());
			if (input != null) listener.onRenameSession(sid, input.trim());
		});
		menu.add(rename);

		JMenuItem copy = new JMenuItem("Copy transcript");
		copy.addActionListener(e -> listener.onCopyTranscript(sid));
		menu.add(copy);

		JMenuItem info = new JMenuItem("Context…");
		info.addActionListener(e -> listener.onInspectSession(sid));
		menu.add(info);

		menu.addSeparator();

		JMenuItem delete = new JMenuItem("Delete");
		delete.addActionListener(e -> {
			if (Dialogs.confirmDanger(this, "Delete conversation", "Delete this conversation? This can't be undone.")) {
				listener.onDeleteSession(sid);
			}
		});
		menu.add(delete);

		return menu;
	}

	/** A relative "3 min ago" / "yesterday" / date label for the last-active time. */
	public static String relativeTime(long ts) {
		if (ts <= 0) return "";
		long d = System.currentTimeMillis() - ts;
		if (d < 60_000L) return "just now";
		if (d < 3_600_000L) return plural(d / 60_000L, "minute");
		if (d < 86_400_000L) return plural(d / 3_600_000L, "hour");
		long days = d / 86_400_000L;
		if (days == 1) return "yesterday";
		if (days < 7) return days + " days ago";
		return new SimpleDateFormat("d MMM").format(new Date(ts));
	}

	private static String plural(long n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s") + " ago";
	}

	/**
	 * One conversation row: a {@link PressButton} laid out with its own labels —
	 * the title over a muted relative time — so the theme paints its hover and
	 * selected looks while the labels keep their own styles. The labels take no
	 * mouse events, so a press anywhere on the row reaches the button.
	 */
	@SuppressWarnings("serial")
	private static final class Row extends PressButton {

		Row(SessionHistory.Session s) {
			super("");
			setLayout(new BorderLayout(0, 1));
			setMargin(new Insets(7, 9, 7, 9));
			setAlignmentX(LEFT_ALIGNMENT);
			add(Labels.text(s.title()), BorderLayout.CENTER);
			add(Labels.small(relativeTime(s.lastTs())), BorderLayout.SOUTH);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}
}
