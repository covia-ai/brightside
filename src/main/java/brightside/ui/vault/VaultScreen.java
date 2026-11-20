package brightside.ui.vault;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.ui.components.ScreenHeader;

/**
 * The Vault: what is private to you on this computer, in one place. Secrets,
 * Access (named tokens and logins for other tools), and Passphrase (whether
 * it is stored here). The pages are the very panels Settings shows in the
 * classic layout, moved here in the sidebar layout, so every control and
 * detail is the same.
 */
@SuppressWarnings("serial")
public final class VaultScreen extends JPanel {

	/** The Vault's pages, in order. */
	public enum Page {
		SECRETS("Secrets"), ACCESS("Access"), PASSPHRASE("Passphrase");

		public final String label;

		Page(String label) {
			this.label = label;
		}
	}

	private final JTabbedPane tabs = new JTabbedPane();

	public VaultScreen() {
		super(new BorderLayout());
		ScreenHeader header = new ScreenHeader("Vault");
		tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
		add(header, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
	}

	/** Takes the three pages' panels (moving them from wherever they were). */
	public void adopt(Component secrets, Component access, Component passphrase) {
		tabs.removeAll();
		tabs.addTab(Page.SECRETS.label, secrets);
		tabs.addTab(Page.ACCESS.label, access);
		tabs.addTab(Page.PASSPHRASE.label, passphrase);
	}

	public void select(Page page) {
		if (page.ordinal() < tabs.getTabCount()) tabs.setSelectedIndex(page.ordinal());
	}

	/** The page on view, for tests. */
	public Page selected() {
		int i = tabs.getSelectedIndex();
		return (i < 0) ? null : Page.values()[i];
	}
}
