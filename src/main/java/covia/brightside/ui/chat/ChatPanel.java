package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultEditorKit;

import covia.brightside.SessionHistory;
import covia.brightside.chat.ChatSession;
import covia.brightside.ui.LAF;

/**
 * The chat: a scrolling {@link MessageColumn} of message components above a
 * rounded input box and an accent Send button. Each transcript item is rendered
 * by its own component — a {@link Bubble} for a user/assistant message, an
 * {@link ExpandableActivity} chip for a turn's tool use — kept as separate
 * components (in this {@code ui.chat} package) so new item kinds (images, cards,
 * richer tool output) can be added as their own row types. Text within a bubble
 * is selectable; a right-click offers <em>Copy message</em> and <em>Copy
 * conversation</em>. Enter sends, Shift+Enter inserts a newline.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

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

	/** Replaces the transcript with the venue's live conversation items. */
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
		JPanel bubble = roundBubble(ChatStyle.assistantBg());
		bubble.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
		thinkingIndicator = new TypingIndicator(ChatStyle.muted());
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
		column.add(noticeRow(text, ChatStyle.muted(), false));
		column.revalidate();
		scrollToBottom();
	}

	public void appendError(String text) {
		column.add(noticeRow(text, ChatStyle.ERROR, true));
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
		Color bg = user ? LAF.ACCENT : ChatStyle.assistantBg();
		Color fg = user ? Color.WHITE : ChatStyle.foreground();
		Bubble bubble = new Bubble(text, bg, fg);
		bubble.setAvailableWidth(scroll.getViewport().getWidth());

		// The bubble is a dumb display component; the panel owns copy behaviour.
		JTextArea ta = bubble.textArea();
		ta.addCaretListener(e -> {
			if (e.getDot() != e.getMark()) lastSelectedBubble = ta;
		});
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
		row.add(new ExpandableActivity(a, column::revalidate), BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}
}
