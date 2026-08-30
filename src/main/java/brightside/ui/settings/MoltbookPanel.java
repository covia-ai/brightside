package brightside.ui.settings;

import java.awt.Dimension;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.Moltbook;
import brightside.ui.components.Buttons;
import brightside.ui.components.Documents;
import brightside.ui.components.Links;
import brightside.ui.components.Panels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;

/**
 * The <b>Moltbook</b> integration: the assistant's account on the social
 * network for AI agents. Brightside registers it under a name the owner
 * chooses; the owner then claims it on Moltbook's claim page (an email, then a
 * tweet). An account that already exists is connected with its API key. From
 * then on the assistant takes part through the shipped {@code moltbook} skill.
 *
 * <p>Dumb: it reports what the owner asked for through the {@link Host}; the
 * app talks to Moltbook, keeps the key and answers through {@link #showStatus}.
 */
@SuppressWarnings("serial")
public final class MoltbookPanel extends SettingsPage {

	/** What the page's actions do; the app answers through {@link #showStatus}. */
	public interface Host {
		void registerMoltbook(String name, String description);

		void connectMoltbook(String apiKey);

		void forgetMoltbook();

		void refreshMoltbookStatus();
	}

	private static final String NAME_HINT = "The agent's name on Moltbook — how other agents and their owners will "
		+ "know it. Moltbook keeps names unique.";
	private static final String DESCRIPTION_HINT = "A line about the agent for its Moltbook profile; it can be changed "
		+ "there later.";
	private static final String KEY_HINT = "Already registered elsewhere, or rotated the key on the owner dashboard? "
		+ "Paste the API key to connect that account instead. It is stored encrypted and never shown again.";

	private final Host host;
	private final SelectableText status = new SelectableText("—");
	private final JTextField nameField = new JTextField(24);
	private final TextArea descriptionField = new TextArea(3, 30).placeholder("What this agent does, in a sentence or two");
	private final JPasswordField keyField = new JPasswordField(24);
	private final JButton connect = Buttons.secondary("Connect with key");
	private final JButton claim = Buttons.secondary("Open claim page");
	private final JButton refresh = Buttons.secondary("Refresh");
	private final JButton forget = Buttons.small("Forget");
	private Moltbook.Account account;
	private boolean busy;

	public MoltbookPanel(Host host) {
		super("Register");
		this.host = host;
		build();
	}

	private void build() {
		Documents.onChange(nameField, this::updateButtons);
		Documents.onChange(keyField, this::updateButtons);
		nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. BrightsideForMike");
		descriptionField.setLineWrap(true);
		descriptionField.setWrapStyleWord(true);
		JScrollPane descriptionScroll = new JScrollPane(descriptionField);
		descriptionScroll.setPreferredSize(new Dimension(360, 72));
		Styles.classes(keyField, Styles.MONOSPACED);
		keyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "moltbook_…");

		primary.setToolTipText("Create the agent's Moltbook account under this name");
		onPrimary(this::onRegister);
		connect.setToolTipText("Connect an account that already exists, from its API key");
		connect.addActionListener(e -> onConnect());
		claim.setToolTipText("Finish activating the account as its owner, in your browser");
		claim.addActionListener(e -> {
			if (account != null && account.claimUrl() != null) Links.open(account.claimUrl());
		});
		refresh.setToolTipText("Ask Moltbook for the account's current status");
		refresh.addActionListener(e -> {
			setBusy(true);
			setNote("Checking…", false);
			host.refreshMoltbookStatus();
		});
		forget.setToolTipText("Forget the key and the claim page here; the account itself stays on Moltbook");
		forget.addActionListener(e -> {
			setBusy(true);
			setNote("Forgetting…", false);
			host.forgetMoltbook();
		});

		JPanel actions = Panels.row();
		actions.add(claim);
		actions.add(Box.createHorizontalStrut(8));
		actions.add(refresh);
		actions.add(Box.createHorizontalStrut(8));
		actions.add(forget);

		JPanel connectRow = Panels.row();
		connectRow.add(keyField);
		connectRow.add(Box.createHorizontalStrut(8));
		connectRow.add(connect);

		addDescription("Your assistant on Moltbook, the social network for AI agents: it can check in, read, post, "
			+ "comment, vote and join communities there as your agent, when you ask it to. Register it under a name, "
			+ "then claim it as the owner on Moltbook's claim page.");
		addField("Status", status);
		addSpanLeft(actions);
		addField("Agent name", NAME_HINT, nameField);
		addField("Description", DESCRIPTION_HINT, descriptionScroll);
		addField("Existing key", KEY_HINT, connectRow);
		addSpan(SelectableText.description("Once claimed, ask your assistant to check Moltbook, read its feed or post "
			+ "something — it loads the moltbook skill and keeps the key out of the conversation. The owner dashboard at "
			+ Moltbook.OWNER_LOGIN + " shows the account's activity and can rotate its key.").small(), "gaptop 14");
		showStatus(null, null);
	}

	/** Reflect the account (null when none is set up) and an optional one-line note. */
	public void showStatus(Moltbook.Account account, String note) {
		this.account = account;
		busy = false;
		status.setText(describe(account));
		Styles.classes(status, account != null && account.error() != null ? Styles.ERROR : Styles.MUTED);
		if (account != null && account.name() != null && nameField.getText().isBlank()) nameField.setText(account.name());
		keyField.setText("");
		if (note != null) setNote(note, note.startsWith("Couldn't"));
		else clearNote();
		updateButtons();
	}

	/** Nothing to show (no venue or no user). */
	public void clearSensitive() {
		nameField.setText("");
		descriptionField.setText("");
		showStatus(null, null);
	}

	private static String describe(Moltbook.Account a) {
		if (a == null) return "Not set up.";
		String name = (a.name() != null) ? a.name() : "your agent";
		if (a.error() != null) return "Couldn't reach Moltbook for " + name + " — " + a.error();
		if (a.pending()) return "Registered as " + name + " — waiting for you to claim it.";
		if (a.claimed()) {
			return "Claimed as " + name + "  ·  karma " + a.karma() + "  ·  " + a.followers() + " follower"
				+ (a.followers() == 1 ? "" : "s") + "  ·  " + a.posts() + " post" + (a.posts() == 1 ? "" : "s");
		}
		return "Registered as " + name + ".";
	}

	private void onRegister() {
		String name = nameField.getText().trim();
		if (name.isEmpty()) return;
		setBusy(true);
		setNote("Registering on Moltbook…", false);
		host.registerMoltbook(name, descriptionField.getText().trim());
	}

	private void onConnect() {
		char[] key = keyField.getPassword();
		if (key.length == 0) return;
		String apiKey = new String(key);
		java.util.Arrays.fill(key, '\0');
		setBusy(true);
		setNote("Connecting…", false);
		host.connectMoltbook(apiKey);
	}

	private void updateButtons() {
		boolean configured = account != null;
		primary.setEnabled(!busy && !configured && !nameField.getText().isBlank());
		connect.setEnabled(!busy && keyField.getPassword().length > 0);
		claim.setEnabled(!busy && configured && account.claimUrl() != null);
		refresh.setEnabled(!busy && configured);
		forget.setEnabled(!busy && configured);
		nameField.setEnabled(!busy && !configured);
		descriptionField.setEnabled(!busy && !configured);
	}

	private void setBusy(boolean b) {
		busy = b;
		updateButtons();
	}
}
