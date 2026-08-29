package covia.brightside.ui.settings;

import java.awt.BorderLayout;
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
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The <b>Identity</b> settings page: the owner's editable name, which principal
 * the app acts as (themselves, or the venue operator — advanced), stable Covia
 * user DID, home venue identity and signing key, plus the primary Ed25519 seed
 * hidden behind passphrase re-authentication and a privacy (eye) toggle.
 */
@SuppressWarnings("serial")
public final class ProfilePanel extends SettingsPage {

	/** Actions the identity page offers. */
	public interface Host {
		void saveName(String name);

		String revealPrimarySeed(char[] passphrase) throws Exception;

		/** Act as the venue operator ({@code true}) or as the named user. */
		void actAs(boolean operator);
	}

	private static final String MASK = "•".repeat(64);

	private final Host host;
	private final JTextField nameField = new JTextField(18);
	private final JTextArea userDidV = SettingsUI.technicalValue("—");
	private final JTextArea venueDidV = SettingsUI.technicalValue("—");
	private final JTextArea pubV = SettingsUI.technicalValue("—");
	private final JTextArea seedField = new JTextArea(1, 40);
	private final ConvexIdenticon userDidIcon = new ConvexIdenticon();
	private final ConvexIdenticon venueDidIcon = new ConvexIdenticon();
	private final ConvexIdenticon publicKeyIcon = new ConvexIdenticon();
	private final ConvexIdenticon seedIcon = new ConvexIdenticon();
	private final JButton reveal = new JButton();
	private final JButton copySeed = new JButton("Copy");
	private final EyeIcon eye = new EyeIcon();
	/** Act as: the named user (everyday), or the venue operator (advanced). Package-visible for tests. */
	final JRadioButton asUser = new JRadioButton("Me");
	final JRadioButton asOperator = new JRadioButton("Venue operator");
	private String seedHex;
	private String baselineName = "";
	private boolean seedAvailable;
	private boolean revealed;
	private long identityVersion;

	public ProfilePanel(Host host) {
		super("Save");
		this.host = host;
		build();
	}

