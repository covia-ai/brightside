package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import covia.brightside.BrightSide;
import covia.brightside.Identity;

/**
 * The "choose a name" screen. A small modal dialog that asks for a user name
 * and previews the venue principal it becomes ({@code u:mike}). Returns the
 * chosen {@link Identity}, or {@code null} if the user cancelled.
 */
@SuppressWarnings("serial")
public final class IdentityDialog extends JDialog {

	private final JTextField field = new JTextField(18);
	private final JLabel preview = new JLabel(" ");
	private final JButton ok = new JButton("Continue");
	private Identity result;

	private IdentityDialog(Frame owner, String title, String prompt, String initial) {
		super(owner, title, true);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JLabel heading = new JLabel(BrightSide.APP_NAME);
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 6f));
		heading.setIcon(new ImageIcon(Icons.icon(32)));
		heading.setIconTextGap(10);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel ask = new JLabel(prompt);
		ask.setAlignmentX(Component.LEFT_ALIGNMENT);

		field.setText(initial);
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));

		preview.setForeground(mutedColour());
		preview.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(16, 18, 8, 18));
		body.add(heading);
		body.add(Box.createVerticalStrut(14));
		body.add(ask);
		body.add(Box.createVerticalStrut(6));
		body.add(field);
		body.add(Box.createVerticalStrut(6));
		body.add(preview);

		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> {
			result = null;
			dispose();
		});
		ok.addActionListener(e -> accept());
		getRootPane().setDefaultButton(ok);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		buttons.add(cancel);
		buttons.add(ok);

		field.getDocument().addDocumentListener((SimpleDocumentListener) e -> refreshPreview());
		field.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					result = null;
					dispose();
				}
			}
		});
		refreshPreview();

		add(body, BorderLayout.CENTER);
		add(buttons, BorderLayout.SOUTH);
		pack();
		setMinimumSize(new Dimension(380, getPreferredSize().height));
		setLocationRelativeTo(owner);
	}

	private static Color mutedColour() {
		Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	private void refreshPreview() {
		String clean = Identity.sanitise(field.getText());
		boolean valid = !clean.isEmpty();
		ok.setEnabled(valid);
		preview.setHorizontalAlignment(SwingConstants.LEFT);
		preview.setText(valid
			? "You'll be  u:" + clean + "  on this venue."
			: "Enter at least one letter or digit.");
	}

	private void accept() {
		String clean = Identity.sanitise(field.getText());
		if (clean.isEmpty()) return;
		result = Identity.of(clean);
		dispose();
	}

	/**
	 * Shows the dialog and blocks until the user chooses or cancels. Must be
	 * called on the event thread.
	 *
	 * @param owner   the parent window, or {@code null}
	 * @param initial the name to pre-fill (a suggestion, or the current name)
	 * @param prompt  the question shown above the field
	 * @return the chosen identity, or {@code null} if cancelled
	 */
	public static Identity ask(Frame owner, String title, String prompt, String initial) {
		IdentityDialog dialog = new IdentityDialog(owner, title, prompt, initial);
		dialog.setVisible(true);
		return dialog.result;
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
