package brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.Insets;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The <b>Integrations</b> settings page: one tab per way the assistant reaches
 * beyond this computer — <b>Discord</b> (the assistant as a bot the owner
 * messages) and <b>Moltbook</b> (the assistant's account on the social network
 * for AI agents). Each tab is its own {@link SettingsPage} with its own action.
 */
@SuppressWarnings("serial")
public final class IntegrationsPanel extends JPanel {

	private final DiscordPanel discord;
	private final MoltbookPanel moltbook;

	public IntegrationsPanel(DiscordPanel.Host discordHost, MoltbookPanel.Host moltbookHost) {
		super(new BorderLayout());
		discord = new DiscordPanel(discordHost);
		moltbook = new MoltbookPanel(moltbookHost);
		JTabbedPane tabs = new JTabbedPane();
		tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_AREA_INSETS, new Insets(8, 20, 0, 20));
		tabs.addTab("Discord", discord);
		tabs.addTab("Moltbook", moltbook);
		add(tabs, BorderLayout.CENTER);
	}

	public DiscordPanel discord() {
		return discord;
	}

	public MoltbookPanel moltbook() {
		return moltbook;
	}

	/** Nothing to show on either tab (no venue or no user). */
	public void clearSensitive() {
		discord.clearSensitive();
		moltbook.clearSensitive();
	}
}
