package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import covia.brightside.SessionHistory;
import covia.brightside.ui.LAF;

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
	private final JPanel rows = new JPanel();
	private String selectedId;

	public ConversationList(Listener listener) {
		super(new BorderLayout());
		this.listener = listener;
		setOpaque(false);
		setPreferredSize(new Dimension(WIDTH, 0));
		setMinimumSize(new Dimension(WIDTH, 0));
		Color line = uiColor("Separator.foreground", Color.GRAY);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, line),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));

		JButton newChat = new JButton("New conversation");
		newChat.putClientProperty("JButton.buttonType", "roundRect");
		newChat.setForeground(Color.WHITE);
		newChat.setBackground(LAF.ACCENT);
		newChat.setFocusable(false);
		newChat.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		newChat.addActionListener(e -> listener.onNewConversation());
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		top.add(newChat, BorderLayout.CENTER);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		JScrollPane scroll = new JScrollPane(rows,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.getVerticalScrollBar().setUnitIncrement(24);

		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
	}

	/** Replaces the list and highlights {@code selectedId} (null for none/new chat). */
	public void setSessions(List<SessionHistory.Session> sessions, String selectedId) {
		this.selectedId = selectedId;
		rows.removeAll();
		if (sessions.isEmpty()) {
			JLabel empty = new JLabel("No past conversations yet");
			empty.putClientProperty("FlatLaf.styleClass", "small");
			empty.setForeground(ChatStyle.muted());
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

	private Component rowFor(SessionHistory.Session s) {
		boolean selected = s.sessionId() != null && s.sessionId().equals(selectedId);
		Row row = new Row(s, selected);
		row.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				// Select on press for a snappier feel; right-click opens the menu.
				if (e.isPopupTrigger()) {
					menuFor(s).show(row, e.getX(), e.getY());
				} else if (SwingUtilities.isLeftMouseButton(e) && !s.sessionId().equals(selectedId)) {
					listener.onSelectSession(s.sessionId());
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) menuFor(s).show(row, e.getX(), e.getY());
			}
		});
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
			Object input = JOptionPane.showInputDialog(this, "Rename this conversation:",
				"Rename conversation", JOptionPane.PLAIN_MESSAGE, null, null, s.title());
			if (input != null) listener.onRenameSession(sid, input.toString().trim());
		});
		menu.add(rename);

		JMenuItem copy = new JMenuItem("Copy transcript");
		copy.addActionListener(e -> listener.onCopyTranscript(sid));
		menu.add(copy);

		JMenuItem info = new JMenuItem("What the assistant sees…");
		info.addActionListener(e -> listener.onInspectSession(sid));
		menu.add(info);

		menu.addSeparator();

		JMenuItem delete = new JMenuItem("Delete");
		delete.addActionListener(e -> {
			int choice = JOptionPane.showConfirmDialog(this,
				"Delete this conversation? This can't be undone.",
				"Delete conversation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.OK_OPTION) listener.onDeleteSession(sid);
		});
		menu.add(delete);

		return menu;
	}

	/** A relative "3 min ago" / "yesterday" / date label for the last-active time. */
	static String relativeTime(long ts) {
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

	private static Color uiColor(String key, Color fallback) {
		Color c = javax.swing.UIManager.getColor(key);
		return (c != null) ? c : fallback;
	}

	/** One conversation row: title over a muted relative time, tinted when selected. */
	@SuppressWarnings("serial")
	private final class Row extends JPanel {

		Row(SessionHistory.Session s, boolean selected) {
			super(new BorderLayout(0, 1));
			setOpaque(selected);
			if (selected) setBackground(ChatStyle.mix(uiColor("Panel.background", Color.DARK_GRAY), LAF.ACCENT, 0.22f));
			setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(LEFT_ALIGNMENT);

			JLabel title = new JLabel(s.title());
			if (selected) title.setForeground(ChatStyle.foreground());
			JLabel when = new JLabel(relativeTime(s.lastTs()));
			when.putClientProperty("FlatLaf.styleClass", "small");
			when.setForeground(ChatStyle.muted());

			add(title, BorderLayout.CENTER);
			add(when, BorderLayout.SOUTH);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}
}
