package covia.brightside.ui.onboarding;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
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
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

import covia.brightside.ui.Icons;

/**
 * The returning-user screen: enter the vault passphrase to unlock Brightside.
 * A wrong passphrase is caught cheaply (the encrypted store's header won't
 * verify) and reported without leaking anything.
 */
@SuppressWarnings("serial")
public final class UnlockPanel extends JPanel {

	public interface Listener {
		void onUnlock(char[] passphrase);
	}

	private final Listener listener;
	private final JPasswordField field = new JPasswordField(22);
	private final JButton unlock = OnboardingUI.primary("Unlock");
	private final JLabel status = OnboardingUI.caption(" ");

	public UnlockPanel(Listener listener) {
		super(new GridBagLayout());
		this.listener = listener;

		JPanel col = new JPanel();
		col.setOpaque(false);
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

		JLabel mark = new JLabel(new ImageIcon(Icons.icon(64)));
		mark.setAlignmentX(CENTER_ALIGNMENT);
		JLabel title = OnboardingUI.title("Welcome back");
		title.setAlignmentX(CENTER_ALIGNMENT);
		JLabel sub = OnboardingUI.subtitle("Enter your passphrase to unlock Brightside.");

		field.putClientProperty("JTextField.placeholderText", "Passphrase");
		field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 3f));
		field.setMaximumSize(new Dimension(320, field.getPreferredSize().height + 12));
		field.setAlignmentX(CENTER_ALIGNMENT);

		unlock.setAlignmentX(CENTER_ALIGNMENT);
		status.setAlignmentX(CENTER_ALIGNMENT);
		status.setHorizontalAlignment(SwingConstants.CENTER);

		JLabel forgot = OnboardingUI.caption("There's no passphrase reset — but your recovery phrase restores your identity.");
		forgot.setAlignmentX(CENTER_ALIGNMENT);

		col.add(mark);
		col.add(Box.createVerticalStrut(16));
		col.add(title);
		col.add(Box.createVerticalStrut(10));
		col.add(sub);
		col.add(Box.createVerticalStrut(22));
		col.add(field);
		col.add(Box.createVerticalStrut(14));
		col.add(unlock);
		col.add(Box.createVerticalStrut(12));
		col.add(status);
		col.add(Box.createVerticalStrut(18));
		col.add(forgot);

		add(col);

		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "unlock");
		field.getActionMap().put("unlock", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				submit();
			}
		});
		unlock.addActionListener(e -> submit());
	}

	private void submit() {
		char[] pw = field.getPassword();
		if (pw.length == 0) return;
		setBusy();
		listener.onUnlock(pw);
	}

	private void setBusy() {
		field.setEnabled(false);
		unlock.setEnabled(false);
		status.setForeground(OnboardingUI.muted());
		status.setText("Unlocking…");
	}

	/** Re-enable with an error message (wrong passphrase). */
	public void showError(String message) {
		field.setEnabled(true);
		unlock.setEnabled(true);
		status.setForeground(new java.awt.Color(0xE5, 0x53, 0x53));
		status.setText(message);
		field.selectAll();
		field.requestFocusInWindow();
	}

	public void focusField() {
		field.requestFocusInWindow();
	}

	public Component initialFocus() {
		return field;
	}
}
