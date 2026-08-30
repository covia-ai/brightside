package brightside.ui.onboarding;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.ui.components.Buttons;
import brightside.ui.components.Documents;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;
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
	private final TextArea phrase = new TextArea(3, 30).placeholder("your twelve or twenty-four words…");
	private final JLabel phraseStatus = Labels.small(" ");
	private final JPasswordField pass1 = new JPasswordField(22);
	private final JPasswordField pass2 = new JPasswordField(22);
	private final OnboardingUI.Strength strength = new OnboardingUI.Strength();
	private final JLabel status = Labels.small(" ");
	private final JButton recover = Buttons.primary("Recover");

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

		JLabel title = Labels.title("Recover Brightside");
		SelectableText sub = SelectableText.description("Restore access with your recovery phrase and set a new passphrase.");
		SelectableText warn = SelectableText.description("Your conversations and memory are encrypted to your identity, "
			+ "not your passphrase — so your 12- or 24-word recovery phrase reopens the existing vault. You'll set a "
			+ "new passphrase now; you may need to re-enter provider API keys afterwards.");
		warn.setMaximumSize(new Dimension(500, Integer.MAX_VALUE));

		phrase.setLineWrap(true);
		phrase.setWrapStyleWord(true);
		Documents.onChange(phrase, this::validatePhrase);
		JScrollPane sp = new JScrollPane(phrase);
		sp.setPreferredSize(new Dimension(480, 76));
		sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));

		pass1.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "New passphrase");
		pass2.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Confirm new passphrase");
		for (JPasswordField p : new JPasswordField[] { pass1, pass2 }) {
			Styles.style(p, "font: +2");
			p.setMaximumSize(new Dimension(340, p.getPreferredSize().height + 8));
		}
		Documents.onChange(pass1, () -> strength.set(OnboardingUI.scorePassphrase(pass1.getPassword())));

		JButton cancel = Buttons.secondary("Cancel");
		cancel.addActionListener(e -> dispose());
		recover.addActionListener(e -> onRecover());

		JPanel buttons = Panels.row();
		buttons.add(status);
		buttons.add(Box.createHorizontalGlue());
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(10));
		buttons.add(recover);

		JPanel centre = Panels.column();
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
		Styles.classes(phraseStatus, Styles.SMALL, ok ? Styles.SUCCESS : Styles.MUTED);
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
		Styles.classes(status, Styles.SMALL, Styles.ERROR);
		status.setText(message);
	}
}
