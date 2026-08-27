package covia.brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;

import java.awt.CardLayout;

/**
 * The <b>Settings</b> screen: a vertical section nav on the left (General,
 * Model, Identity, Vault, Auth) selecting the content shown on the right. One
 * consistent place to find application actions and configuration.
 */
@SuppressWarnings("serial")
public final class SettingsScreen extends JPanel {

	public enum Tab {
		GENERAL("General"), MODEL("Model"), PROFILE("Identity"), VAULT("Vault"), AUTH("Auth");

		final String label;

		Tab(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final JList<Tab> nav = new JList<>(Tab.values());
	private final GeneralPanel general;
	private final ModelPanel model;
	private final ProfilePanel profile;
	private final VaultPanel vault;
	private final AuthPanel auth;

	public SettingsScreen(GeneralPanel general, ModelPanel model, ProfilePanel profile, VaultPanel vault, AuthPanel auth) {
		super(new BorderLayout());
		this.general = general;
		this.model = model;
		this.profile = profile;
		this.vault = vault;
		this.auth = auth;

		content.setOpaque(false);
		content.add(general, Tab.GENERAL.name());
		content.add(model, Tab.MODEL.name());
		content.add(profile, Tab.PROFILE.name());
		content.add(vault, Tab.VAULT.name());
		content.add(auth, Tab.AUTH.name());

		nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		nav.setCellRenderer(navRenderer());
		nav.setFixedCellHeight(38);
		nav.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
		nav.setSelectedIndex(0);
		nav.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && nav.getSelectedValue() != null) {
				cards.show(content, nav.getSelectedValue().name());
			}
		});

		JScrollPane navScroll = new JScrollPane(nav,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		navScroll.setPreferredSize(new Dimension(150, 0));
		navScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, sep()));

		add(navScroll, BorderLayout.WEST);
		add(content, BorderLayout.CENTER);
	}

	/** Select a section (also shows its content). */
	public void select(Tab t) {
		nav.setSelectedValue(t, true);
	}

	public ModelPanel model() {
		return model;
	}

	public GeneralPanel general() {
		return general;
	}

	public ProfilePanel profile() {
		return profile;
	}

	public VaultPanel vault() {
		return vault;
	}

	public AuthPanel auth() {
		return auth;
	}

	private static ListCellRenderer<? super Tab> navRenderer() {
		DefaultListCellRenderer base = new DefaultListCellRenderer();
		return (list, value, index, selected, focus) -> {
			JLabel l = (JLabel) base.getListCellRendererComponent(list, value, index, selected, focus);
			l.setText(value.label);
			l.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
			l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() + 1f));
			return l;
		};
	}

	private static Color sep() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}
}
