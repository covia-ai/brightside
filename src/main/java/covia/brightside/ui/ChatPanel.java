package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import covia.brightside.chat.ChatSession;

/**
 * The chat window body: a transcript above, a message box and Send button
 * below. Enter sends, Shift+Enter inserts a newline. Each message is sent on a
 * worker thread and the reply (or error) appended when it arrives; input is
 * disabled while a reply is pending because a session accepts one chat at a
 * time.
 */
@SuppressWarnings("serial")
public final class ChatPanel extends JPanel {

	private static final Color ERROR_RED = new Color(0xE5, 0x53, 0x53);

	private final JTextPane transcript = new JTextPane();
	private final JTextArea input = new JTextArea(3, 20);
	private final JButton send = new JButton("Send");

	private final Style body;
	private final Style userLabel;
	private final Style agentLabel;
	private final Style system;
	private final Style errorLabel;
	private final Style error;

	private volatile ChatSession session;
	private boolean busy;

	public ChatPanel() {
		super(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		transcript.setEditable(false);
		transcript.setMargin(new Insets(8, 8, 8, 8));
		StyledDocument doc = transcript.getStyledDocument();
		Style base = transcript.getStyle(StyleContext.DEFAULT_STYLE);

		body = doc.addStyle("body", base);

		userLabel = doc.addStyle("userLabel", base);
		StyleConstants.setBold(userLabel, true);
		StyleConstants.setForeground(userLabel, accent());

		agentLabel = doc.addStyle("agentLabel", base);
		StyleConstants.setBold(agentLabel, true);

		system = doc.addStyle("system", base);
		StyleConstants.setItalic(system, true);
		StyleConstants.setForeground(system, muted());

		errorLabel = doc.addStyle("errorLabel", base);
		StyleConstants.setBold(errorLabel, true);
		StyleConstants.setForeground(errorLabel, ERROR_RED);
		error = doc.addStyle("error", base);
		StyleConstants.setForeground(error, ERROR_RED);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setFont(transcript.getFont());
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
		send.addActionListener(e -> send());

		JPanel bottom = new JPanel(new BorderLayout(8, 0));
		bottom.add(new JScrollPane(input), BorderLayout.CENTER);
		bottom.add(send, BorderLayout.EAST);

		add(new JScrollPane(transcript), BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		setInputEnabled(false);
	}

	private static Color accent() {
		Color c = UIManager.getColor("Component.accentColor");
		return (c != null) ? c : new Color(0x9F, 0x7A, 0xEA);
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	/** Connects the panel to a live session and opens the input box. */
	public void setSession(ChatSession session) {
		this.session = session;
		setInputEnabled(true);
		focusInput();
	}

	public void focusInput() {
		input.requestFocusInWindow();
	}

	private void setInputEnabled(boolean on) {
		input.setEnabled(on);
		send.setEnabled(on);
	}

	private void send() {
		ChatSession s = session;
		if (s == null || busy) return;
		String text = input.getText().trim();
		if (text.isEmpty()) return;
		input.setText("");
		append("You", text, userLabel, body);
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
					ChatSession.Reply reply = get();
					append(s.config().agentId(), reply.text(), agentLabel, body);
				} catch (ExecutionException e) {
					appendError(describe(e.getCause() != null ? e.getCause() : e));
				} catch (Exception e) {
					appendError(describe(e));
				}
				focusInput();
			}
		}.execute();
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.toString() : m;
	}

	/** A note from BrightSide itself (status, hints). */
	public void appendSystem(String text) {
		append(null, text, system, system);
	}

	public void appendError(String text) {
		append("Error", text, errorLabel, error);
	}

	private void append(String label, String text, Style labelStyle, Style bodyStyle) {
		StyledDocument doc = transcript.getStyledDocument();
		try {
			if (doc.getLength() > 0) doc.insertString(doc.getLength(), "\n\n", bodyStyle);
			if (label != null) doc.insertString(doc.getLength(), label + "\n", labelStyle);
			doc.insertString(doc.getLength(), text, bodyStyle);
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
		transcript.setCaretPosition(doc.getLength());
	}
}
