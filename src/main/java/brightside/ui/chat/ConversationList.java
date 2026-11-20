package brightside.ui.chat;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;
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
import javax.swing.JComboBox;
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
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The conversation switcher: a "+ Session" button above a scrolling list of
 * past sessions (newest first), each a clickable row showing its title and
 * how long ago it was last active. The currently-open session is highlighted.
 * It shows chats unless a quiet drop-down beside the button, there only when
 * some conversation began as a task, says otherwise ({@link Show}).
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

		/** Show the assistant's saved summary of this conversation, writing it first if new turns need it. */
		void onSummariseSession(String sessionId);

		/** Compact this conversation under a summary, keeping every message beneath it. */
		void onCompactSession(String sessionId);

		/** Show what the assistant's model actually receives for this conversation. */
		void onInspectSession(String sessionId);

		void onDeleteSession(String sessionId);
	}

	/**
	 * Which conversations the list shows, by how each began. Chats are the
	 * default; tasks are agent requests, mostly a schedule's runs.
	 *
	 * <p>A session's first message is all Covia records of its origin, and a
	 * chat from the app looks the same as one from Claude Code over MCP or from
	 * a channel. Once Covia records where a session came from — the schedule
	 * that started it (covia-ai/covia#701), the client and the door it came in
	 * by — this can filter more finely and group a schedule's runs together.
	 */
	public enum Show {
		CHATS("Chats"), TASKS("Tasks"), ALL("All");

		private final String label;

		Show(String label) {
			this.label = label;
		}

		/** Whether a session that began as a task, or not, is shown. */
		public boolean includes(boolean task) {
			return this == ALL || (this == TASKS) == task;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static final int WIDTH = 240;

	private final Listener listener;
	private final JPanel rows = Panels.column();
	private final Map<String, Row> rowsById = new HashMap<>();
	private final JComboBox<Show> show = new JComboBox<>(Show.values());
	private List<SessionHistory.Session> sessions = List.of();
	private String selectedId;
	/**
	 * When each conversation was last seen open, by its last activity then:
	 * a later activity marks the row as new until it is opened. First sight
	 * counts as seen, so nothing is marked on start. Kept for this run.
	 */
	private final java.util.Map<String, Long> seenAt = new java.util.HashMap<>();

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

		JButton newChat = Buttons.tinted("Chat", Lucide.icon("plus", 16, Theme::accentText));
		newChat.setToolTipText("New chat");
		newChat.setFocusable(false);
		newChat.addActionListener(e -> listener.onNewConversation());
		// Beside the button, quiet: there only while some conversation began as
		// a task.
		Styles.classes(show, "quiet");
		show.setFocusable(false);
		show.setToolTipText("Chats, or tasks a schedule or another agent sent");
		show.addActionListener(e -> render());
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		top.add(newChat, BorderLayout.CENTER);
		top.add(show, BorderLayout.EAST);

		add(top, BorderLayout.NORTH);
		add(Scrolls.vertical(rows), BorderLayout.CENTER);
	}

	/** Replaces the list and highlights {@code selectedId} (null for none/new chat). */
	public void setSessions(List<SessionHistory.Session> sessions, String selectedId) {
		this.sessions = sessions;
		this.selectedId = selectedId;
		render();
	}

	/**
	 * The sessions {@code show} lets through, in order; the open one,
	 * {@code selectedId}, always, so the list never hides what is on screen.
	 */
	public static List<SessionHistory.Session> shown(List<SessionHistory.Session> sessions, Show show,
			String selectedId) {
		return sessions.stream()
			.filter(s -> show.includes(s.task()) || s.sessionId().equals(selectedId))
			.toList();
	}

	private void render() {
		Show which = (Show) show.getSelectedItem();
		show.setVisible(which != Show.CHATS || sessions.stream().anyMatch(SessionHistory.Session::task));
		List<SessionHistory.Session> visible = shown(sessions, which, selectedId);
		rows.removeAll();
		rowsById.clear();
		if (visible.isEmpty()) {
			JLabel empty = Labels.small(sessions.isEmpty() ? "No past conversations yet"
				: (which == Show.TASKS) ? "No tasks" : "No chats");
			empty.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			empty.setAlignmentX(LEFT_ALIGNMENT);
			rows.add(empty);
		} else {
			for (SessionHistory.Session s : visible) rows.add(rowFor(s));
		}
		rows.add(Box.createVerticalGlue());
		rows.revalidate();
		rows.repaint();
		revalidate();
	}

	/** Scrolls a conversation's row into view, once the list has laid out. */
	public void reveal(String sessionId) {
		Row row = rowsById.get(sessionId);
		if (row == null) return;
		SwingUtilities.invokeLater(() -> rows.scrollRectToVisible(row.getBounds()));
	}

	private Component rowFor(SessionHistory.Session s) {
		boolean selected = s.sessionId() != null && s.sessionId().equals(selectedId);
		boolean fresh = false;
		if (s.sessionId() != null) {
			Long seen = seenAt.get(s.sessionId());
			if (seen == null || selected) seenAt.put(s.sessionId(), s.lastTs());
			else fresh = s.lastTs() > seen;
		}
		Row row = new Row(s, fresh);
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

		JMenuItem summarise = new JMenuItem("Summarise");
		summarise.setToolTipText(APP_NAME + "'s summary of this conversation; the conversation itself is unchanged");
		summarise.addActionListener(e -> listener.onSummariseSession(sid));
		menu.add(summarise);

		JMenuItem compact = new JMenuItem("Compact…");
		compact.setToolTipText("Carry this conversation on from a summary, keeping every message beneath it");
		compact.addActionListener(e -> listener.onCompactSession(sid));
		menu.add(compact);

		JMenuItem info = new JMenuItem("Context");
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

		/** {@code fresh}: new activity since it was last open; a dot and the word "New" say so. */
		final boolean fresh;

		Row(SessionHistory.Session s, boolean fresh) {
			super("");
			this.fresh = fresh;
			setLayout(new BorderLayout(0, 1));
			setMargin(new Insets(brightside.ui.components.Density.pad(7), 9, brightside.ui.components.Density.pad(7), 9));
			setAlignmentX(LEFT_ALIGNMENT);
			JLabel title = Labels.text(s.title());
			if (fresh) {
				Styles.classes(title, Styles.STRONG);
				title.setIcon(new javax.swing.Icon() {
					@Override
					public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
						java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
						g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
						g2.setColor(Theme.accentText());
						int d = getIconWidth();
						g2.fillOval(x, y + (getIconHeight() - d) / 2, d, d);
						g2.dispose();
					}

					@Override
					public int getIconWidth() {
						return com.formdev.flatlaf.util.UIScale.scale(7);
					}

					@Override
					public int getIconHeight() {
						return getIconWidth();
					}
				});
				title.setIconTextGap(com.formdev.flatlaf.util.UIScale.scale(6));
			}
			add(title, BorderLayout.CENTER);
			add(Labels.small(relativeTime(s.lastTs()) + (fresh ? "  ·  New" : "")), BorderLayout.SOUTH);
			getAccessibleContext().setAccessibleName(s.title() + (fresh ? ", new activity" : ""));
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}
}
