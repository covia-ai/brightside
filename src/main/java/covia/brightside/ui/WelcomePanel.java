package covia.brightside.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import covia.brightside.Identity;

/**
 * The friendly first screen: "What should I call you?". A full-window panel
 * (not a dialog) with a text box and a Continue button. Reused, with a Cancel
 * option, when someone changes their name later. No jargon — the person just
 * enters a name.
 */
@SuppressWarnings("serial")
public final class WelcomePanel extends JPanel {

	/** What the panel does with the entered name. */
	public interface Listener {
		void onNameEntered(String name);

		void onCancel();
	}

	private static final Color ERROR_RED = new Color(0xE5, 0x53, 0x53);

	private final Listener listener;
	private final JLabel title = new JLabel("What should I call you?");
	private final JLabel subtitle = new JLabel();
	private final JTextField field = new JTextField(16);
	private final JButton cont = new JButton("Continue");
	private final JButton cancel = new JButton("Cancel");
	private final JLabel statusLine = new JLabel(" ");

	public WelcomePanel(Listener listener) {
		this.listener = listener;
		setLayout(new java.awt.GridBagLayout()); // centre the column in the window

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

		JLabel mark = new JLabel(new ImageIcon(Icons.icon(72)));
		mark.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel hello = new JLabel("Welcome to " + covia.brightside.BrightSide.APP_NAME);
		hello.setForeground(muted());
		hello.setAlignmentX(Component.CENTER_ALIGNMENT);

		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 10f));
		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		subtitle.setForeground(muted());
		subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		setSubtitle("I'm your personal assistant. Let's get to know each other.");

		field.setHorizontalAlignment(JTextField.CENTER);
		field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 6f));
		field.setMaximumSize(new Dimension(320, field.getPreferredSize().height + 10));
		field.setAlignmentX(Component.CENTER_ALIGNMENT);
		field.putClientProperty("JTextField.placeholderText", "Your name");

		cont.setAlignmentX(Component.CENTER_ALIGNMENT);
		cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
		cancel.setVisible(false);
		cancel.addActionListener(e -> listener.onCancel());

		statusLine.setForeground(muted());
		statusLine.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(cont);

		column.add(mark);
		column.add(Box.createVerticalStrut(16));
		column.add(hello);
		column.add(Box.createVerticalStrut(4));
		column.add(title);
		column.add(Box.createVerticalStrut(10));
		column.add(subtitle);
		column.add(Box.createVerticalStrut(22));
		column.add(field);
		column.add(Box.createVerticalStrut(16));
		column.add(buttons);
		column.add(Box.createVerticalStrut(12));
		column.add(statusLine);

		add(column);

		// Enter submits.
		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");
		field.getActionMap().put("submit", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				submit();
			}
		});
		cont.addActionListener(e -> submit());
		field.getDocument().addDocumentListener((SimpleDocumentListener) e -> refresh());
		field.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE && cancel.isVisible()) listener.onCancel();
			}
		});
		refresh();
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	public void setSubtitle(String text) {
		subtitle.setText(text);
	}

	/**
	 * Prepares the screen for showing.
	 *
	 * @param initial      name to pre-fill
	 * @param showCancel   whether a Cancel option is offered (changing name, not first run)
	 */
	public void prepare(String initial, boolean showCancel) {
		field.setText(initial == null ? "" : initial);
		field.selectAll();
		cancel.setVisible(showCancel);
		setBusy(null);
		refresh();
	}

	public void focusField() {
		field.requestFocusInWindow();
		field.selectAll();
	}

	/** Shows a working message and locks input, or clears it when {@code message} is null. */
	public void setBusy(String message) {
		boolean busy = message != null;
		field.setEnabled(!busy);
		cont.setEnabled(!busy && nameValid());
		cancel.setEnabled(!busy);
		statusLine.setForeground(muted());
		statusLine.setText(busy ? message : " ");
	}

	public void setError(String message) {
		field.setEnabled(true);
		cancel.setEnabled(true);
		statusLine.setForeground(ERROR_RED);
		statusLine.setText(message);
		refresh();
		focusField();
	}

	private boolean nameValid() {
		return !Identity.sanitise(field.getText()).isEmpty();
	}

	private void refresh() {
		cont.setEnabled(nameValid());
	}

	private void submit() {
		if (!nameValid()) {
			setError("Please enter a name.");
			return;
		}
		listener.onNameEntered(field.getText());
	}

	/** A DocumentListener whose three methods collapse to one callback. */
	@FunctionalInterface
	private interface SimpleDocumentListener extends DocumentListener {
		void update(DocumentEvent e);

		@Override
		default void insertUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void removeUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void changedUpdate(DocumentEvent e) {
			update(e);
		}
	}
}
