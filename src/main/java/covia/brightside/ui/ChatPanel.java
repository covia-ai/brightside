package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.View;

import covia.brightside.SessionHistory;
import covia.brightside.chat.ChatSession;

/**
 * The chat: one scrolling, selectable transcript above a rounded input box and
 * an accent Send button. Messages read as bubbles — the user on the right, the
 * assistant on the left — drawn as a rounded per-message background behind the
 * text. Because it is a single text component, selection and copy work across
 * the whole conversation, not just one message. Enter sends, Shift+Enter inserts
 * a newline.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

	private static final Color ERROR_RED = new Color(0xE5, 0x53, 0x53);
	private static final int SIDE_INSET = 96; // keeps a bubble off the far edge

	private final JTextPane transcript = new JTextPane();
	private final JScrollPane scroll;
	private final JTextArea input = new JTextArea(1, 20);
	private final JButton send = new JButton("Send");

	private final List<SessionHistory.Turn> displayed = new ArrayList<>();
	private volatile ChatSession session;
	private boolean busy;

	public ChatPanel() {
		super(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

		transcript.setEditable(false);
		transcript.setBackground(UIManager.getColor("Panel.background"));
		transcript.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		transcript.setFont(transcript.getFont().deriveFont(transcript.getFont().getSize2D() + 1f));
		transcript.getCaret().setSelectionVisible(true); // keep the selection painted when focus leaves

		scroll = new JScrollPane(transcript);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(24);

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

	/** Connects a live session and opens the input box (does not clear the transcript). */
	public void setSession(ChatSession session) {
		this.session = session;
		setInputEnabled(true);
		focusInput();
	}

	/** Replaces the transcript with the venue's live conversation turns. */
	public void restore(List<SessionHistory.Turn> turns) {
		transcript.setText("");
		transcript.getHighlighter().removeAllHighlights();
		displayed.clear();
		for (SessionHistory.Turn t : turns) {
			displayed.add(t);
			appendBubble(t.role(), t.text());
		}
		scrollToBottom();
	}

	/** Re-render only if the live turns differ from what's shown (avoids flicker). */
	public void refreshTo(List<SessionHistory.Turn> live) {
		if (live.equals(displayed)) return;
		restore(live);
	}

	public void clearMessages() {
		transcript.setText("");
		transcript.getHighlighter().removeAllHighlights();
		displayed.clear();
	}

	public void focusInput() {
		input.requestFocusInWindow();
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
		displayed.add(new SessionHistory.Turn(role, text));
		appendBubble(role, text);
		scrollToBottom();
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.toString() : m;
	}

	/** A note from Brightside itself (status, hints) — centred and muted. */
	public void appendSystem(String text) {
		appendNotice(text, muted(), false);
	}

	public void appendError(String text) {
		appendNotice(text, ERROR_RED, true);
	}

	// ------------------------------------------------------------------
	// Rendering into the single transcript
	// ------------------------------------------------------------------

	private void appendBubble(String role, String text) {
		boolean user = "user".equals(role);
		Color bg = user ? LAF.ACCENT : assistantBg();
		Color fg = user ? Color.WHITE : UIManager.getColor("Label.foreground");

		SimpleAttributeSet chars = new SimpleAttributeSet();
		StyleConstants.setForeground(chars, fg);

		SimpleAttributeSet para = new SimpleAttributeSet();
		StyleConstants.setAlignment(para, user ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT);
		StyleConstants.setLeftIndent(para, user ? SIDE_INSET : 8);
		StyleConstants.setRightIndent(para, user ? 8 : SIDE_INSET);
		StyleConstants.setSpaceAbove(para, 5);
		StyleConstants.setSpaceBelow(para, 5);

		StyledDocument doc = transcript.getStyledDocument();
		int start = doc.getLength();
		try {
			doc.insertString(start, text + "\n", chars);
			doc.setParagraphAttributes(start, text.length(), para, false);
			transcript.getHighlighter().addHighlight(start, start + text.length(), new BubblePainter(bg));
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
	}

	private void appendNotice(String text, Color fg, boolean bold) {
		SimpleAttributeSet chars = new SimpleAttributeSet();
		StyleConstants.setForeground(chars, fg);
		StyleConstants.setItalic(chars, !bold);
		StyleConstants.setBold(chars, bold);
		StyleConstants.setFontSize(chars, Math.max(11, transcript.getFont().getSize() - 1));

		SimpleAttributeSet para = new SimpleAttributeSet();
		StyleConstants.setAlignment(para, StyleConstants.ALIGN_CENTER);
		StyleConstants.setSpaceAbove(para, 6);
		StyleConstants.setSpaceBelow(para, 6);
		StyleConstants.setLeftIndent(para, 40);
		StyleConstants.setRightIndent(para, 40);

		StyledDocument doc = transcript.getStyledDocument();
		int start = doc.getLength();
		try {
			doc.insertString(start, text + "\n", chars);
			doc.setParagraphAttributes(start, text.length(), para, false);
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
		scrollToBottom();
	}

	private void scrollToBottom() {
		SwingUtilities.invokeLater(() -> {
			var bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
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

	/** Paints a rounded background behind a message's text range (a bubble). */
	private static final class BubblePainter extends DefaultHighlighter.DefaultHighlightPainter {
		private static final int ARC = 16;
		private static final int PAD_X = 8;
		private static final int PAD_Y = 1;

		BubblePainter(Color color) {
			super(color);
		}

		@Override
		public Shape paintLayer(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c, View view) {
			Rectangle r;
			try {
				Shape s = view.modelToView(offs0, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds);
				r = (s instanceof Rectangle) ? (Rectangle) s : s.getBounds();
			} catch (BadLocationException e) {
				return null;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(getColor());
			g2.fillRoundRect(r.x - PAD_X, r.y - PAD_Y, r.width + 2 * PAD_X, r.height + 2 * PAD_Y, ARC, ARC);
			g2.dispose();
			return r;
		}
	}
}
