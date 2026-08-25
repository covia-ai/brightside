package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.text.DefaultEditorKit;

import covia.brightside.ConversationStore;
import covia.brightside.chat.ChatSession;

/**
 * The chat: a scrolling column of rounded message bubbles (the user on the
 * right, the assistant on the left) above a rounded input box and an accent
 * Send button. Enter sends, Shift+Enter inserts a newline. Messages are sent
 * on a worker thread; input is disabled while a reply is pending.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

	/** Notified of each real turn so the app can persist the conversation. */
	public interface MessageSink {
		void onMessage(String role, String text);
	}

	private static final Color ERROR_RED = new Color(0xE5, 0x53, 0x53);

	private final MessageColumn column = new MessageColumn();
	private final JScrollPane scroll;
	private final JTextArea input = new JTextArea(1, 20);
	private final JButton send = new JButton("Send");

	private volatile ChatSession session;
	private boolean busy;
	private MessageSink sink;

	public ChatPanel() {
		super(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

		scroll = new JScrollPane(column);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		// Keep bubbles reflowing to the viewport width.
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

	public void setSink(MessageSink sink) {
		this.sink = sink;
	}

	/** Connects a live session and opens the input box (does not clear the transcript). */
	public void setSession(ChatSession session) {
		this.session = session;
		setInputEnabled(true);
		focusInput();
	}

	/** Replaces the transcript with saved messages (no persistence side effects). */
	public void restore(List<ConversationStore.Msg> messages) {
		column.clear();
		for (ConversationStore.Msg m : messages) {
			boolean user = "user".equals(m.role());
			column.add(bubbleRow(m.text(), user));
		}
		column.revalidate();
		scrollToBottom();
	}

	public void clearMessages() {
		column.clear();
		column.revalidate();
		column.repaint();
	}

	public void focusInput() {
		input.requestFocusInWindow();
	}

	private void setInputEnabled(boolean on) {
		input.setEnabled(on);
		send.setEnabled(on);
	}

	// ------------------------------------------------------------------
	// Messages
	// ------------------------------------------------------------------

	private void send() {
		ChatSession s = session;
		if (s == null || busy) return;
		String text = input.getText().trim();
		if (text.isEmpty()) return;
		input.setText("");
		addTurn("user", text, true);
		busy = true;
		setInputEnabled(false);

		new SwingWorker<ChatSession.Reply, Void>() {
			@Override
			protected ChatSession.Reply doInBackground() throws Exception {
				return s.send(text);
			}

			@Override
			protected void done() {
				busy = false;
				setInputEnabled(true);
				try {
					addTurn("assistant", get().text(), true);
				} catch (ExecutionException e) {
					appendError(describe(e.getCause() != null ? e.getCause() : e));
				} catch (Exception e) {
					appendError(describe(e));
				}
				focusInput();
			}
		}.execute();
	}

	private void addTurn(String role, String text, boolean persist) {
		column.add(bubbleRow(text, "user".equals(role)));
		column.revalidate();
		scrollToBottom();
		if (persist && sink != null) sink.onMessage(role, text);
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

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.toString() : m;
	}

	private void scrollToBottom() {
		SwingUtilities.invokeLater(() -> {
			var bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	// ------------------------------------------------------------------
	// Bubble construction
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

	/** A rounded message bubble wrapping a wrapping, selectable text area. */
	private static final class Bubble extends JPanel {
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
			ta.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
			ta.setFont(ta.getFont().deriveFont(ta.getFont().getSize2D() + 1f));
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
