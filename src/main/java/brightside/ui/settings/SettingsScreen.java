package brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import brightside.ui.components.Borders;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;

/**
 * The <b>Settings</b> screen: a vertical section nav on the left (Identity,
 * General, Model, Integrations, Vault, Auth) selecting the content shown on the right. One
 * consistent place to find application actions and configuration. Identity
 * comes first: who the app is acting as frames everything below it.
 */
@SuppressWarnings("serial")
public final class SettingsScreen extends JPanel {

	public enum Tab {
		PROFILE("Identity"), GENERAL("General"), MODEL("Model"), INTEGRATIONS("Integrations"), VAULT("Vault"), AUTH("Auth");

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
	private final Map<Tab, PressButton> nav = new EnumMap<>(Tab.class);
	private final GeneralPanel general;
	private final ModelPanel model;
	private final ProfilePanel profile;
	private final IntegrationsPanel integrations;
	private final VaultPanel vault;
	private final AuthPanel auth;

	public SettingsScreen(GeneralPanel general, ModelPanel model, ProfilePanel profile,
			IntegrationsPanel integrations, VaultPanel vault, AuthPanel auth) {
		super(new BorderLayout());
		this.general = general;
		this.model = model;
		this.profile = profile;
		this.integrations = integrations;
		this.vault = vault;
		this.auth = auth;

		content.setOpaque(false);
		content.add(profile, Tab.PROFILE.name());
		content.add(general, Tab.GENERAL.name());
		content.add(model, Tab.MODEL.name());
		content.add(integrations, Tab.INTEGRATIONS.name());
		content.add(vault, Tab.VAULT.name());
		content.add(auth, Tab.AUTH.name());

		// The section nav: one PressButton per section, the same control as the
		// bottom tabs — hover, pressed and selected looks from the theme, acting
		// on the press.
		JPanel column = Panels.column();
		column.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
		for (Tab t : Tab.values()) {
			PressButton b = new PressButton(t.label);
			b.setHorizontalAlignment(SwingConstants.LEFT);
			b.setMargin(new Insets(8, 12, 8, 12));
			Styles.style(b, "font: +1");
			b.setAlignmentX(LEFT_ALIGNMENT);
			b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
			b.setPreferredSize(new Dimension(138, 38));
			b.onPress(() -> select(t));
			nav.put(t, b);
			column.add(b);
			column.add(Box.createVerticalStrut(2));
		}
		JPanel navHolder = Scrolls.hugTop(column);
		navHolder.setPreferredSize(new Dimension(150, 0));
		navHolder.setBorder(Borders.hairlineRight());

		add(navHolder, BorderLayout.WEST);
		add(content, BorderLayout.CENTER);
		select(Tab.values()[0]);
	}

	/** Select a section (also shows its content). */
	public void select(Tab t) {
		for (Map.Entry<Tab, PressButton> e : nav.entrySet()) {
			boolean on = e.getKey() == t;
			e.getValue().setSelected(on);
			if (on) Styles.classes(e.getValue(), Styles.ACCENT);
			else Styles.classes(e.getValue());
		}
		cards.show(content, t.name());
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

	public IntegrationsPanel integrations() {
		return integrations;
	}

	public VaultPanel vault() {
		return vault;
	}

	public AuthPanel auth() {
		return auth;
	}
}
