package brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Borders;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The <b>Settings</b> screen: a vertical section nav on the left, each section
 * with its icon, in three groups — you and the app (Identity, General, Theme),
 * what the assistant runs on and reaches (Model, Integrations, Files,
 * Schedule), and keys and access (Secrets, Vault, Auth) — selecting the
 * content shown on the right. One consistent place to find
 * application actions and configuration. Identity comes first: who the app
 * is acting as frames everything below it.
 */
@SuppressWarnings("serial")
public final class SettingsScreen extends JPanel {

	public enum Tab {
		// You and the app.
		PROFILE("Identity", "user-round", false), GENERAL("General", "sliders-horizontal", false),
		THEME("Theme", "palette", false),
		// What the assistant runs on and reaches.
		MODEL("Model", "sparkles", true), INTEGRATIONS("Integrations", "plug", false), FILES("Files", "folder", false),
		SCHEDULE("Schedule", "calendar-clock", false),
		// Keys and access.
		SECRETS("Secrets", "key-round", true), VAULT("Vault", "lock", false), AUTH("Auth", "ticket", false);

		final String label;
		/** The {@link Lucide} icon beside the label. */
		final String icon;
		/** Whether a gap sets this section apart from the one above, starting a group. */
		final boolean startsGroup;

		Tab(String label, String icon, boolean startsGroup) {
			this.label = label;
			this.icon = icon;
			this.startsGroup = startsGroup;
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
	private final ThemePanel theme;
	private final ModelPanel model;
	private final ProfilePanel profile;
	private final IntegrationsPanel integrations;
	private final SchedulePanel schedule;
	private final FilesPanel files;
	private final SecretsPanel secrets;
	private final VaultPanel vault;
	private final AuthPanel auth;

	public SettingsScreen(GeneralPanel general, ThemePanel theme, ModelPanel model, ProfilePanel profile,
			IntegrationsPanel integrations, SchedulePanel schedule, SecretsPanel secrets, VaultPanel vault,
			AuthPanel auth, FilesPanel files) {
		super(new BorderLayout());
		this.general = general;
		this.theme = theme;
		this.model = model;
		this.profile = profile;
		this.integrations = integrations;
		this.schedule = schedule;
		this.files = files;
		this.secrets = secrets;
		this.vault = vault;
		this.auth = auth;

		content.setOpaque(false);
		content.add(profile, Tab.PROFILE.name());
		content.add(general, Tab.GENERAL.name());
		content.add(theme, Tab.THEME.name());
		content.add(model, Tab.MODEL.name());
		content.add(integrations, Tab.INTEGRATIONS.name());
		content.add(schedule, Tab.SCHEDULE.name());
		content.add(files, Tab.FILES.name());
		content.add(secrets, Tab.SECRETS.name());
		content.add(vault, Tab.VAULT.name());
		content.add(auth, Tab.AUTH.name());

		// The section nav: one PressButton per section, the same control as the
		// bottom tabs — hover, pressed and selected looks from the theme, acting
		// on the press — its icon in the accent with the label when selected,
		// muted otherwise. The column is as wide as its widest section.
		JPanel column = Panels.column();
		column.setBorder(new FlatEmptyBorder(8, 6, 8, 6));
		for (Tab t : Tab.values()) {
			PressButton b = new PressButton(t.label);
			b.setIcon(Lucide.icon(t.icon, 16, () -> b.isSelected() ? Theme.accent() : Theme.muted()));
			b.setIconTextGap(10);
			b.setHorizontalAlignment(SwingConstants.LEFT);
			b.setMargin(new Insets(8, 12, 8, 16));
			Styles.style(b, "font: +1");
			b.setAlignmentX(LEFT_ALIGNMENT);
			// As wide as the column; the column hugs the top, so each is as tall as it prefers.
			b.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
			b.onPress(() -> select(t));
			nav.put(t, b);
			if (t.startsGroup) column.add(Box.createVerticalStrut(UIScale.scale(14)));
			column.add(b);
			column.add(Box.createVerticalStrut(UIScale.scale(2)));
		}
		JPanel navHolder = Scrolls.hugTop(column);
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

	public ThemePanel theme() {
		return theme;
	}

	public ProfilePanel profile() {
		return profile;
	}

	public IntegrationsPanel integrations() {
		return integrations;
	}

	public SchedulePanel schedule() {
		return schedule;
	}

	public SecretsPanel secrets() {
		return secrets;
	}

	public FilesPanel files() {
		return files;
	}

	public VaultPanel vault() {
		return vault;
	}

	public AuthPanel auth() {
		return auth;
	}
}
