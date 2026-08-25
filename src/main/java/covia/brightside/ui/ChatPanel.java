package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.DefaultEditorKit;

import covia.brightside.SessionHistory;
import covia.brightside.chat.ChatSession;

/**
 * The chat: a scrolling column of message components above a rounded input box
 * and an accent Send button. Each message is its own rounded {@link Bubble}
 * component (user right/accent, assistant left/surface) — kept as separate
 * components so new message kinds (images, cards, tool output) can be added as
 * their own row types later. Text within a bubble is selectable; a right-click
 * offers <em>Copy message</em> and <em>Copy conversation</em> to get text out
 * across messages. Enter sends, Shift+Enter inserts a newline.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

	private static final Color ERROR_RED = new Color(0xE5, 0x53, 0x53);

	private final MessageColumn column = new MessageColumn();
	private final JScrollPane scroll;
	private final JTextArea input = new JTextArea(1, 20);
	private final JButton send = new JButton("Send");

	private final List<SessionHistory.Item> displayed = new ArrayList<>();
	private JTextArea lastSelectedBubble; // the bubble holding the current selection, if any
	private Component thinkingRow; // the assistant "typing…" row while a reply is pending
	private TypingIndicator thinkingIndicator;
	private volatile ChatSession session;
	private boolean busy;

	public ChatPanel() {
		super(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

		scroll = new JScrollPane(column);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		scroll.getViewport().addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				column.setAvailableWidth(scroll.getViewport().getWidth());
			}
		});

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setFont(input.getFont().deriveFont(input.getFont().getSize2D() + 1f));
		input.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
		input.putClientProperty("JTextArea.placeholderText", "Message Brightside…");
		input.getInputMap(JComponent.WHEN_FOCUSED)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
		input.getInputMap(JComponent.WHEN_FOCUSED)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction);
		input.getActionMap().put("send", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				send();
			}
		});
		// The input keeps focus, so Ctrl/Cmd+C lands here: copy the input's own
		// selection, or fall back to the last bubble the user selected in.
		KeyStroke copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C,
			Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
		input.getInputMap(JComponent.WHEN_FOCUSED).put(copyKey, "smartCopy");
		input.getActionMap().put("smartCopy", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (hasSelection(input)) {
					input.copy();
				} else if (hasSelection(lastSelectedBubble)) {
					toClipboard(lastSelectedBubble.getSelectedText());
				}
			}
		});

		JScrollPane inputScroll = new JScrollPane(input,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		inputScroll.putClientProperty("FlatLaf.style", "arc: 16");
		inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

		send.putClientProperty("JButton.buttonType", "roundRect");
		send.setForeground(Color.WHITE);
		send.setBackground(LAF.ACCENT);
		send.addActionListener(e -> send());

		JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		bottom.add(inputScroll, BorderLayout.CENTER);
		JPanel sendWrap = new JPanel(new BorderLayout());
		sendWrap.add(send, BorderLayout.SOUTH);
		bottom.add(sendWrap, BorderLayout.EAST);

		add(scroll, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		setInputEnabled(false);
	}

	// ------------------------------------------------------------------
	// Wiring
	// ------------------------------------------------------------------

	/** Connects a live session and opens the input box (does not clear the transcript). */
	public void setSession(ChatSession session) {
		this.session = session;
		setInputEnabled(true);
		focusInput();
	}

	/** Replaces the transcript with the venue's live conversation turns. */
	public void restore(List<SessionHistory.Item> items) {
		column.clear();
		displayed.clear();
		for (SessionHistory.Item it : items) {
			displayed.add(it);
			column.add(rowFor(it));
		}
		column.revalidate();
		column.repaint();
		scrollToBottom();
	}

	private Component rowFor(SessionHistory.Item it) {
		if (it instanceof SessionHistory.Message m) return bubbleRow(m.text(), "user".equals(m.role()));
		if (it instanceof SessionHistory.Activity a) return activityRow(a);
		return new JPanel();
	}

	/** Re-render only if the live items differ from what's shown (avoids flicker). */
	public void refreshTo(List<SessionHistory.Item> live) {
		// While a send is in flight the message sits in the agent's pending queue
		// before it's minted into the conversation, so a mid-flight read wouldn't
		// yet include it — don't clobber the optimistic bubble. The reply's own
		// re-read reconciles once it lands.
		if (busy) return;
		if (live.equals(displayed)) return;
		restore(live);
	}

	public void clearMessages() {
		column.clear();
		displayed.clear();
		column.revalidate();
		column.repaint();
	}

	public void focusInput() {
		// After the window/layout settles, so the request actually lands.
		SwingUtilities.invokeLater(input::requestFocusInWindow);
	}

	private void setInputEnabled(boolean on) {
		input.setEnabled(on);
		send.setEnabled(on);
	}

	// ------------------------------------------------------------------
	// Sending
	// ------------------------------------------------------------------

	private void send() {
		ChatSession s = session;
		if (s == null || busy) return;
		String text = input.getText().trim();
		if (text.isEmpty()) return;
		input.setText("");
		addTurn("user", text);
		busy = true;
		setInputEnabled(false);
		showThinking();

		new SwingWorker<ChatSession.Reply, Void>() {
			@Override
			protected ChatSession.Reply doInBackground() throws Exception {
				return s.send(text);
			}

			@Override
			protected void done() {
				busy = false;
				setInputEnabled(true);
				hideThinking();
				try {
					addTurn("assistant", get().text());
				} catch (ExecutionException e) {
					appendError(describe(e.getCause() != null ? e.getCause() : e));
				} catch (Exception e) {
					appendError(describe(e));
				}
				focusInput();
			}
		}.execute();
	}

	private void addTurn(String role, String text) {
		// The venue records the turn in the agent session; the UI reflects it
		// optimistically and keeps `displayed` in step so the watcher's compare
		// doesn't re-render our own turns.
		displayed.add(new SessionHistory.Message(role, text));
		column.add(bubbleRow(text, "user".equals(role)));
		column.revalidate();
		scrollToBottom();
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.toString() : m;
	}

	/** Show an animated "typing…" bubble on the assistant side while a reply is pending. */
	private void showThinking() {
		if (thinkingRow != null) return;
		JPanel bubble = roundBubble(assistantBg());
		bubble.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
		thinkingIndicator = new TypingIndicator(muted());
		bubble.add(thinkingIndicator, BorderLayout.CENTER);
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		row.add(bubble, BorderLayout.WEST);
		thinkingRow = row;
		column.add(row);
		column.revalidate();
		scrollToBottom();
		thinkingIndicator.start();
	}

	private void hideThinking() {
		if (thinkingRow == null) return;
		if (thinkingIndicator != null) {
			thinkingIndicator.stop();
			thinkingIndicator = null;
		}
		column.remove(thinkingRow);
		thinkingRow = null;
		column.revalidate();
		column.repaint();
	}

	@SuppressWarnings("serial")
	private static JPanel roundBubble(Color bg) {
		JPanel p = new JPanel(new BorderLayout()) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(bg);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		p.setOpaque(false);
		return p;
	}

	/** A note from Brightside itself (status, hints) — centred and muted. */
	public void appendSystem(String text) {
		column.add(noticeRow(text, muted(), false));
		column.revalidate();
		scrollToBottom();
	}

	public void appendError(String text) {
		column.add(noticeRow(text, ERROR_RED, true));
		column.revalidate();
		scrollToBottom();
	}

	private void scrollToBottom() {
		SwingUtilities.invokeLater(() -> {
			var bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	// ------------------------------------------------------------------
	// Copy
	// ------------------------------------------------------------------

	/** The whole conversation as plain text ("You:" / "Brightside:" turns). */
	public String conversationText() {
		StringBuilder sb = new StringBuilder();
		for (SessionHistory.Item it : displayed) {
			if (it instanceof SessionHistory.Message m) {
				String who = "user".equals(m.role()) ? "You" : "Brightside";
				sb.append(who).append(": ").append(m.text()).append("\n\n");
			}
		}
		return sb.toString().stripTrailing();
	}

	private static void toClipboard(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private static boolean hasSelection(JTextArea ta) {
		return ta != null && ta.getSelectionStart() != ta.getSelectionEnd();
	}

	/** Right-click menu on a bubble: copy its selection/message, or the whole conversation. */
	private void showBubbleMenu(JTextArea ta, String messageText, Component invoker, int x, int y) {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy message");
		copy.addActionListener(e -> {
			String sel = ta.getSelectedText();
			toClipboard((sel != null && !sel.isEmpty()) ? sel : messageText);
		});
		JMenuItem copyAll = new JMenuItem("Copy conversation");
		copyAll.addActionListener(e -> toClipboard(conversationText()));
		menu.add(copy);
		menu.add(copyAll);
		menu.show(invoker, x, y);
	}

	// ------------------------------------------------------------------
	// Rows
	// ------------------------------------------------------------------

	private Component bubbleRow(String text, boolean user) {
		Color bg = user ? LAF.ACCENT : assistantBg();
		Color fg = user ? Color.WHITE : UIManager.getColor("Label.foreground");
		Bubble bubble = new Bubble(text, bg, fg);
		bubble.setAvailableWidth(scroll.getViewport().getWidth());

		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		row.add(bubble, user ? BorderLayout.EAST : BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private Component noticeRow(String text, Color fg, boolean bold) {
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(fg);
		label.putClientProperty("FlatLaf.styleClass", "small");
		if (bold) label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | java.awt.Font.BOLD));
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(6, 24, 6, 24));
		row.add(label, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/** An assistant-side, collapsed-by-default group of a turn's tool-use steps. */
	private Component activityRow(SessionHistory.Activity a) {
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		row.add(new ExpandableActivity(a), BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
	}

	private static String truncate(String s, int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "…";
	}

	private static JLabel htmlLabel(String text, Color fg, boolean italic) {
		String style = "width:440px;" + (italic ? "font-style:italic;" : "");
		JLabel l = new JLabel("<html><div style='" + style + "'>" + escapeHtml(text) + "</div></html>");
		l.setForeground(fg);
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	/** Elevated surface for the assistant's bubbles, derived from the theme. */
	private static Color assistantBg() {
		Color base = UIManager.getColor("Panel.background");
		if (base == null) base = new Color(0x2B, 0x2B, 0x2B);
		boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 128;
		return dark ? mix(base, Color.WHITE, 0.12f) : mix(base, Color.BLACK, 0.06f);
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(
			Math.round(a.getRed() * (1 - t) + b.getRed() * t),
			Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
			Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
	}

	/** A three-dot "typing…" animation. */
	@SuppressWarnings("serial")
	private static final class TypingIndicator extends JComponent {
		private static final int COUNT = 3;
		private static final int DOT = 8;
		private static final int GAP = 6;

		private final Color color;
		private final Timer timer;
		private int phase;

		TypingIndicator(Color color) {
			this.color = color;
			setOpaque(false);
			setPreferredSize(new Dimension(COUNT * DOT + (COUNT - 1) * GAP, DOT + 6));
			// COUNT+1 phases: each dot lifts in turn, then a brief rest.
			timer = new Timer(280, e -> {
				phase = (phase + 1) % (COUNT + 1);
				repaint();
			});
		}

		void start() {
			phase = 0;
			timer.start();
		}

		void stop() {
			timer.stop();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int y = (getHeight() - DOT) / 2;
			for (int i = 0; i < COUNT; i++) {
				boolean active = (i == phase);
				int alpha = active ? 235 : 110;
				int lift = active ? 2 : 0;
				g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
				g2.fillOval(i * (DOT + GAP), y - lift, DOT, DOT);
			}
			g2.dispose();
		}
	}

	/** The scrolling column of rows; tracks the viewport width so rows can align. */
	private static final class MessageColumn extends JPanel implements Scrollable {
		MessageColumn() {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
		}

		void setAvailableWidth(int viewportWidth) {
			for (Component row : getComponents()) {
				if (row instanceof JPanel p) {
					for (Component c : p.getComponents()) {
						if (c instanceof Bubble b) b.setAvailableWidth(viewportWidth);
					}
				}
			}
			revalidate();
		}

		void clear() {
			removeAll();
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
			return r.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	/** A collapsed "tool steps" chip that expands to show a turn's tool use. */
	@SuppressWarnings("serial")
	private final class ExpandableActivity extends JPanel {
		private static final Color OK_GREEN = new Color(0x3F, 0xB9, 0x50);

		private final JLabel header;
		private final JPanel body;
		private final int toolCount;
		private boolean expanded;

		ExpandableActivity(SessionHistory.Activity a) {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			toolCount = (int) a.steps().stream().filter(SessionHistory.Step::tool).count();

			header = new JLabel();
			header.putClientProperty("FlatLaf.styleClass", "small");
			header.setForeground(muted());
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
			header.setAlignmentX(LEFT_ALIGNMENT);
			header.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					toggle();
				}
			});
			updateHeader();

			body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			body.setBorder(BorderFactory.createEmptyBorder(2, 12, 6, 4));
			body.setVisible(false);
			body.setAlignmentX(LEFT_ALIGNMENT);
			for (SessionHistory.Step s : a.steps()) body.add(stepComponent(s));

			add(header);
			add(body);
		}

		private String summary() {
			if (toolCount == 1) return "1 tool step";
			if (toolCount > 1) return toolCount + " tool steps";
			return "details";
		}

		private void updateHeader() {
			header.setText((expanded ? "▾  " : "▸  ") + summary());
		}

		private void toggle() {
			expanded = !expanded;
			body.setVisible(expanded);
			updateHeader();
			revalidate();
			column.revalidate();
		}

		private Component stepComponent(SessionHistory.Step s) {
			if (!s.tool()) {
				return htmlLabel(s.detail(), muted(), true);
			}
			JPanel p = new JPanel();
			p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
			p.setOpaque(false);
			p.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
			p.setAlignmentX(LEFT_ALIGNMENT);
			JLabel title = new JLabel((s.error() ? "✕ " : "✓ ") + s.title());
			title.setForeground(s.error() ? ERROR_RED : OK_GREEN);
			title.putClientProperty("FlatLaf.styleClass", "small");
			title.setAlignmentX(LEFT_ALIGNMENT);
			p.add(title);
			if (s.detail() != null && !s.detail().isBlank()) {
				p.add(htmlLabel(truncate(s.detail(), 800), muted(), false));
			}
			return p;
		}

		@Override
		public Dimension getMaximumSize() {
			Dimension p = getPreferredSize();
			return new Dimension(Math.min(p.width, 680), p.height);
		}
	}

	/** A rounded message bubble wrapping a wrapping, selectable text area. */
	private final class Bubble extends JPanel {
		private static final int ARC = 20;
		private static final int PAD_H = 14;
		private static final int PAD_V = 10;

		private final JTextArea ta;
		private final Color bg;
		private int maxWidth = 460;

		Bubble(String text, Color bg, Color fg) {
			super(new BorderLayout());
			this.bg = bg;
			setOpaque(false);
			ta = new JTextArea(text);
			ta.setEditable(false);
			ta.setLineWrap(true);
			ta.setWrapStyleWord(true);
			ta.setOpaque(false);
			ta.setForeground(fg);
			// A read-only bubble never takes keyboard focus (so the input keeps it)
			// and shows no insert caret — but stays mouse-selectable, with the
			// selection painted even without focus.
			ta.setFocusable(false);
			javax.swing.text.DefaultCaret caret = new javax.swing.text.DefaultCaret() {
				@Override
				public void setVisible(boolean visible) {
					super.setVisible(false);
				}

				@Override
				public void setSelectionVisible(boolean visible) {
					super.setSelectionVisible(true);
				}
			};
			caret.setBlinkRate(0);
			ta.setCaret(caret);
			caret.setSelectionVisible(true);
			// Remember this bubble as the copy source while it holds a selection.
			ta.addCaretListener(e -> {
				if (e.getDot() != e.getMark()) lastSelectedBubble = ta;
			});
			ta.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
			ta.setFont(ta.getFont().deriveFont(ta.getFont().getSize2D() + 1f));
			MouseAdapter popup = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (e.isPopupTrigger()) showBubbleMenu(ta, text, ta, e.getX(), e.getY());
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					if (e.isPopupTrigger()) showBubbleMenu(ta, text, ta, e.getX(), e.getY());
				}
			};
			ta.addMouseListener(popup);
			add(ta, BorderLayout.CENTER);
		}

		void setAvailableWidth(int viewportWidth) {
			int w = (viewportWidth > 0) ? (int) (viewportWidth * 0.78) : 460;
			maxWidth = Math.max(200, Math.min(660, w));
			revalidate();
		}

		@Override
		public Dimension getPreferredSize() {
			int inner = Math.max(40, maxWidth - 2 * PAD_H);
			FontMetrics fm = ta.getFontMetrics(ta.getFont());
			int longest = 0;
			for (String line : ta.getText().split("\n", -1)) {
				longest = Math.max(longest, fm.stringWidth(line));
			}
			int contentW = Math.min(longest, inner);
			ta.setSize(contentW, Short.MAX_VALUE);
			int h = ta.getPreferredSize().height;
			return new Dimension(contentW + 2 * PAD_H, h + 2 * PAD_V);
		}

		@Override
		public Dimension getMaximumSize() {
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(bg);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
			g2.dispose();
			super.paintComponent(g);
		}
	}
}
