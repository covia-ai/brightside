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
import brightside.ui.components.IconTile;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The <b>Settings</b> screen: a vertical section nav on the left, each section
 * with its icon, in three groups — you and the app (Identity, General, Theme),
 * what the assistant runs on and reaches (Model, Integrations, Files,
 * Schedule), and keys and access (Secrets, Vault, Access) — selecting the
 * content shown on the right. One consistent place to find
 * application actions and configuration. Identity comes first: who the app
 * is acting as frames everything below it.
 */
@SuppressWarnings("serial")
public final class SettingsScreen extends JPanel {

	public enum Tab {
		// Who you are, what you run on, who you know, and what you pay with.
		PROFILE("Identity", IconTile.Concept.IDENTITY, false),
		MODEL("Models", IconTile.Concept.MODEL, false),
		PEERS("Peers", IconTile.Concept.CONSTELLATION, false),
		TREASURY("Treasury", IconTile.Concept.TREASURY, false),
		// The app.
		GENERAL("General", "sliders-horizontal", IconTile.Family.NEUTRAL, true),
		THEME("Theme", "palette", IconTile.Family.NEUTRAL, false),
		INTEGRATIONS("Integrations", IconTile.Concept.INTEGRATIONS, false),
		SCHEDULE("Schedule", IconTile.Concept.SCHEDULE, false),
		// On screens of their own in the sidebar layout.
		JOBS("Jobs", IconTile.Concept.JOBS, true),
		// Keys and access.
		SECRETS("Secrets", IconTile.Concept.SECRETS, false), VAULT("Vault", IconTile.Concept.VAULT, false),
		ACCESS("Access", IconTile.Concept.ACCESS, false);

		final String label;
		/** The {@link Lucide} icon in the section's tile. */
		final String icon;
		/** The colour family the tile is tinted from. */
		final IconTile.Family family;
		/** Whether a gap sets this section apart from the one above, starting a group. */
		final boolean startsGroup;

		Tab(String label, IconTile.Concept concept, boolean startsGroup) {
			this(label, concept.icon, concept.family, startsGroup);
		}

