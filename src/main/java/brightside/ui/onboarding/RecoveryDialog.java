package brightside.ui.onboarding;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import brightside.vault.Mnemonic;

/**
 * Recovery from the unlock screen ("Forgot passphrase?"). Restores the identity
 * from a BIP39 recovery phrase and sets a new passphrase. The identity-derived
 * store key reopens an existing encrypted store; provider credentials encrypted
 * by the forgotten passphrase must be entered again. Modal; only collects and
 * validates — the caller does the work.
 */
@SuppressWarnings("serial")
public final class RecoveryDialog extends JDialog {

	public interface Listener {
		void onRecover(String seedHex, char[] passphrase);
	}

	private final Listener listener;
	private final JTextArea phrase = new JTextArea(3, 30);
	private final JLabel phraseStatus = OnboardingUI.caption(" ");
	private final JPasswordField pass1 = new JPasswordField(22);
	private final JPasswordField pass2 = new JPasswordField(22);
	private final OnboardingUI.Strength strength = new OnboardingUI.Strength();
	private final JLabel status = OnboardingUI.caption(" ");
	private final JButton recover = OnboardingUI.primary("Recover");

	public RecoveryDialog(Frame owner, Listener listener) {
		super(owner, "Recover Brightside", true);
		this.listener = listener;
		setContentPane(build());
		pack();
		setMinimumSize(new Dimension(560, getPreferredSize().height));
		setLocationRelativeTo(owner);
	}

	private JComponent build() {
		JPanel root = new JPanel(new BorderLayout());
		root.setBorder(BorderFactory.createEmptyBorder(22, 26, 18, 26));

		JTextArea title = OnboardingUI.selectable("Recover Brightside");
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize2D() + 8f));
		JTextArea sub = OnboardingUI.selectable("Restore access with your recovery phrase and set a new passphrase.");
		sub.setForeground(OnboardingUI.muted());
		JTextArea warn = OnboardingUI.selectable("Your conversations and memory are encrypted to your identity, "
			+ "not your passphrase — so your 12- or 24-word recovery phrase reopens the existing vault. You'll set a "
			+ "new passphrase now; you may need to re-enter provider API keys afterwards.");
		warn.setForeground(OnboardingUI.muted());
		warn.setMaximumSize(new Dimension(500, Integer.MAX_VALUE));

		phrase.setLineWrap(true);
		phrase.setWrapStyleWord(true);
		phrase.putClientProperty("JTextArea.placeholderText", "your twelve or twenty-four words…");
		phrase.getDocument().addDocumentListener((Simple) e -> validatePhrase());
		JScrollPane sp = new JScrollPane(phrase);
		sp.setPreferredSize(new Dimension(480, 76));
		sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));

		pass1.putClientProperty("JTextField.placeholderText", "New passphrase");
		pass2.putClientProperty("JTextField.placeholderText", "Confirm new passphrase");
		for (JPasswordField p : new JPasswordField[] { pass1, pass2 }) {
			p.setFont(p.getFont().deriveFont(p.getFont().getSize2D() + 2f));
			p.setMaximumSize(new Dimension(340, p.getPreferredSize().height + 8));
		}
		pass1.getDocument().addDocumentListener((Simple) e -> strength.set(OnboardingUI.scorePassphrase(pass1.getPassword())));

		JButton cancel = OnboardingUI.secondary("Cancel");
		cancel.addActionListener(e -> dispose());
		recover.addActionListener(e -> onRecover());

		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.add(status);
		buttons.add(Box.createHorizontalGlue());
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(10));
		buttons.add(recover);

		JPanel centre = new JPanel();
		centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
		for (JComponent c : new JComponent[] { title, sub, warn, sp, phraseStatus, pass1, pass2, strength }) {
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		centre.add(title);
		centre.add(Box.createVerticalStrut(8));
		centre.add(sub);
		centre.add(Box.createVerticalStrut(12));
		centre.add(warn);
		centre.add(Box.createVerticalStrut(16));
		centre.add(sp);
		centre.add(Box.createVerticalStrut(4));
		centre.add(phraseStatus);
		centre.add(Box.createVerticalStrut(14));
		centre.add(pass1);
		centre.add(Box.createVerticalStrut(8));
		centre.add(pass2);
		centre.add(Box.createVerticalStrut(10));
		centre.add(strength);

		root.add(centre, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		return root;
	}

	private void validatePhrase() {
		String t = phrase.getText().trim();
		if (t.isEmpty()) {
			phraseStatus.setText(" ");
			return;
		}
		boolean ok = Mnemonic.isValid(t);
		phraseStatus.setForeground(ok ? new Color(0x3F, 0xB9, 0x50) : OnboardingUI.muted());
		phraseStatus.setText(ok ? "Valid recovery phrase." : "Not a valid phrase yet…");
	}

	private void onRecover() {
		String words = phrase.getText().trim();
		if (!Mnemonic.isValid(words)) {
			fail("Enter your 12- or 24-word recovery phrase.");
			return;
		}
		char[] a = pass1.getPassword();
		char[] b = pass2.getPassword();
		if (a.length < 8) {
			Arrays.fill(a, '\0');
			Arrays.fill(b, '\0');
			fail("Use at least 8 characters for the new passphrase.");
			return;
		}
		if (!Arrays.equals(a, b)) {
			Arrays.fill(a, '\0');
			Arrays.fill(b, '\0');
			fail("The passphrases don't match.");
			return;
		}
		Arrays.fill(b, '\0');
		pass1.setText("");
		pass2.setText("");
		String seedHex = Mnemonic.toSeedHex(words);
		dispose();
		listener.onRecover(seedHex, a);
	}

	private void fail(String message) {
		status.setForeground(new Color(0xE5, 0x53, 0x53));
		status.setText(message);
	}

	/** A DocumentListener whose three methods collapse to one callback. */
	@FunctionalInterface
	private interface Simple extends DocumentListener {
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
