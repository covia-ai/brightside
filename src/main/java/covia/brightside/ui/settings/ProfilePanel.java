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
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The <b>Identity</b> settings page: first, the user switcher — which principal
 * the app acts as (the named user, or the venue operator — advanced); then the
 * owner's editable name, stable Covia user DID, home venue identity and signing
 * key, plus the primary Ed25519 seed hidden behind passphrase re-authentication
 * and a privacy (eye) toggle.
 */
@SuppressWarnings("serial")
public final class ProfilePanel extends SettingsPage {

	/** Actions the identity page offers. */
	public interface Host {
		void saveName(String name);

		String revealPrimarySeed(char[] passphrase) throws Exception;

		/** Switch to acting as the venue operator ({@code true}) or as the named user. */
		void actAs(boolean operator);
	}

	/**
	 * A principal the app can act as: the named user ({@code <venueDID>:u:<slug>})
	 * or the venue operator (the venue's own DID). {@code label} is what the
	 * switcher shows.
	 */
	public record Principal(String label, String did, boolean operator) {
		@Override
		public String toString() {
			return label;
		}
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
	/** Switch user: the named user (everyday) or the venue operator (advanced). */
	private final JComboBox<Principal> principal = new JComboBox<>();
	/** True while {@link #refresh} sets the selection, so the host is only told about a person's choice. */
	private boolean refreshing;
	/** Whether the rows show the venue (operator) rather than the named user; the name is then read-only. */
	private boolean actingAsOperator;
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
	 * seed and re-baselines Save. The name and DID rows show the principal the
	 * app is acting as — the named user, or the venue itself when acting as the
	 * operator (its name, read-only, and its DID). Selecting a principal here
	 * fires no host call.
	 */
	public void refresh(String name, String userDid, String venueDid, String publicKeyHex,
			boolean seedAvailable, boolean actingAsOperator, String venueName) {
		identityVersion++;
		this.actingAsOperator = actingAsOperator;
		baselineName = (name != null) ? name : "";
		nameField.setText(actingAsOperator ? ((venueName != null) ? venueName : "") : baselineName);
		nameField.setEditable(!actingAsOperator);
		nameField.setToolTipText(actingAsOperator
			? "The venue's name; change it in the settings file"
			: "The name your assistant addresses you by");
		refreshing = true;
		try {
			principal.removeAllItems();
			boolean canSwitch = name != null && userDid != null && venueDid != null;
			if (canSwitch) {
				Principal user = new Principal(name + " (" + suffix(userDid) + ")", userDid, false);
				Principal operator = new Principal("Venue operator", venueDid, true);
				principal.addItem(user);
				principal.addItem(operator);
				principal.setSelectedItem(actingAsOperator ? operator : user);
			}
			principal.setEnabled(canSwitch);
		} finally {
			refreshing = false;
		}
		String actingDid = actingAsOperator ? venueDid : userDid;
		userDidV.setText(actingDid != null ? actingDid : "—");
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

		principal.setToolTipText("Who Brightside acts as: you (everyday), or the venue operator — the venue's own "
			+ "agents, Odin among them, and the venue's Inbox");
		principal.setEnabled(false);
		principal.addActionListener(e -> {
			Principal chosen = (Principal) principal.getSelectedItem();
			if (!refreshing && chosen != null) host.actAs(chosen.operator());
		});
		JTextArea switchNote = SettingsUI.selectable("As the venue operator you see and talk to the venue's own agents "
			+ "— Odin, who administers this Brightside — and answer the venue's Inbox. Everything else is as yourself; "
			+ "Brightside starts as you on every launch.");

		addField("Switch user", principal);
		addSpan(switchNote, "gapbottom 14");
		addDescription("Your name is what Brightside and other people call you. Your Covia user DID is the stable "
			+ "identity peers use; changing your name does not change it. Your home venue controls that identity with "
			+ "the Ed25519 signing key shown here.");
		addField("Your name", nameField);
		addFieldTop("Covia DID", copyableRow(userDidIcon, userDidV, "Covia DID"));
		addFieldTop("Home venue DID", copyableRow(venueDidIcon, venueDidV, "home venue DID"));
		addFieldTop("Venue signing key", copyableRow(publicKeyIcon, pubV, "Ed25519 venue signing public key"));
		addFieldTop("Primary seed (Advanced)", keyRow);
		addSpan(warn, "gaptop 4");
	}

	/** The principal's own suffix — {@code :u:mike} of {@code did:key:z6Mk…:u:mike} — or the whole DID if it has none. */
	private static String suffix(String did) {
		int at = did.indexOf(":u:");
		return (at >= 0) ? did.substring(at) : did;
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
		primary.setEnabled(!actingAsOperator && !n.equals(baselineName) && !n.isEmpty());
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
		actingAsOperator = false;
		baselineName = "";
		nameField.setText("");
		nameField.setEditable(true);
		refreshing = true;
		try {
			principal.removeAllItems();
			principal.setEnabled(false);
		} finally {
			refreshing = false;
		}
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