		Tab(String label, String icon, IconTile.Family family, boolean startsGroup) {
			this.label = label;
			this.icon = icon;
			this.family = family;
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
	private final brightside.ui.jobs.JobsScreen jobs;
	private final SecretsPanel secrets;
	private final VaultPanel vault;
	private final AccessPanel access;

	public SettingsScreen(GeneralPanel general, ThemePanel theme, ModelPanel model, ProfilePanel profile,
			IntegrationsPanel integrations, SchedulePanel schedule, brightside.ui.jobs.JobsScreen jobs,
			SecretsPanel secrets, VaultPanel vault, AccessPanel access) {
		super(new BorderLayout());
		this.general = general;
		this.theme = theme;
		this.model = model;
		this.profile = profile;
		this.integrations = integrations;
		this.schedule = schedule;
		this.jobs = jobs;
		this.secrets = secrets;
		this.vault = vault;
		this.access = access;

		content.setOpaque(false);
		content.add(profile, Tab.PROFILE.name());
		content.add(general, Tab.GENERAL.name());
		content.add(theme, Tab.THEME.name());
		content.add(model, Tab.MODEL.name());
		content.add(integrations, Tab.INTEGRATIONS.name());
		content.add(schedule, Tab.SCHEDULE.name());
		content.add(jobs, Tab.JOBS.name());
		content.add(secrets, Tab.SECRETS.name());
		content.add(vault, Tab.VAULT.name());
		content.add(access, Tab.ACCESS.name());

		// The section nav: one PressButton per section, the same control as the
		// bottom tabs — hover, pressed and selected looks from the theme, acting
		// on the press — its icon in the accent with the label when selected,
		// muted otherwise. The column is as wide as its widest section.
		JPanel column = Panels.column();
		column.setBorder(new FlatEmptyBorder(8, 6, 8, 6));
		// Search: by a section's name or what is on it ("lock", "passphrase", "token"); Enter opens the first match.
		search.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Search settings");
		search.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_LEADING_ICON,
			brightside.ui.components.Lucide.icon("search", 14, brightside.ui.components.Theme::muted));
		search.getAccessibleContext().setAccessibleName("Search settings");
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, search.getPreferredSize().height));
		brightside.ui.components.Documents.onChange(search, this::filter);
		search.addActionListener(e -> {
			for (Tab t : Tab.values()) if (nav.get(t).isVisible()) { select(t); return; }
		});
		column.add(search);
		column.add(Box.createVerticalStrut(UIScale.scale(8)));
		for (Tab t : Tab.values()) {
			PressButton b = new PressButton(t.label);
			b.setIcon(new IconTile(t.icon, t.family, 24).asIcon());
			b.setIconTextGap(10);
			b.setHorizontalAlignment(SwingConstants.LEFT);
			b.setMargin(new Insets(4, 8, 4, 16)); // the tile carries its own padding
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
		// Scrolls when the window is too short for every section, so none is ever out of reach.
		// The view keeps its own width (the widest section), so no label is cut short.
		javax.swing.JScrollPane navHolder = new javax.swing.JScrollPane(Scrolls.hugTop(column),
			javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		navHolder.setOpaque(false);
		navHolder.getViewport().setOpaque(false);
		navHolder.getVerticalScrollBar().setUnitIncrement(UIScale.scale(16));
		navHolder.setBorder(Borders.hairlineRight());

		add(navHolder, BorderLayout.WEST);
		add(content, BorderLayout.CENTER);
		select(Tab.values()[0]);
	}

	private Tab selected = Tab.values()[0];
	private final javax.swing.JTextField search = new javax.swing.JTextField();

	/** What each section is about, beyond its name, for search. */
	static String keywords(Tab t) {
		return switch (t) {
			case PROFILE -> "identity name operator switch user acting as id did seed recovery key";
			case MODEL -> "models model provider api key included plan";
			case PEERS -> "peers people homes follow relay discovery network map";
			case TREASURY -> "treasury plan free paid payments money njord limits billing";
			case GENERAL -> "general lock quit startup sign in tray voice read aloud data store compact dashboard logs about";
			case THEME -> "theme dark light system accent colour density layout sidebar bottom bar";
			case INTEGRATIONS -> "integrations discord moltbook connections";
			case SCHEDULE -> "schedule timer remind repeat later";
			case JOBS -> "jobs tasks running record";
			case SECRETS -> "secrets keys passwords";
			case VAULT -> "vault passphrase remember stored";
			case ACCESS -> "access tokens token mcp login permissions connect";
		};
	}

	/** Shows the sections whose name or keywords match the search; all of them when it is empty. */
	void filter() {
		String q = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
		for (Tab t : Tab.values()) nav.get(t).setVisible(matches(t, q));
		revalidate();
		repaint();
	}

	/** Whether section {@code t} answers the search {@code query} (by its name or what is on it). */
	static boolean matches(Tab t, String query) {
		String q = query.strip().toLowerCase(java.util.Locale.ROOT);
		return q.isEmpty() || t.label.toLowerCase(java.util.Locale.ROOT).contains(q) || keywords(t).contains(q);
	}

	/** The sections shown just now, for tests. */
	java.util.List<Tab> visibleSections() {
		return java.util.Arrays.stream(Tab.values()).filter(t -> nav.get(t).isVisible()).toList();
	}

	/** Types into the search, for tests. */
	void search(String text) {
		search.setText(text);
	}
	/** Pages that live on a screen of their own (the sidebar layout): their entry here opens it instead. */
	private final Map<Tab, Runnable> elsewhere = new EnumMap<>(Tab.class);

	/** Puts a page that is built outside the constructor (Peers, Treasury) into its section. */
	public void install(Tab tab, java.awt.Component page) {
		content.add(page, tab.name());
		content.revalidate();
	}

	/** Whether {@code tab}'s page lives on a screen of its own just now. */
	public boolean isElsewhere(Tab tab) {
		return elsewhere.containsKey(tab);
	}

	/** {@code tab}'s page lives elsewhere: its entry opens it there. Null brings it back here. */
	public void livesElsewhere(Tab tab, Runnable open) {
		if (open == null) elsewhere.remove(tab);
		else elsewhere.put(tab, open);
	}

	/** Puts a page's panel back in this screen, after it was shown on a screen of its own. */
	public void restore(Tab tab, java.awt.Component panel) {
		content.add(panel, tab.name());
		content.revalidate();
	}

	/** The section on view. */
	public Tab selected() {
		return selected;
	}

	/** Select a section (also shows its content). */
	public void select(Tab t) {
		Runnable open = elsewhere.get(t);
		if (open != null) {
			open.run();
			return;
		}
		selected = t;
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

	public brightside.ui.jobs.JobsScreen jobs() {
		return jobs;
	}

	public SecretsPanel secrets() {
		return secrets;
	}

	public VaultPanel vault() {
		return vault;
	}

	public AccessPanel access() {
		return access;
	}
}
