package brightside.ui.settings;

import java.awt.BorderLayout;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Documents;
import brightside.ui.components.ElidedText;
import brightside.ui.components.HintLabel;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.Theme;

/**
 * The <b>Identity</b> settings page: first, the user switcher — which principal
 * the app acts as (the named user, or the venue operator — advanced); then the
 * owner's editable name, stable Covia user DID, home venue identity and signing
 * key, plus the primary Ed25519 seed hidden behind passphrase re-authentication
 * and a privacy (eye) toggle. Each row explains itself through the ⓘ beside its
 * label; the values are one line each, elided in the middle, with a copy button
 * (or a right-click) taking the whole.
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
	private static final String NONE = "—";
	private static final int EYE = 18;
	private static final int COPY = 16;

	private static final String SWITCH_HINT = "Who Brightside acts as: you, for everyday use, or the venue operator "
		+ "(advanced) — the venue's own agents, Odin among them, and the venue's Inbox. Everything else is as yourself; "
		+ "Brightside starts as you on every launch.";
	private static final String NAME_HINT = "What Brightside and other people call you. Changing it does not change "
		+ "your identity.";
	private static final String OPERATOR_NAME_HINT = "What Brightside calls the venue operator — a label only; the "
		+ "operator is the venue itself.";
	private static final String USER_DID_HINT = "Your Covia user DID: the stable identity peers use for you. Your home "
		+ "venue controls it; changing your name does not change it.";
	private static final String OPERATOR_DID_HINT = "The venue's own DID — the identity the operator acts under.";
	private static final String VENUE_DID_HINT = "Your home venue's DID: the venue that controls your identity.";
	private static final String KEY_HINT = "The Ed25519 public key your home venue signs with.";
	private static final String SEED_HINT = "The primary seed is the master secret for your venue identity — anyone "
		+ "who has it can act as you and decrypt a copied vault. Reveal it only for recovery or advanced tools, and "
		+ "never share it. Your recovery phrase reproduces the same seed.";

	private final Host host;
	private final JTextField nameField = new JTextField(18);
	private final ElidedText userDidV = ElidedText.mono(NONE).copyable();
	private final ElidedText venueDidV = ElidedText.mono(NONE).copyable();
	private final ElidedText pubV = ElidedText.mono(NONE).copyable();
	private final ElidedText seedV = ElidedText.mono(MASK).tooltip(false);
	private final ConvexIdenticon userDidIcon = new ConvexIdenticon();
	private final ConvexIdenticon venueDidIcon = new ConvexIdenticon();
	private final ConvexIdenticon publicKeyIcon = new ConvexIdenticon();
	private final ConvexIdenticon seedIcon = new ConvexIdenticon();
	private final JButton reveal = Buttons.icon(eye(false), "Show / hide the primary Ed25519 seed");
	private final JButton copySeed = copyButton("the primary seed");
	/** Switch user: the named user (everyday) or the venue operator (advanced). */
	private final JComboBox<Principal> principal = new JComboBox<>();
	private HintLabel nameLabel;
	private HintLabel userDidLabel;
	/** True while {@link #refresh} sets the selection, so the host is only told about a person's choice. */
	private boolean refreshing;
	/** Whether the rows show the venue operator rather than the named user; the name field then edits the operator's label. */
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
	 * app is acting as — the named user, or the venue operator (its display
	 * name, editable like the user's, and the venue DID). Selecting a principal here
	 * fires no host call.
	 */
	public void refresh(String name, String userDid, String venueDid, String publicKeyHex,
			boolean seedAvailable, boolean actingAsOperator, String operatorName) {
		identityVersion++;
		this.actingAsOperator = actingAsOperator;
		String operatorLabel = (operatorName != null && !operatorName.isBlank()) ? operatorName : "Operator";
		baselineName = actingAsOperator ? operatorLabel : ((name != null) ? name : "");
		nameField.setText(baselineName);
		nameLabel.setText(actingAsOperator ? "Operator name" : "Your name");
		nameLabel.setHint(actingAsOperator ? OPERATOR_NAME_HINT : NAME_HINT);
		userDidLabel.setText(actingAsOperator ? "Operator DID" : "Covia DID");
		userDidLabel.setHint(actingAsOperator ? OPERATOR_DID_HINT : USER_DID_HINT);
		refreshing = true;
		try {
			principal.removeAllItems();
			boolean canSwitch = name != null && userDid != null && venueDid != null;
			if (canSwitch) {
				Principal user = new Principal(name + " (" + suffix(userDid) + ")", userDid, false);
				Principal operator = new Principal(operatorLabel + " (venue operator)", venueDid, true);
				principal.addItem(user);
				principal.addItem(operator);
				principal.setSelectedItem(actingAsOperator ? operator : user);
			}
			principal.setEnabled(canSwitch);
		} finally {
			refreshing = false;
		}
		String actingDid = actingAsOperator ? venueDid : userDid;
		userDidV.setText(actingDid != null ? actingDid : NONE);
		venueDidV.setText(venueDid != null ? venueDid : NONE);
		pubV.setText(publicKeyHex != null ? publicKeyHex : NONE);
		userDidIcon.setPublicKeyHex(publicKeyHex);
		venueDidIcon.setPublicKeyHex(publicKeyHex);
		publicKeyIcon.setPublicKeyHex(publicKeyHex);
		seedIcon.setPublicKeyHex(publicKeyHex);
		this.seedAvailable = seedAvailable;
		this.seedHex = null;
		revealed = false;
		showEye(false);
		seedV.setText(seedAvailable ? MASK : "unavailable");
		reveal.setEnabled(seedAvailable);
		copySeed.setEnabled(false);
		clearNote();
		updateDirty();
		repaint();
	}

	private void build() {
		Documents.onChange(nameField, this::updateDirty);
		primary.setEnabled(false);
		primary.setToolTipText("Save your name");
		onPrimary(() -> {
			String n = nameField.getText().trim();
			if (!n.isEmpty()) host.saveName(n);
		});

		reveal.addActionListener(e -> toggle());
		copySeed.setEnabled(false);
		copySeed.addActionListener(e -> Clipboard.copy(seedHex));

		JPanel seedActions = Panels.row();
		seedActions.add(reveal);
		seedActions.add(Box.createHorizontalStrut(2));
		seedActions.add(copySeed);

		principal.setToolTipText("Who Brightside acts as");
		principal.setEnabled(false);
		principal.addActionListener(e -> {
			Principal chosen = (Principal) principal.getSelectedItem();
			if (!refreshing && chosen != null) host.actAs(chosen.operator());
		});

		addField("Switch user", SWITCH_HINT, principal);
		nameLabel = addField("Your name", NAME_HINT, nameField);
		userDidLabel = addValueRow("Covia DID", USER_DID_HINT, valueRow(userDidIcon, userDidV, copyButton("the Covia DID", userDidV)));
		addValueRow("Home venue DID", VENUE_DID_HINT, valueRow(venueDidIcon, venueDidV, copyButton("the home venue DID", venueDidV)));
		addValueRow("Venue signing key", KEY_HINT, valueRow(publicKeyIcon, pubV, copyButton("the venue signing key", pubV)));
		addValueRow("Primary seed (Advanced)", SEED_HINT, valueRow(seedIcon, seedV, seedActions));
	}

	/** The principal's own suffix — {@code :u:mike} of {@code did:key:z6Mk…:u:mike} — or the whole DID if it has none. */
	private static String suffix(String did) {
		int at = did.indexOf(":u:");
		return (at >= 0) ? did.substring(at) : did;
	}

	/** An identicon, the value taking the width between, and the row's actions at the end. */
	private static JPanel valueRow(ConvexIdenticon identicon, ElidedText value, JPanel actions) {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.add(identicon, BorderLayout.WEST);
		row.add(value, BorderLayout.CENTER);
		row.add(actions, BorderLayout.EAST);
		return row;
	}

	private static JPanel valueRow(ConvexIdenticon identicon, ElidedText value, JButton copy) {
		JPanel actions = Panels.row();
		actions.add(copy);
		return valueRow(identicon, value, actions);
	}

	/** A small copy icon that takes the whole value, unless there is none. */
	private static JButton copyButton(String subject, ElidedText value) {
		JButton copy = copyButton(subject);
		copy.addActionListener(e -> {
			String text = value.getText();
			if (text != null && !text.isBlank() && !NONE.equals(text)) Clipboard.copy(text);
		});
		return copy;
	}

	private static JButton copyButton(String subject) {
		return Buttons.icon(Lucide.icon("copy", COPY, Theme::muted), "Copy " + subject + " to the clipboard");
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
		if (!Dialogs.confirmDanger(this, "Enter your Brightside passphrase to reveal the primary seed", passphrase)) return;
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
					showEye(true);
					seedV.setText(seedHex);
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
		showEye(false);
		seedV.setText(seedAvailable ? MASK : "unavailable");
		copySeed.setEnabled(false);
		repaint();
	}

	/** Drops any displayed or cached private material when the user logs out. */
	public void clearSensitive() {
		identityVersion++;
		actingAsOperator = false;
		baselineName = "";
		nameField.setText("");
		refreshing = true;
		try {
			principal.removeAllItems();
			principal.setEnabled(false);
		} finally {
			refreshing = false;
		}
		seedAvailable = false;
		hidePrimarySeed();
		userDidV.setText(NONE);
		venueDidV.setText(NONE);
		pubV.setText(NONE);
		userDidIcon.setPublicKeyHex(null);
		venueDidIcon.setPublicKeyHex(null);
		publicKeyIcon.setPublicKeyHex(null);
		seedIcon.setPublicKeyHex(null);
		clearNote();
		updateDirty();
	}

	/** The eye is open while the seed is revealed and struck through while it is hidden. */
	private void showEye(boolean open) {
		reveal.setIcon(eye(open));
	}

	private static javax.swing.Icon eye(boolean open) {
		return Lucide.icon(open ? "eye" : "eye-off", EYE, Theme::muted);
	}
}
