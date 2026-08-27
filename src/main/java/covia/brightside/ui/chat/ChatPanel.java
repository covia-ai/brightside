package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

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
 * conversation</em>. Enter sends; Ctrl+Enter or Shift+Enter inserts a newline.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatPanel.class);
	private static final int MAX_INPUT_ROWS = 6;

	private final MessageColumn column = new MessageColumn();
	private final EmptyChatState emptyState = new EmptyChatState();
	private final JScrollPane scroll;
	private final JTextArea input = new JTextArea(1, 20);
	private final JScrollPane inputScroll;
	private final JButton send = new JButton("Send");

	private final List<SessionHistory.Item> displayed = new ArrayList<>();
	private JTextArea lastSelectedBubble; // the bubble holding the current selection, if any
	private Component thinkingRow; // assistant progress row while a reply is pending
	private ThinkingBubble thinkingBubble;
	private Consumer<String> conversationCommitted = ignored -> {
	};
	private volatile ChatSession session;
	private long bindingVersion;
	private boolean busy;
	private CompletableFuture<String> acceptedSession;
	private CompletableFuture<Void> deliveryTail = CompletableFuture.completedFuture(null);

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
		input.getInputMap(JComponent.WHEN_FOCUSED)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), DefaultEditorKit.insertBreakAction);
		input.getActionMap().put("send", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				send();
			}
		});
		// The input keeps focus, so Ctrl/Cmd+C lands here: copy the input's own
		// selection, or fall back to the last bubble the user selected in.
		KeyStroke copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C,
			GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK
				: Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
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

		inputScroll = new JScrollPane(input,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		inputScroll.putClientProperty("FlatLaf.style", "arc: 16");
		inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
		input.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				scheduleInputResize();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				scheduleInputResize();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				scheduleInputResize();
			}
		});
		input.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				scheduleInputResize();
			}
		});

		send.putClientProperty("JButton.buttonType", "roundRect");
		send.setForeground(Color.WHITE);
		send.setBackground(LAF.ACCENT);
		send.setToolTipText("Send message (Enter)");
		send.addActionListener(e -> send());
		Dimension sendSize = send.getPreferredSize();
		int defaultComposerHeight = inputScroll.getPreferredSize().height;
		send.setPreferredSize(new Dimension(sendSize.width, defaultComposerHeight));
		send.setMinimumSize(new Dimension(sendSize.width, defaultComposerHeight));

		JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		bottom.add(inputScroll, BorderLayout.CENTER);
		JPanel sendWrap = new JPanel(new BorderLayout());
		sendWrap.add(send, BorderLayout.SOUTH);
		bottom.add(sendWrap, BorderLayout.EAST);

		add(scroll, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		showEmptyState();
		setInputEnabled(false);
	}

	private void scheduleInputResize() {
		SwingUtilities.invokeLater(this::resizeInput);
	}

	/** Grow to the draft's visual line count, capped so the transcript keeps space. */
	private void resizeInput() {
		Insets insets = input.getInsets();
		int width = input.getWidth() - insets.left - insets.right;
		if (width <= 0) width = 420;
		java.awt.FontMetrics fm = input.getFontMetrics(input.getFont());
		int rows = 0;
		for (String line : input.getText().split("\\R", -1)) {
			int pixels = fm.stringWidth(line);
			rows += Math.max(1, (pixels + width - 1) / width);
		}
		rows = Math.max(1, Math.min(MAX_INPUT_ROWS, rows));
		if (input.getRows() == rows) return;
		input.setRows(rows);
		input.revalidate();
		inputScroll.revalidate();
		revalidate();
	}

	// ------------------------------------------------------------------
	// Wiring
	// ------------------------------------------------------------------

	/** Connects a live session and opens the input box (does not clear the transcript). */
	public void setSession(ChatSession session) {
		this.session = session;
		bindingVersion++;
		busy = false;
		acceptedSession = null;
		deliveryTail = CompletableFuture.completedFuture(null);
		hideThinking();
		setInputEnabled(true);
		focusInput();
	}

	/** Called after a successful send has committed and returned its session id. */
	public void setConversationCommittedListener(Consumer<String> listener) {
		conversationCommitted = (listener != null) ? listener : ignored -> {
		};
	}

	/** Removes the current user's session and all locally rendered conversation data. */
	public void clearSession() {
		session = null;
		bindingVersion++;
		busy = false;
		acceptedSession = null;
		deliveryTail = CompletableFuture.completedFuture(null);
		hideThinking();
		input.setText("");
		lastSelectedBubble = null;
		clearMessages();
		setInputEnabled(false);
	}

	/** Replaces the transcript with the venue's live conversation items. */
	public void restore(List<SessionHistory.Item> items) {
		column.clear();
		displayed.clear();
		for (SessionHistory.Item it : items) {
			displayed.add(it);
			column.add(rowFor(it));
		}
		if (items.isEmpty()) showEmptyState();
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
		refreshTo(live, false);
	}

	/**
	 * Re-render a settled venue projection. While the session has pending or
	 * in-cycle work, its stored conversation intentionally trails the optimistic
	 * user bubbles, so replacing the UI would make accepted messages disappear.
	 */
	public void refreshTo(List<SessionHistory.Item> live, boolean sessionActive) {
		// While a send is in flight the message sits in the agent's pending queue
		// before it's minted into the conversation, so a mid-flight read wouldn't
		// yet include it — don't clobber the optimistic bubble. The reply's own
		// re-read reconciles once it lands.
		if (busy || sessionActive) return;
		if (live.equals(displayed)) return;
		restore(live);
	}

	public void clearMessages() {
		column.clear();
		displayed.clear();
		showEmptyState();
		column.revalidate();
		column.repaint();
	}

	/** Clears transcript and draft state, leaving the bound session ready for a first send. */
	public void startNewConversation() {
		bindingVersion++;
		busy = false;
		acceptedSession = null;
		deliveryTail = CompletableFuture.completedFuture(null);
		hideThinking();
		clearMessages();
		input.setText("");
		lastSelectedBubble = null;
		setInputEnabled(session != null);
		focusInput();
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
		if (s == null) return;
		long version = bindingVersion;
		String text = input.getText().trim();
		if (text.isEmpty()) return;
		input.setText("");
		addTurn("user", text);
		if (busy) {
			enqueue(s, version, text);
			focusInput();
			return;
		}

		busy = true;
		CompletableFuture<String> accepted = new CompletableFuture<>();
		acceptedSession = accepted;
		deliveryTail = CompletableFuture.completedFuture(null);
		showThinking();

		new SwingWorker<ChatSession.Reply, Void>() {
			@Override
			protected ChatSession.Reply doInBackground() throws Exception {
				return s.send(text, sid -> {
					accepted.complete(sid);
					SwingUtilities.invokeLater(() -> {
						if (session == s && bindingVersion == version && thinkingBubble != null) {
							thinkingBubble.setSummary("Working…");
						}
					});
				});
			}

			@Override
			protected void done() {
				ChatSession.Reply reply = null;
				Throwable failure = null;
				try {
					reply = get();
				} catch (ExecutionException e) {
					failure = (e.getCause() != null) ? e.getCause() : e;
				} catch (Exception e) {
					failure = e;
				}
				if (failure != null) accepted.completeExceptionally(failure);
				if (session != s || bindingVersion != version) return;
				busy = false;
				acceptedSession = null;
				hideThinking();
				if (failure == null) {
					addTurn("assistant", reply.text());
					conversationCommitted.accept(reply.sessionId());
				} else {
					log.warn("Chat send failed", failure);
					appendError(describe(failure));
				}
				focusInput();
			}
		}.execute();
	}

	/** Deliver a follow-up to the venue queue without waiting for the active reply. */
	private void enqueue(ChatSession s, long version, String text) {
		CompletableFuture<String> accepted = acceptedSession;
		if (accepted == null) {
			appendError("The active conversation has not been accepted by the venue");
			return;
		}

		// Chain only the fast intake calls, preserving click order. Each message is
		// handed to agent:message as soon as the preceding intake has returned; no
		// client-side wait for an agent cycle or model reply is introduced.
		CompletableFuture<Void> previous = deliveryTail;
		CompletableFuture<ChatSession.Delivery> delivery = previous
			.handle((ignored, failure) -> null)
			.thenCombine(accepted, (ignored, sid) -> sid)
			.thenApplyAsync(sid -> {
				try {
					return s.enqueue(text, sid);
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			});
		deliveryTail = delivery.handle((ignored, failure) -> null);
		delivery.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (session != s || bindingVersion != version) return;
			if (failure != null) {
				Throwable cause = unwrap(failure);
				log.warn("Queued chat delivery failed", cause);
				appendError(describe(cause));
			} else {
				conversationCommitted.accept(result.sessionId());
			}
		}));
	}

	private void addTurn(String role, String text) {
		// The venue records the turn in the agent session; the UI reflects it
		// optimistically and keeps `displayed` in step so the watcher's compare
		// doesn't re-render our own turns.
		displayed.add(new SessionHistory.Message(role, text));
		hideEmptyState();
		column.add(bubbleRow(text, "user".equals(role)));
		column.revalidate();
		scrollToBottom();
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.toString() : m;
	}

	private static Throwable unwrap(Throwable t) {
		Throwable current = t;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	/** Show compact live progress on the assistant side while a reply is pending. */
	private void showThinking() {
		if (thinkingRow != null) return;
		hideEmptyState();
		thinkingBubble = new ThinkingBubble("Preparing…");
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		row.add(thinkingBubble, BorderLayout.WEST);
		thinkingRow = row;
		column.add(row);
		column.revalidate();
		scrollToBottom();
		thinkingBubble.start();
	}

	private void hideThinking() {
		if (thinkingRow == null) return;
		if (thinkingBubble != null) {
			thinkingBubble.stop();
			thinkingBubble = null;
		}
		column.remove(thinkingRow);
		thinkingRow = null;
		column.revalidate();
		column.repaint();
	}

	/** A note from Brightside itself (status, hints) — centred and muted. */
	public void appendSystem(String text) {
		hideEmptyState();
		column.add(noticeRow(text, ChatStyle.muted(), false, false));
		column.revalidate();
		scrollToBottom();
	}

	public void appendError(String text) {
		hideEmptyState();
		column.add(noticeRow(text, ChatStyle.ERROR, true, true));
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

	private Component noticeRow(String text, Color fg, boolean bold, boolean selectable) {
		Component content;
		if (selectable) {
			// A selectable, wrapping text area — an error can be read AND copied
			// (Ctrl/Cmd+C). Transparent and borderless so it reads as a notice, not
			// an input.
			JTextArea ta = new JTextArea(text);
			ta.setEditable(false);
			ta.setLineWrap(true);
			ta.setWrapStyleWord(true);
			ta.setOpaque(false);
			ta.setForeground(fg);
			ta.setBorder(null);
			ta.putClientProperty("FlatLaf.styleClass", "small");
			if (bold) ta.setFont(ta.getFont().deriveFont(java.awt.Font.BOLD));
			content = ta;
		} else {
			// Short status line: a centred, wrapping label. Newlines become <br>.
			String body = escapeHtml(text).replace("\n", "<br>");
			JLabel label = new JLabel(
				"<html><div style='width:520px; text-align:center'>" + body + "</div></html>", SwingConstants.CENTER);
			label.setForeground(fg);
			label.putClientProperty("FlatLaf.styleClass", "small");
			if (bold) label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | java.awt.Font.BOLD));
			content = label;
		}
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(6, 24, 6, 24));
		row.add(content, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void showEmptyState() {
		if (emptyState.getParent() != column) column.add(emptyState);
	}

	private void hideEmptyState() {
		if (emptyState.getParent() == column) column.remove(emptyState);
	}

	/** An assistant-side, collapsed-by-default group of a turn's tool-use steps. */
	private Component activityRow(SessionHistory.Activity a) {
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		row.add(new ExpandableActivity(a, column::revalidate, ta -> lastSelectedBubble = ta), BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}
}
