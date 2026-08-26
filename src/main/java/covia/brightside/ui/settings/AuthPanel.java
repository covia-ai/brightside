package covia.brightside.ui.settings;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The <b>Auth</b> settings page: mint a venue-signed access-token JWT that
 * authenticates as the venue operator against the local venue API, with a chosen
 * expiry. Its single primary action is <b>Generate</b> (there is nothing to save);
 * the token is shown once (selectable) for copying and is never written to disk.
 */
@SuppressWarnings("serial")
public final class AuthPanel extends SettingsPage {

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
	private final JTextArea token = new JTextArea(4, 34);
	private final JButton copy = new JButton("Copy");

	public AuthPanel(Minter minter) {
		super("Generate");
		this.minter = minter;
		build();
	}

	private void build() {
		expiry.setModel(new DefaultComboBoxModel<>(new Exp[] {
			new Exp("5 minutes", 300L),
			new Exp("1 hour", 3600L),
			new Exp("1 day", 86_400L),
			new Exp("30 days", 2_592_000L),
		}));
		expiry.setSelectedIndex(1);
		expiry.setToolTipText("How long the token stays valid");

		token.setEditable(false);
		token.setLineWrap(true);
		token.putClientProperty("JTextArea.placeholderText", "Generated token appears here");
		token.setToolTipText("The bearer token — select and copy it");
		JScrollPane tokenScroll = new JScrollPane(token);

		copy.putClientProperty("FlatLaf.styleClass", "small");
		copy.setEnabled(false);
		copy.setToolTipText("Copy the token to the clipboard");
		copy.addActionListener(e -> onCopy());

		primary.setToolTipText("Mint a new access token with the chosen expiry");
		onPrimary(this::onGenerate);

		addDescription("A bearer token for this venue's local API (Authorization: Bearer …), authenticating as the "
			+ "venue operator. This is highly privileged: treat it like a password. It is not stored on disk.");
		addField("Expires in", expiry);
		addSpan(tokenScroll, "h 96!, gaptop 6");
		addSpanLeft(copy);
	}

	private void onGenerate() {
		Exp sel = (Exp) expiry.getSelectedItem();
		long secs = (sel != null) ? sel.seconds() : 3600L;
		String jwt = minter.mint(secs);
		if (jwt == null) {
			token.setText("");
			copy.setEnabled(false);
			setNote("Couldn't mint a token — the venue isn't ready.", true);
			return;
		}
		token.setText(jwt);
		token.setCaretPosition(0);
		copy.setEnabled(true);
		setNote("Valid for " + sel.label() + ". Treat it like a password.", false);
	}

	private void onCopy() {
		String t = token.getText();
		if (t == null || t.isEmpty()) return;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null);
		setNote("Copied to the clipboard.", false);
	}

	/** Drops the displayed bearer token when the current user logs out. */
	public void clearSensitive() {
		token.setText("");
		copy.setEnabled(false);
		clearNote();
	}
}
