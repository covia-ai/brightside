package brightside.ui.settings;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;

import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;

/**
 * The <b>Auth</b> settings page: mint a venue-signed access-token JWT that
 * authenticates as the current named user against the local venue API, with a
 * chosen expiry. Its single primary action is <b>Generate</b> (there is nothing
 * to save); the token is shown once (selectable) for copying and is never written
 * to disk. Separately, the owner may opt in to a token for local tools that
 * Brightside keeps fresh in a file ({@link brightside.ToolToken}).
 */
@SuppressWarnings("serial")
public final class AuthPanel extends SettingsPage {

	/** Mints a token for the selected subject, or returns null if unavailable. */
	public interface Minter {
		String mint(long seconds, boolean asOperator);
	}

	private record Exp(String label, long seconds) {
		@Override
		public String toString() {
			return label;
		}
	}

	private record Subject(String label, boolean operator) {
		@Override
		public String toString() {
			return label;
		}
	}

	private final Minter minter;
	private final JComboBox<Subject> subject = new JComboBox<>();
	private final JComboBox<Exp> expiry = new JComboBox<>();
	private final TextArea token = new TextArea(4, 34).placeholder("Generated token appears here");
	private final JButton copy = Buttons.small("Copy");
	private final JCheckBox keepToolToken = new JCheckBox("Keep a token for local tools");

	public AuthPanel(Minter minter, BooleanSupplier keepingToolToken, Consumer<Boolean> keepToolToken) {
		super("Generate");
		this.minter = minter;
		build();
		this.keepToolToken.setSelected(keepingToolToken.getAsBoolean());
		this.keepToolToken.addActionListener(e -> keepToolToken.accept(this.keepToolToken.isSelected()));
	}

	private void build() {
		subject.setModel(new DefaultComboBoxModel<>(new Subject[] {
			new Subject("User (recommended)", false),
			new Subject("Venue operator (advanced)", true),
		}));
		subject.setSelectedIndex(0);
		subject.setToolTipText("The identity and authority represented by the token");

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
		Styles.classes(token, Styles.MONOSPACED);
		token.setToolTipText("The bearer token — select and copy it");
		JScrollPane tokenScroll = new JScrollPane(token);

		copy.setEnabled(false);
		copy.setToolTipText("Copy the token to the clipboard");
		copy.addActionListener(e -> onCopy());

		primary.setToolTipText("Mint a new access token with the chosen expiry");
		onPrimary(this::onGenerate);

		group(null);
		addField("Authenticate as", "A bearer token for this venue's local API (Authorization: Bearer …). The user "
			+ "reaches your own Brightside data; the venue operator is for administration only.", subject);
		addField("Expires in", expiry);
		addSpan(tokenScroll, "h 96!, gaptop 6");
		addSpanLeft(copy);

		keepToolToken.setOpaque(false);
		keepToolToken.setToolTipText("Renewed every hour while Brightside runs, and removed when it quits");
		group(null);
		addField("Local tools", "While Brightside runs, keeps a user token valid for a day in tool-token.json in "
			+ "your data folder, for tools on this computer such as Claude Code to read when they connect. Like a "
			+ "remembered passphrase, anyone who can read your files can use it.", keepToolToken);
	}

	private void onGenerate() {
		Exp sel = (Exp) expiry.getSelectedItem();
		Subject who = (Subject) subject.getSelectedItem();
		long secs = (sel != null) ? sel.seconds() : 3600L;
		boolean asOperator = who != null && who.operator();
		String jwt = minter.mint(secs, asOperator);
		if (jwt == null) {
			token.setText("");
			copy.setEnabled(false);
			setNote("Couldn't mint a token — the venue isn't ready.", true);
			return;
		}
		token.setText(jwt);
		token.setCaretPosition(0);
		copy.setEnabled(true);
		setNote("Valid for " + sel.label() + (asOperator ? " with venue-operator authority" : " as the user")
			+ ". Treat it like a password; it isn't stored.", asOperator);
	}

	private void onCopy() {
		String t = token.getText();
		if (t == null || t.isEmpty()) return;
		Clipboard.copy(t, copy);
	}

	/** Drops the displayed bearer token when the current user logs out. */
	public void clearSensitive() {
		token.setText("");
		copy.setEnabled(false);
		clearNote();
	}
}
