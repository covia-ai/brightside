package brightside.ui.onboarding;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.ui.Icons;
import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The returning-user screen: enter the vault passphrase to unlock Brightside.
 * A wrong passphrase is caught by the authenticated encryption on
 * {@code identity.enc} and reported without leaking anything.
 *
 * <p>The optional remembered-unlock choice explicitly stores the passphrase as
 * plaintext; a "Forgot passphrase?" link opens recovery from the recovery
 * phrase.
 */
@SuppressWarnings("serial")
public final class UnlockPanel extends JPanel {

	public interface Listener {
		void onUnlock(char[] passphrase, boolean remember);

		void onForgot();
	}

	private final Listener listener;
	private final JPasswordField field = new JPasswordField(22);
	private final JCheckBox remember = new JCheckBox("Store passphrase in plaintext on this computer");
	private final JButton unlock = Buttons.primary("Unlock");
	private final JLabel status = Labels.small(" ");

	public UnlockPanel(Listener listener) {
		super(new GridBagLayout());
		this.listener = listener;

		JPanel col = Panels.column();
		col.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

		JLabel mark = Labels.icon(new ImageIcon(Icons.icon(64)));
		mark.setAlignmentX(CENTER_ALIGNMENT);
		JLabel title = Labels.title("Welcome back");
		title.setAlignmentX(CENTER_ALIGNMENT);
		JLabel sub = Styles.classes(Labels.html("Enter your passphrase to unlock Brightside.", 420, SwingConstants.CENTER), Styles.MUTED);
		sub.setAlignmentX(CENTER_ALIGNMENT);

		field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Passphrase");
		Styles.style(field, "font: +3");
		field.setMaximumSize(new Dimension(320, field.getPreferredSize().height + 12));
		field.setAlignmentX(CENTER_ALIGNMENT);

		// Remember-me, with an explicit plaintext warning and fuller detail on hover.
		remember.setOpaque(false);
		remember.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel info = Labels.icon(Lucide.icon("info", 16, Theme::muted));
		info.setToolTipText("Anyone who can read files as your OS account can unlock Brightside. "
			+ "Only enable this on a computer and account you trust; untick it to delete the file.");
		info.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JPanel rememberRow = Panels.row();
		rememberRow.setAlignmentX(CENTER_ALIGNMENT);
		rememberRow.add(remember);
		rememberRow.add(Box.createHorizontalStrut(6));
		rememberRow.add(info);
		rememberRow.setMaximumSize(rememberRow.getPreferredSize());

		unlock.setAlignmentX(CENTER_ALIGNMENT);
		status.setAlignmentX(CENTER_ALIGNMENT);
		status.setHorizontalAlignment(SwingConstants.CENTER);

		PressButton forgot = Buttons.link("Forgot passphrase?", listener::onForgot);
		forgot.setAlignmentX(CENTER_ALIGNMENT);

		col.add(mark);
		col.add(Box.createVerticalStrut(16));
		col.add(title);
		col.add(Box.createVerticalStrut(10));
		col.add(sub);
		col.add(Box.createVerticalStrut(22));
		col.add(field);
		col.add(Box.createVerticalStrut(12));
		col.add(rememberRow);
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

	/** Pre-fill a remembered passphrase and tick the box (untick to forget it). */
	public void prefill(char[] passphrase) {
		if (passphrase == null) return;
		field.setText(new String(passphrase));
		remember.setSelected(true);
	}

	/** Clear to a fresh, enabled state — e.g. when showing the lock screen on log out. */
	public void reset() {
		field.setText("");
		field.setEnabled(true);
		unlock.setEnabled(true);
		Styles.classes(status, Styles.SMALL, Styles.MUTED);
		status.setText(" ");
	}

	private void submit() {
		char[] pw = field.getPassword();
		if (pw.length == 0) return;
		field.setText("");
		setBusy();
		listener.onUnlock(pw, remember.isSelected());
	}

	private void setBusy() {
		field.setEnabled(false);
		unlock.setEnabled(false);
		Styles.classes(status, Styles.SMALL, Styles.MUTED);
		status.setText("Unlocking…");
	}

	/** Re-enable with an error message (wrong passphrase). */
	public void showError(String message) {
		field.setEnabled(true);
		unlock.setEnabled(true);
		Styles.classes(status, Styles.SMALL, Styles.ERROR);
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
