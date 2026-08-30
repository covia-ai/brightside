package brightside.ui.settings;

import java.awt.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import brightside.Discord;

/**
 * The <b>Integrations</b> settings page — for now, Discord: the assistant as a
 * bot the owner can message from anywhere. The token is stored encrypted and
 * the bot is created live; the allow-list says who may talk to it.
 */
@SuppressWarnings("serial")
public final class IntegrationsPanel extends SettingsPage {

	/** What Save and Remove do; the app reports back through {@link #showStatus}. */
	public interface Host {
		/** {@code token} is null when left blank (keep the stored one). */
		void saveDiscord(String token, List<String> allow);

		void removeDiscord();
	}

	private final Host host;
	private final JTextArea status = SettingsUI.selectable("—");
	private final JPasswordField tokenField = new JPasswordField(24);
	private final JTextField allowField = new JTextField(24);
	private final JButton remove = new JButton("Remove bot");
	private List<String> baselineAllow = List.of();
	private boolean hasBot;
	private boolean busy;

	public IntegrationsPanel(Host host) {
		super("Save");
		this.host = host;
		build();
	}

	private void build() {
		tokenField.setFont(SettingsUI.technicalFont(tokenField.getFont()));
		tokenField.putClientProperty("JTextField.placeholderText", "Paste the bot token (leave blank to keep the stored one)");
		tokenField.setToolTipText("From the Discord Developer Portal → your application → Bot → Reset Token. Stored encrypted.");
		tokenField.getDocument().addDocumentListener((SimpleDoc) e -> updateDirty());

		allowField.putClientProperty("JTextField.placeholderText", "Discord user ids or usernames, comma-separated");
		allowField.setToolTipText("Only these Discord users may talk to your assistant. DM the bot and it tells you your id.");
		allowField.getDocument().addDocumentListener((SimpleDoc) e -> updateDirty());

		remove.putClientProperty("FlatLaf.styleClass", "small");
		remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		remove.setToolTipText("Disconnect and delete the bot and its Discord conversations");
		remove.setEnabled(false);
		remove.addActionListener(e -> {
			setBusy(true);
			setNote("Removing…", false);
			host.removeDiscord();
		});

		primary.setEnabled(false);
		primary.setToolTipText("Store the token and connect the bot");
		onPrimary(this::onSave);

		JTextArea steps = SettingsUI.description(
			"1. In the Discord Developer Portal, create an application and add a Bot to it.\n"
			+ "2. Under Bot, enable the Message Content Intent, then Reset Token and copy it.\n"
			+ "3. Under OAuth2 → URL Generator, tick the bot scope with View Channels, Read Message History and "
			+ "Send Messages, open the generated link and add the bot to your server — or just message it directly.\n"
			+ "4. Paste the token here, list who may talk to it, and save.");

		addDescription("Your assistant on Discord. Message it directly from your phone or any server it is in; in a "
			+ "server it answers when mentioned. Only the Discord users you list can talk to it — anyone else who "
			+ "messages it is told their user id, which is what you paste below.");
		addField("Status", status);
		addField("Bot token", tokenField);
		addField("Allowed users", allowField);
		addSpanLeft(remove);
		addSpan(steps, "gaptop 14");
	}

	/** Reflect the bot (null when none) and an optional one-line note; re-baselines the form. */
	public void showStatus(Discord.Bot bot, String note) {
		busy = false;
		hasBot = bot != null;
		baselineAllow = hasBot ? bot.allows() : List.of();
		allowField.setText(String.join(", ", baselineAllow));
		tokenField.setText("");
		status.setText(describe(bot));
		status.setForeground(bot != null && bot.error() != null ? SettingsPage.ERROR : SettingsUI.muted());
		remove.setEnabled(hasBot);
		if (note != null) setNote(note, note.startsWith("Couldn't") || note.startsWith("Sorry"));
		else clearNote();
		updateDirty();
	}

	/** Nothing to show yet (no venue or no user). */
	public void clearSensitive() {
		showStatus(null, null);
		status.setText("—");
	}

	private static String describe(Discord.Bot bot) {
		if (bot == null) return "Not set up.";
		if (bot.running()) {
			String who = (bot.username() != null) ? "Connected as @" + bot.username() : "Connected";
			return who + "  ·  " + bot.received() + " received, " + bot.sent() + " sent";
		}
		String why = (bot.error() != null) ? " — " + bot.error() : "";
		return switch (bot.state()) {
			case "PENDING" -> "Not connected yet; retrying" + why;
			case "STARTING" -> "Connecting…";
			case "STOPPED" -> "Stopped" + why;
			default -> bot.state() + why;
		};
	}

	private void updateDirty() {
		if (busy) return;
		boolean tokenTyped = tokenField.getPassword().length > 0;
		boolean allowChanged = !Objects.equals(allowList(), baselineAllow);
		boolean canSave = tokenTyped || (hasBot && allowChanged);
		primary.setEnabled(canSave);
	}

	private List<String> allowList() {
		List<String> out = new ArrayList<>();
		for (String s : allowField.getText().split("[,;\\s]+")) {
			if (!s.isBlank()) out.add(s.strip());
		}
		return out;
	}

	private void onSave() {
		char[] key = tokenField.getPassword();
		String token = (key.length > 0) ? new String(key) : null;
		java.util.Arrays.fill(key, '\0');
		setBusy(true);
		setNote(token != null ? "Storing the token and connecting…" : "Saving…", false);
		host.saveDiscord(token, allowList());
	}

	private void setBusy(boolean b) {
		busy = b;
		primary.setEnabled(false);
		remove.setEnabled(!b && hasBot);
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
