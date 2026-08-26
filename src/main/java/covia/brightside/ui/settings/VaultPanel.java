package covia.brightside.ui.settings;

import javax.swing.JCheckBox;
import javax.swing.JLabel;

/**
 * The <b>Vault</b> settings page: how Brightside is secured at rest. Shows the
 * encryption summary and whether a passphrase is remembered on this computer;
 * unticking that and pressing <b>Save</b> forgets it. (Re-enable it from the unlock
 * screen's "Remember me".)
 */
@SuppressWarnings("serial")
public final class VaultPanel extends SettingsPage {

	/** Actions the vault page offers. */
	public interface Host {
		void forgetRemembered();
	}

	private final Host host;
	private final JCheckBox remember = new JCheckBox("Remember my passphrase on this computer");
	private final JLabel hint = SettingsUI.note();
	private boolean baseline;

	public VaultPanel(Host host) {
		super("Save");
		this.host = host;
		build();
	}

	/** Refresh with whether a passphrase is currently remembered on this computer. */
	public void refresh(boolean isRemembered) {
		baseline = isRemembered;
		remember.setSelected(isRemembered);
		remember.setEnabled(isRemembered); // can only be turned OFF here
		hint.setText(isRemembered ? " " : "To remember it, tick \"Remember me\" on the unlock screen.");
		clearNote();
		primary.setEnabled(false);
	}

	private void build() {
		remember.setOpaque(false);
		remember.setToolTipText("Whether your passphrase is stored (encrypted) on this computer so you aren't asked each launch");
		remember.addActionListener(e -> primary.setEnabled(remember.isSelected() != baseline));

		primary.setEnabled(false);
		primary.setToolTipText("Apply the change");
		onPrimary(this::onSave);

		addDescription("Everything Brightside stores on this computer is encrypted. Your passphrase (hardened with "
			+ "Argon2id) protects your identity key; the store is encrypted with a key derived from that identity, so "
			+ "your recovery phrase can reopen a retained store. Provider API keys must be entered again after recovery. "
			+ "Keep your recovery phrase safe.");
		addSpan(remember);
		addSpan(hint);
	}

	private void onSave() {
		if (baseline && !remember.isSelected()) {
			host.forgetRemembered();
			baseline = false;
			remember.setEnabled(false);
			setNote("Forgotten. Your passphrase is no longer stored here.", false);
		}
		primary.setEnabled(false);
	}
}
