package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * <b>Access token</b> — mints a venue-signed JWT that authenticates as this
 * install's identity against the local venue API, with a chosen expiry. The
 * token is shown once (in a selectable area) for copying and is never written
 * to disk. A separate surface from Model &amp; API key. Non-modal.
 */
@SuppressWarnings("serial")
public final class AccessTokenDialog extends JDialog {

	/** Mints a token valid for {@code seconds}, or returns null if unavailable. */
	public interface Minter {
		String mint(long seconds);
	}

	private record Exp(String label, long seconds) {
		@Override
		public String toString() {
			return label;
		}
	}

	private final Minter minter;
	private final JComboBox<Exp> expiry = new JComboBox<>();
	private final JTextArea token = new JTextArea(4, 30);
	private final JButton copy = new JButton("Copy");
	private final JLabel note = new JLabel(" ");

	public AccessTokenDialog(Frame owner, Minter minter) {
		super(owner, "Access token", false);
		this.minter = minter;
		setContentPane(build());
		pack();
		setMinimumSize(new Dimension(540, getPreferredSize().height));
		setLocationRelativeTo(owner);
	}

	private JComponent build() {
		JPanel root = new JPanel(new BorderLayout());
		root.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

		JLabel title = new JLabel("Access token");
		title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 4f).deriveFont(Font.BOLD));
		JLabel blurb = new JLabel("<html><div style='width:470px'>A bearer token for this venue's local API "
			+ "(<code>Authorization: Bearer …</code>), authenticating as your identity. Treat it like a password "
			+ "— it grants access to your agent. It is not stored on disk.</div></html>");
		blurb.setForeground(mutedColor());

		expiry.setModel(new DefaultComboBoxModel<>(new Exp[] {
			new Exp("5 minutes", 300L),
			new Exp("1 hour", 3600L),
			new Exp("1 day", 86_400L),
			new Exp("30 days", 2_592_000L),
		}));
		expiry.setSelectedIndex(1);
		expiry.setMaximumSize(expiry.getPreferredSize());

		JButton generate = new JButton("Generate");
		generate.putClientProperty("JButton.buttonType", "roundRect");
		generate.setBackground(LAF.ACCENT);
		generate.setForeground(Color.WHITE);
		generate.addActionListener(e -> onGenerate());

		token.setEditable(false);
		token.setLineWrap(true);
		token.setWrapStyleWord(false);
		token.putClientProperty("JTextArea.placeholderText", "Generated token appears here");
		JScrollPane tokenScroll = new JScrollPane(token);
		tokenScroll.setPreferredSize(new Dimension(470, 96));
		tokenScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

		copy.setEnabled(false);
		copy.addActionListener(e -> onCopy());
		note.putClientProperty("FlatLaf.styleClass", "small");
		note.setForeground(mutedColor());

		JLabel expLabel = new JLabel("Expires in ");
		expLabel.setForeground(mutedColor());
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.add(expLabel);
		top.add(expiry);
		top.add(Box.createHorizontalStrut(10));
		top.add(generate);
		top.add(Box.createHorizontalGlue());

		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.add(note);
		buttons.add(Box.createHorizontalGlue());
		buttons.add(copy);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(close);

		JPanel centre = new JPanel();
		centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
		for (JComponent c : new JComponent[] { title, blurb, top, tokenScroll }) {
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		centre.add(title);
		centre.add(Box.createVerticalStrut(10));
		centre.add(blurb);
		centre.add(Box.createVerticalStrut(16));
		centre.add(top);
		centre.add(Box.createVerticalStrut(12));
		centre.add(tokenScroll);

		root.add(centre, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		return root;
	}

	private void onGenerate() {
		Exp sel = (Exp) expiry.getSelectedItem();
		long secs = (sel != null) ? sel.seconds() : 3600L;
		String jwt = minter.mint(secs);
		if (jwt == null) {
			token.setText("");
			copy.setEnabled(false);
			note.setForeground(new Color(0xE5, 0x53, 0x53));
			note.setText("Couldn't mint a token — the venue isn't ready.");
			return;
		}
		token.setText(jwt);
		token.setCaretPosition(0);
		copy.setEnabled(true);
		note.setForeground(mutedColor());
		note.setText("Valid for " + sel.label() + ". Treat it like a password.");
	}

	private void onCopy() {
		String t = token.getText();
		if (t == null || t.isEmpty()) return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null);
		note.setForeground(mutedColor());
		note.setText("Copied to the clipboard.");
	}

	private static Color mutedColor() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}
}
