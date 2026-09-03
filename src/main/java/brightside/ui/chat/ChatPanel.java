package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
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
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

import brightside.SessionHistory;
import brightside.chat.ChatSession;
import brightside.chat.LiveTurn;
import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Documents;
import brightside.ui.components.Labels;
import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;
import covia.venue.AgentEvents;

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
	private static final String COMPOSER_HINT = "Message Brightside…";

	private final TextArea input = new TextArea(1, 20).placeholder(COMPOSER_HINT);
	private final JScrollPane inputScroll;
	private final JButton send = Buttons.primary("Send");

	private final List<SessionHistory.Item> displayed = new ArrayList<>();
	private JTextComponent lastSelectedBubble; // the bubble or step holding the current selection, if any
	private Component thinkingRow; // assistant progress row while a reply is pending
	private ThinkingBubble thinkingBubble;
	private LiveTurn liveTurn; // the turn in flight, built from the agent's live events
	private ExpandableActivity liveChip; // its steps so far, grown in place
	private Component liveRow;
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

		scroll = Scrolls.vertical(column);
		scroll.getViewport().addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				column.setAvailableWidth(scroll.getViewport().getWidth());
			}
		});

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		Styles.style(input, "font: +1");
		input.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
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
					Clipboard.copy(lastSelectedBubble.getSelectedText());
				}
			}
		});

		inputScroll = new JScrollPane(input,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		inputScroll.putClientProperty("FlatLaf.style", "arc: 16");
		inputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
		Documents.onChange(input, this::scheduleInputResize);
		input.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				scheduleInputResize();
			}
		});

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
		setStartingUp(false);
		setInputEnabled(true);
		focusInput();
	}

	/**
	 * While the app is still starting, Home stays exactly as it will be — the
	 * welcome card, the composer — and only the composer's hint says so; nothing
	 * is added to the transcript that would have to be taken away again.
	 */
	public void setStartingUp(boolean starting) {
		input.placeholder(starting ? "Just a moment while everything starts up…" : COMPOSER_HINT);
	}

	/** Each activity label stays readable for at least this long; faster steps are coalesced. */
	private static final int ACTIVITY_HOLD_MS = 800;
	private long activityShownAt;
	private String pendingActivity;
	private javax.swing.Timer activityTimer;

	/**
	 * One event from the venue's agent event tap while a reply is pending;
	 * ignored when idle. The assistant's narration and each tool call become
	 * the steps of a chip that grows above the bubble, and the bubble's line
	 * shows the latest status — the assistant's own words when it narrates,
	 * else "Thinking…" or the running tool's name.
	 *
	 * @param toolLabel the display name of the tool a {@code tool:start} names, or null
	 */
	public void showActivity(AgentEvents.Event event, String toolLabel) {
		if (liveTurn == null || thinkingBubble == null) return;
		if (!liveTurn.apply(event, toolLabel)) return;
		showStatus(liveTurn.status());
		showLiveSteps();
	}

	/**
	 * The bubble's status line. Fast tool calls would flash by unreadably, so
	 * each label holds for {@link #ACTIVITY_HOLD_MS} and a burst shows only its
	 * latest label once the hold elapses.
	 */
	private void showStatus(String label) {
		if (thinkingBubble == null) return;
		long now = System.currentTimeMillis();
		long elapsed = now - activityShownAt;
		if (elapsed >= ACTIVITY_HOLD_MS) {
			thinkingBubble.setSummary(label);
			activityShownAt = now;
			pendingActivity = null;
			if (activityTimer != null) {
				activityTimer.stop();
				activityTimer = null;
			}
			return;
		}
		pendingActivity = label;
		if (activityTimer == null) {
			activityTimer = new javax.swing.Timer((int) (ACTIVITY_HOLD_MS - elapsed), e -> {
				activityTimer = null;
				String next = pendingActivity;
				pendingActivity = null;
				if (next != null && thinkingBubble != null) {
					thinkingBubble.setSummary(next);
					activityShownAt = System.currentTimeMillis();
				}
			});
			activityTimer.setRepeats(false);
			activityTimer.start();
		}
	}

	/**
	 * The steps chip for the turn in flight: made on the first step, above the
	 * bubble, and grown in place after. It is the same chip the transcript
	 * shows for a finished turn, so it simply stays when the reply lands.
	 */
	private void showLiveSteps() {
		SessionHistory.Activity a = liveTurn.activity();
		if (a == null) return;
		if (liveChip == null) {
			liveChip = new ExpandableActivity(a, column::revalidate, ta -> lastSelectedBubble = ta);
			liveRow = activityRow(liveChip);
			column.add(liveRow, indexOf(thinkingRow));
		} else {
			liveChip.update(a);
		}
		column.revalidate();
		scrollToBottom();
	}

	/** The row's index in the column, or -1 (append) when it is not there. */
	private int indexOf(Component row) {
		Component[] rows = column.getComponents();
		for (int i = 0; i < rows.length; i++) {
			if (rows[i] == row) return i;
		}
		return -1;
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
		// Already the clean Home it is being asked for: leave it be, rather than
		// take the welcome card down and put it straight back.
		if (items.isEmpty() && displayed.isEmpty() && emptyState.getParent() == column) return;
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
		if (it instanceof SessionHistory.Message m) {
			return bubbleRow(m.text(), "user".equals(m.role()), m.origin());
		}
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
						if (session == s && bindingVersion == version && liveTurn != null && liveTurn.accepted()) {
							showStatus(liveTurn.status());
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
				} else if (failure instanceof CancellationException) {
					// The user stopped waiting. The turn may still finish on the
					// venue; reconciling by session id lets the watcher show it.
					appendSystem("Stopped waiting for this reply.");
					String sid = accepted.isCompletedExceptionally() ? null : accepted.getNow(null);
					if (sid != null) conversationCommitted.accept(sid);
				} else {
					log.warn("Chat send failed", failure);
					appendError(describe(failure));
				}
				focusInput();
			}
		}.execute();
	}

	/**
	 * The thinking bubble's stop control. A reply has no deadline, so this is
	 * the way out of a turn that is taking too long: confirm, then cancel the
	 * chat job on the venue. The agent's own work is not interrupted — whatever
	 * it finishes still reaches the session, and the live watcher shows it.
	 */
	private void confirmStop() {
		ChatSession s = session;
		long version = bindingVersion;
		if (s == null || !busy) return;
		boolean stop = Dialogs.choose(this, "Stop waiting?",
			"Stop waiting for this reply?\n\n"
			+ "You can carry on chatting straight away. Anything the assistant is\n"
			+ "still doing will finish on its own, and if it answers, the reply\n"
			+ "appears here.",
			"Stop waiting", "Keep waiting");
		if (!stop || session != s || bindingVersion != version || !busy) return;
		Thread t = new Thread(() -> {
			try {
				if (!s.cancel()) log.info("Nothing to cancel: the reply had already arrived");
			} catch (Exception e) {
				log.warn("Could not cancel the chat in flight", e);
			}
		}, "brightside-chat-cancel");
		t.setDaemon(true);
		t.start();
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
		liveTurn = new LiveTurn();
		thinkingBubble = new ThinkingBubble(liveTurn.status(), this::confirmStop);
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
		// The reply is in: drop any queued activity label with the bubble.
		if (activityTimer != null) {
			activityTimer.stop();
			activityTimer = null;
		}
		pendingActivity = null;
		activityShownAt = 0;
		if (thinkingBubble != null) {
			thinkingBubble.stop();
			thinkingBubble = null;
		}
		if (thinkingRow != null) {
			column.remove(thinkingRow);
			thinkingRow = null;
		}
		// The steps chip stays as the turn's record, and `displayed` learns of
		// it so the venue's own projection of the same turn is not re-rendered.
		if (liveTurn != null) {
			SessionHistory.Activity a = liveTurn.activity();
			if (a != null && liveRow != null) displayed.add(a);
			liveTurn = null;
			liveChip = null;
			liveRow = null;
		}
		column.revalidate();
		column.repaint();
	}

	/** A note from Brightside itself (status, hints) — centred and muted. */
	public void appendSystem(String text) {
		hideEmptyState();
		column.add(noticeRow(text, Styles.MUTED, false, false));
		column.revalidate();
		scrollToBottom();
	}

	public void appendError(String text) {
		hideEmptyState();
		column.add(noticeRow(text, Styles.ERROR, true, true));
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

	private static boolean hasSelection(JTextComponent ta) {
		return ta != null && ta.getSelectionStart() != ta.getSelectionEnd();
	}

	/** Right-click menu on a bubble: copy its selection/message, or the whole conversation. */
	private void showBubbleMenu(JTextComponent ta, String messageText, Component invoker, int x, int y) {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy message");
		copy.addActionListener(e -> {
			String sel = ta.getSelectedText();
			Clipboard.copy((sel != null && !sel.isEmpty()) ? sel : messageText);
		});
		JMenuItem copyAll = new JMenuItem("Copy conversation");
		copyAll.addActionListener(e -> Clipboard.copy(conversationText()));
		menu.add(copy);
		menu.add(copyAll);
		menu.show(invoker, x, y);
	}

	// ------------------------------------------------------------------
	// Rows
	// ------------------------------------------------------------------

	private Component bubbleRow(String text, boolean user) {
		return bubbleRow(text, user, null);
	}

	/** A message row; {@code origin} adds a small caption naming where an inbound message came from. */
	private Component bubbleRow(String text, boolean user, String origin) {
		// The assistant writes Markdown; the user's own words are shown as typed.
		Bubble bubble = user ? Bubble.plain(text, true) : Bubble.markdown(text);
		bubble.setAvailableWidth(scroll.getViewport().getWidth());

		// The bubble is a dumb display component; the panel owns copy behaviour.
		// "Copy message" copies the source, so Markdown stays Markdown.
		JTextComponent ta = bubble.textComponent();
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

		Component content = bubble;
		if (origin != null) {
			JPanel stack = new JPanel();
			stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));
			stack.setOpaque(false);
			JLabel caption = Labels.small("via " + origin, Styles.MUTED);
			float edge = user ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT;
			caption.setAlignmentX(edge);
			bubble.setAlignmentX(edge);
			stack.add(caption);
			stack.add(bubble);
			content = stack;
		}

		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		row.add(content, user ? BorderLayout.EAST : BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/** A centred note in a {@link Styles} tone ({@link Styles#MUTED}, {@link Styles#ERROR}). */
	private Component noticeRow(String text, String tone, boolean bold, boolean selectable) {
		Component content;
		if (selectable) {
			// A selectable, wrapping run — an error can be read AND copied
			// (Ctrl/Cmd+C). Transparent and borderless so it reads as a notice, not
			// an input.
			SelectableText ta = new SelectableText(text).tone(tone).small();
			if (bold) ta.bold();
			content = ta;
		} else {
			// Short status line: a centred, wrapping label. Newlines become <br>.
			String body = escapeHtml(text).replace("\n", "<br>");
			JLabel label = Styles.classes(Labels.html(body, 520, SwingConstants.CENTER), Styles.SMALL, tone);
			if (bold) Styles.style(label, "font: bold -2");
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
		return activityRow(new ExpandableActivity(a, column::revalidate, ta -> lastSelectedBubble = ta));
	}

	private static Component activityRow(ExpandableActivity chip) {
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		row.add(chip, BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}
}
