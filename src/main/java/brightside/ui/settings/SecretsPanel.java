package brightside.ui.settings;

import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Secrets</b> settings page: the acting user's encrypted secret store on
 * the venue — the values operations resolve as {@code s/<name>} (the Moltbook
 * and Discord keys live here). Names are listed openly; a value is revealed
 * only behind passphrase re-authentication — the same gate as the primary seed
 * — and only for that sitting. Saving under an existing name replaces it.
 */
@SuppressWarnings("serial")
public final class SecretsPanel extends SettingsPage {

	/** What the page can do; reveal re-authenticates and throws when it cannot. */
	public interface Host {
		List<String> listSecrets();

		boolean storeSecret(String name, String value);

		boolean deleteSecret(String name);

		String revealSecret(String name, char[] passphrase) throws Exception;
	}

	private static final String MASK = "••••••••••••";

	private final Host host;
	private final JPanel rows = new JPanel(new MigLayout(
		"insets 0, fillx, wrap 5, gapy 2", "[grow 30,fill]12[grow 70,fill]8[]2[]6[]", ""));
	private final JTextField nameField = new JTextField(16);
	private final JPasswordField valueField = new JPasswordField(24);
	/** Bumped on every rebuild so a slow reveal never lands on replaced rows. */
	private long version;

	public SecretsPanel(Host host) {
		super("Save secret");
		this.host = host;
		build();
	}

	private void build() {
		addDescription("Your encrypted secrets on the venue. Operations resolve them as s/<name> — the Moltbook "
			+ "and Discord keys live here — and your assistant's tools use them without ever seeing a value. "
			+ "Revealing one asks for your Brightside passphrase, like the primary seed.");
		rows.setOpaque(false);
		addSpan(rows, "gapbottom 14");
		addField("Name",
			"Letters, digits, dots, dashes and underscores — how operations reference it (s/<name>)", nameField);
		// The action belongs beside the fields it acts on: adding the primary
		// button here re-parents it out of the bottom action bar, which then
		// carries only the status note.
		JPanel saveRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]", ""));
		saveRow.setOpaque(false);
		saveRow.add(valueField, "growx");
		saveRow.add(primary);
		addField("Value", "Stored encrypted; saving under an existing name replaces its value", saveRow);
		primary.setToolTipText("Encrypt and store the secret under this name");
		onPrimary(this::onSave);
	}

	/** Re-reads the list; anything revealed goes back behind the mask. */
	public void refresh(List<String> names) {
		version++;
		rows.removeAll();
		if (names.isEmpty()) {
			rows.add(Labels.small("No secrets stored yet.", Styles.MUTED), "span 5");
		} else {
			for (String name : names) addRow(name);
		}
		rows.revalidate();
		rows.repaint();
	}

	/** Drops everything shown or typed when the user logs out or switches. */
	public void clearSensitive() {
		version++;
		rows.removeAll();
		rows.revalidate();
		rows.repaint();
		nameField.setText("");
		valueField.setText("");
		clearNote();
	}

	private void addRow(String name) {
		JLabel label = Styles.classes(new JLabel(name), Styles.MONOSPACED);
		SelectableText value = new SelectableText(MASK).mono().muted();
		JButton eye = Buttons.icon(Lucide.icon("eye", 16, Theme::muted),
			"Reveal the value (asks for your passphrase)");
		JButton copy = Buttons.icon(Lucide.icon("copy", 16, Theme::muted),
			"Copy the value (asks for your passphrase while it is hidden)");
		JButton forget = Buttons.small("Forget");
		forget.setToolTipText("Remove this secret from the store");
		final String[] revealed = { null };

		eye.addActionListener(e -> {
			if (revealed[0] != null) {
				revealed[0] = null;
				value.setText(MASK);
				eye.setIcon(Lucide.icon("eye", 16, Theme::muted));
				return;
			}
			fetchGated(name, "reveal", eye, secret -> {
				revealed[0] = secret;
				value.setText(secret);
				eye.setIcon(Lucide.icon("eye-off", 16, Theme::muted));
				setNote("Revealed for this sitting only.", false);
			});
		});
		copy.addActionListener(e -> {
			if (revealed[0] != null) {
				Clipboard.copy(revealed[0]);
				setNote("Copied to the clipboard.", false);
				return;
			}
			// Copy without showing: the same gate, but the value goes straight
			// to the clipboard and stays masked on screen.
			fetchGated(name, "copy", copy, secret -> {
				Clipboard.copy(secret);
				setNote("Copied to the clipboard — without revealing it.", false);
			});
		});
		forget.addActionListener(e -> {
			if (!Dialogs.confirmDanger(this, "Forget secret",
				"Forget \"" + name + "\"? Anything resolving s/" + name + " will stop working.")) return;
			boolean done = host.deleteSecret(name);
			refresh(host.listSecrets());
			setNote(done ? "Forgot " + name + "." : "Couldn't forget " + name + ".", !done);
		});

		rows.add(label, "wmin 0");
		rows.add(value, "growx, wmin 0");
		rows.add(eye);
		rows.add(copy);
		rows.add(forget);
	}

	/**
	 * The passphrase gate shared by reveal and copy: prompts, re-authenticates
	 * and decrypts off the event thread, and hands the value to {@code use} —
	 * which decides whether it is shown or only copied. {@code trigger} is
	 * disabled while the check runs.
	 */
	private void fetchGated(String name, String verb, JButton trigger, java.util.function.Consumer<String> use) {
		JPasswordField pass = new JPasswordField(24);
		if (!Dialogs.confirmDanger(this,
			"Enter your Brightside passphrase to " + verb + " \"" + name + "\"", pass)) return;
		char[] entered = pass.getPassword();
		pass.setText("");
		if (entered.length == 0) return;
		long v = version;
		trigger.setEnabled(false);
		setNote("Checking your passphrase…", false);
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return host.revealSecret(name, entered);
			}

			@Override
			protected void done() {
				java.util.Arrays.fill(entered, '\0');
				trigger.setEnabled(true);
				if (version != v) return; // the list was rebuilt meanwhile
				try {
					use.accept(get());
				} catch (Exception ex) {
					setNote("That passphrase didn't unlock this identity.", true);
				}
			}
		}.execute();
	}

	private void onSave() {
		String name = nameField.getText().trim();
		char[] typed = valueField.getPassword();
		String value = new String(typed).trim();
		java.util.Arrays.fill(typed, '\0');
		if (name.isEmpty() || value.isEmpty()) {
			setNote("A name and a value are both needed.", true);
			return;
		}
		boolean stored = host.storeSecret(name, value);
		if (stored) {
			nameField.setText("");
			valueField.setText("");
			refresh(host.listSecrets());
			setNote("Stored " + name + ".", false);
		} else {
			setNote("Couldn't store it — names use letters, digits, dots, dashes and underscores.", true);
		}
	}
}
