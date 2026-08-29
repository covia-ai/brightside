package covia.brightside.ui.onboarding;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

import covia.brightside.ui.Icons;
import covia.brightside.ui.LAF;

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

		// Remember-me, with an explicit plaintext warning and fuller detail on hover.
		remember.setOpaque(false);
		remember.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel info = infoIcon("Anyone who can read files as your OS account can unlock Brightside. "
			+ "Only enable this on a computer and account you trust; untick it to delete the file.");
		JPanel rememberRow = new JPanel();
		rememberRow.setOpaque(false);
		rememberRow.setLayout(new BoxLayout(rememberRow, BoxLayout.X_AXIS));
		rememberRow.setAlignmentX(CENTER_ALIGNMENT);
		rememberRow.add(remember);
		rememberRow.add(Box.createHorizontalStrut(6));
		rememberRow.add(info);
		rememberRow.setMaximumSize(rememberRow.getPreferredSize());

		unlock.setAlignmentX(CENTER_ALIGNMENT);
		status.setAlignmentX(CENTER_ALIGNMENT);
		status.setHorizontalAlignment(SwingConstants.CENTER);

		JLabel forgot = OnboardingUI.caption("Forgot passphrase?");
		forgot.setAlignmentX(CENTER_ALIGNMENT);
		forgot.setForeground(LAF.ACCENT);
		forgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		forgot.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (javax.swing.SwingUtilities.isLeftMouseButton(e)) listener.onForgot();
			}
		});

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
		status.setForeground(OnboardingUI.muted());
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
		status.setForeground(OnboardingUI.muted());
		status.setText("Unlocking…");
	}

	/** Re-enable with an error message (wrong passphrase). */
	public void showError(String message) {
		field.setEnabled(true);
		unlock.setEnabled(true);
		status.setForeground(new Color(0xE5, 0x53, 0x53));
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

	/** A small circled-i info glyph, painted (no font dependency), carrying a tooltip. */
	private static JLabel infoIcon(String tooltip) {
		Icon icon = new Icon() {
			@Override
			public int getIconWidth() {
				return 16;
			}

			@Override
			public int getIconHeight() {
				return 16;
			}

			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(OnboardingUI.muted());
				g2.drawOval(x + 1, y + 1, 13, 13);
				g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
				FontMetrics fm = g2.getFontMetrics();
				String s = "i";
				g2.drawString(s, x + 8 - fm.stringWidth(s) / 2f, y + 8 + (fm.getAscent() - fm.getDescent()) / 2f);
				g2.dispose();
			}
		};
		JLabel l = new JLabel(icon);
		l.setToolTipText(tooltip);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return l;
	}
}
