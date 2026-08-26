package covia.brightside.ui.settings;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The <b>Profile</b> settings page: your display name (editable, with a dirty-gated
 * Save), your identity DID and public key (read-only, selectable), and the
 * <b>private key</b> (the Ed25519 seed) hidden behind a privacy (eye) toggle.
 */
@SuppressWarnings("serial")
public final class ProfilePanel extends SettingsPage {

	/** Actions the profile offers. */
	public interface Host {
		void saveName(String name);
	}

	private static final String MASK = "•".repeat(64);

	private final Host host;
	private final JTextField nameField = new JTextField(18);
	private final JTextArea didV = SettingsUI.selectable("—");
	private final JTextArea pubV = SettingsUI.selectable("—");
	private final JTextArea keyField = new JTextArea(1, 40);
	private final JButton copy = new JButton("Copy");
	private final EyeIcon eye = new EyeIcon();
	private String seedHex;
	private String baselineName = "";
	private boolean revealed;

	public ProfilePanel(Host host) {
		super("Save");
		this.host = host;
		build();
	}

	/** Refresh with the current identity; hides the private key and re-baselines Save. */
	public void refresh(String name, String did, String publicKeyHex, String seedHex) {
		baselineName = (name != null) ? name : "";
		nameField.setText(baselineName);
		didV.setText(did != null ? did : "—");
		pubV.setText(publicKeyHex != null ? publicKeyHex : "—");
		this.seedHex = seedHex;
		revealed = false;
		eye.open = false;
		keyField.setText(seedHex == null ? "unavailable" : MASK);
		keyField.setCaretPosition(0);
		copy.setEnabled(false);
		clearNote();
		updateDirty();
		repaint();
	}

	private void build() {
		nameField.setToolTipText("The name your assistant addresses you by");
		nameField.getDocument().addDocumentListener((SimpleDoc) e -> updateDirty());
		primary.setEnabled(false);
		primary.setToolTipText("Save your name");
		onPrimary(() -> {
			String n = nameField.getText().trim();
			if (!n.isEmpty()) host.saveName(n);
		});

		keyField.setEditable(false);
		keyField.setLineWrap(true);
		keyField.setOpaque(false);
		keyField.setBorder(null);
		keyField.setFont(UIManager.getFont("Label.font"));
		keyField.setText(MASK);

		JButton reveal = new JButton(eye);
		reveal.setToolTipText("Show / hide the private key");
		reveal.setContentAreaFilled(false);
		reveal.setBorderPainted(false);
		reveal.setFocusPainted(false);
		reveal.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		reveal.addActionListener(e -> toggle());

		copy.putClientProperty("FlatLaf.styleClass", "small");
		copy.setEnabled(false);
		copy.setToolTipText("Copy the private key to the clipboard");
		copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copy.addActionListener(e -> {
			if (seedHex != null) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(seedHex), null);
			}
		});

		JPanel keyRow = new JPanel();
		keyRow.setOpaque(false);
		keyRow.setLayout(new BoxLayout(keyRow, BoxLayout.X_AXIS));
		keyField.setMaximumSize(new Dimension(360, 60));
		keyRow.add(keyField);
		keyRow.add(Box.createHorizontalStrut(8));
		keyRow.add(reveal);
		keyRow.add(Box.createHorizontalStrut(4));
		keyRow.add(copy);
		keyRow.add(Box.createHorizontalGlue());

		JTextArea warn = SettingsUI.selectable("Your private key is the master secret for this identity — anyone who has "
			+ "it can act as you. Reveal it only when you must, and never share it. (Your recovery phrase reproduces it too.)");
		warn.setForeground(new Color(0xE5, 0x8A, 0x3A));

		addDescription("Who you are on the Covia grid.");
		addField("Name", nameField);
		addFieldTop("Identity (DID)", didV);
		addFieldTop("Public key", pubV);
		addFieldTop("Private key", keyRow);
		addSpan(warn, "gaptop 4");
	}

	private void updateDirty() {
		String n = nameField.getText().trim();
		primary.setEnabled(!n.equals(baselineName) && !n.isEmpty());
	}

	private void toggle() {
		if (seedHex == null) return;
		revealed = !revealed;
		keyField.setText(revealed ? seedHex : MASK);
		keyField.setCaretPosition(0);
		copy.setEnabled(revealed);
		eye.open = revealed;
		repaint();
	}

	/** A small painted eye — open when revealed, struck-through when hidden. */
	private static final class EyeIcon implements Icon {
		private boolean open;

		@Override
		public int getIconWidth() {
			return 22;
		}

		@Override
		public int getIconHeight() {
			return 16;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(SettingsUI.muted());
			int w = 22, h = 16, cx = x + w / 2, cy = y + h / 2;
			g2.drawArc(x + 1, cy - 6, w - 2, 12, 0, 180);
			g2.drawArc(x + 1, cy - 6, w - 2, 12, 180, 180);
			g2.fillOval(cx - 3, cy - 3, 6, 6);
			if (!open) {
				g2.setColor(new Color(0xE5, 0x53, 0x53));
				g2.drawLine(x + 2, y + h - 2, x + w - 2, y + 2);
			}
			g2.dispose();
		}
	}

	/** A DocumentListener whose three methods collapse to one callback. */
	@FunctionalInterface
	private interface SimpleDoc extends DocumentListener {
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