	/**
	 * Refresh with the current identity and acting principal; hides the primary
	 * seed and re-baselines Save. Selecting a principal here fires no host call.
	 */
	public void refresh(String name, String userDid, String venueDid, String publicKeyHex,
			boolean seedAvailable, boolean actingAsOperator) {
		identityVersion++;
		baselineName = (name != null) ? name : "";
		nameField.setText(baselineName);
		asUser.setText((name != null && !name.isBlank()) ? name : "Me");
		(actingAsOperator ? asOperator : asUser).setSelected(true);
		boolean canSwitch = name != null && venueDid != null;
		asUser.setEnabled(canSwitch);
		asOperator.setEnabled(canSwitch);
		userDidV.setText(userDid != null ? userDid : "—");
		venueDidV.setText(venueDid != null ? venueDid : "—");
		pubV.setText(publicKeyHex != null ? publicKeyHex : "—");
		userDidIcon.setPublicKeyHex(publicKeyHex);
		venueDidIcon.setPublicKeyHex(publicKeyHex);
		publicKeyIcon.setPublicKeyHex(publicKeyHex);
		seedIcon.setPublicKeyHex(publicKeyHex);
		this.seedAvailable = seedAvailable;
		this.seedHex = null;
		revealed = false;
		eye.open = false;
		seedField.setText(seedAvailable ? MASK : "unavailable");
		seedField.setCaretPosition(0);
		reveal.setEnabled(seedAvailable);
		copySeed.setEnabled(false);
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

		seedField.setEditable(false);
		seedField.setLineWrap(true);
		seedField.setOpaque(false);
		seedField.setBorder(null);
		seedField.setFont(SettingsUI.technicalFont(seedField.getFont()));
		seedField.setText(MASK);

		reveal.setIcon(eye);
		reveal.setToolTipText("Show / hide the primary Ed25519 seed");
		reveal.setContentAreaFilled(false);
		reveal.setBorderPainted(false);
		reveal.setFocusPainted(false);
		reveal.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		reveal.addActionListener(e -> toggle());

		copySeed.putClientProperty("FlatLaf.styleClass", "small");
		copySeed.setEnabled(false);
		copySeed.setToolTipText("Copy the primary seed to the clipboard");
		copySeed.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copySeed.addActionListener(e -> {
			if (seedHex != null) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(seedHex), null);
			}
		});

		JPanel keyRow = new JPanel();
		keyRow.setOpaque(false);
		keyRow.setLayout(new BoxLayout(keyRow, BoxLayout.X_AXIS));
		seedField.setMaximumSize(new Dimension(360, 60));
		keyRow.add(seedIcon);
		keyRow.add(Box.createHorizontalStrut(8));
		keyRow.add(seedField);
		keyRow.add(Box.createHorizontalStrut(8));
		keyRow.add(reveal);
		keyRow.add(Box.createHorizontalStrut(4));
		keyRow.add(copySeed);
		keyRow.add(Box.createHorizontalGlue());

		JTextArea warn = SettingsUI.selectable("The primary seed is the master secret for your venue identity — anyone who has "
			+ "it can act as you and decrypt a copied vault. Reveal it only for recovery or advanced tools, and never share it. "
			+ "Your recovery phrase reproduces the same seed.");
		warn.setForeground(new Color(0xE5, 0x8A, 0x3A));

		ButtonGroup actAs = new ButtonGroup();
		actAs.add(asUser);
		actAs.add(asOperator);
		asUser.setOpaque(false);
		asOperator.setOpaque(false);
		asUser.setToolTipText("Everyday use: your own assistant, conversations and Inbox");
		asOperator.setToolTipText("Advanced: the venue's own agents — Odin, the administrator — and the venue's Inbox");
		// Action events fire only on a click, so refresh() can select without calling the host.
		asUser.addActionListener(e -> host.actAs(false));
		asOperator.addActionListener(e -> host.actAs(true));
		asUser.setEnabled(false);
		asOperator.setEnabled(false);
		JPanel actAsRow = new JPanel();
		actAsRow.setOpaque(false);
		actAsRow.setLayout(new BoxLayout(actAsRow, BoxLayout.X_AXIS));
		actAsRow.add(asUser);
		actAsRow.add(Box.createHorizontalStrut(16));
		actAsRow.add(asOperator);
		actAsRow.add(Box.createHorizontalGlue());
		JTextArea actAsNote = SettingsUI.selectable("As the venue operator you see and talk to the venue's own agents "
			+ "— Odin, who administers this Brightside — and answer the venue's Inbox. Everything else is as yourself; "
			+ "Brightside starts as you on every launch.");

		addDescription("Your name is what Brightside and other people call you. Your Covia user DID is the stable "
			+ "identity peers use; changing your name does not change it. Your home venue controls that identity with "
			+ "the Ed25519 signing key shown here.");
		addField("Your name", nameField);
		addField("Act as", actAsRow);
		addSpan(actAsNote, "gapbottom 8");
		addFieldTop("Covia user DID", copyableRow(userDidIcon, userDidV, "Covia user DID"));
		addFieldTop("Home venue DID", copyableRow(venueDidIcon, venueDidV, "home venue DID"));
		addFieldTop("Venue signing key", copyableRow(publicKeyIcon, pubV, "Ed25519 venue signing public key"));
		addFieldTop("Primary seed (Advanced)", keyRow);
		addSpan(warn, "gaptop 4");
	}

	private static JPanel copyableRow(ConvexIdenticon identicon, JTextArea value, String subject) {
		JButton copy = new JButton("Copy");
		copy.putClientProperty("FlatLaf.styleClass", "small");
		copy.setToolTipText("Copy the " + subject + " to the clipboard");
		copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copy.addActionListener(e -> {
			String text = value.getText();
			if (text != null && !text.isBlank() && !"—".equals(text)) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			}
		});

		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.add(identicon, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		row.add(copy, BorderLayout.EAST);
		return row;
	}

	private void updateDirty() {
		String n = nameField.getText().trim();
		primary.setEnabled(!n.equals(baselineName) && !n.isEmpty());
	}

	private void toggle() {
		if (revealed) {
			hidePrimarySeed();
			return;
		}
		if (!seedAvailable) return;

		JPasswordField passphrase = new JPasswordField(24);
		int choice = JOptionPane.showConfirmDialog(this, passphrase,
			"Enter your Brightside passphrase to reveal the primary seed",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) return;
		char[] entered = passphrase.getPassword();
		passphrase.setText("");
		if (entered.length == 0) return;
		long version = identityVersion;

		reveal.setEnabled(false);
		copySeed.setEnabled(false);
		setNote("Checking your passphrase…", false);
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return host.revealPrimarySeed(entered);
			}

			@Override
			protected void done() {
				java.util.Arrays.fill(entered, '\0');
				if (identityVersion != version || !seedAvailable) {
					hidePrimarySeed();
					reveal.setEnabled(seedAvailable);
					return;
				}
				reveal.setEnabled(seedAvailable);
				try {
					seedHex = get();
					revealed = true;
					eye.open = true;
					seedField.setText(seedHex);
					copySeed.setEnabled(true);
					setNote("Primary seed revealed for this session only.", false);
				} catch (Exception e) {
					hidePrimarySeed();
					setNote("That passphrase didn't unlock this identity.", true);
				}
				repaint();
			}
		}.execute();
	}

	private void hidePrimarySeed() {
		seedHex = null;
		revealed = false;
		eye.open = false;
		seedField.setText(seedAvailable ? MASK : "unavailable");
		seedField.setCaretPosition(0);
		copySeed.setEnabled(false);
		repaint();
	}

	/** Drops any displayed or cached private material when the user logs out. */
	public void clearSensitive() {
		identityVersion++;
		baselineName = "";
		nameField.setText("");
		asUser.setText("Me");
		asUser.setSelected(true);
		asUser.setEnabled(false);
		asOperator.setEnabled(false);
		seedAvailable = false;
		hidePrimarySeed();
		userDidV.setText("—");
		venueDidV.setText("—");
		pubV.setText("—");
		userDidIcon.setPublicKeyHex(null);
		venueDidIcon.setPublicKeyHex(null);
		publicKeyIcon.setPublicKeyHex(null);
		seedIcon.setPublicKeyHex(null);
		clearNote();
		updateDirty();
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
