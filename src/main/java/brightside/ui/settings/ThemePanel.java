package brightside.ui.settings;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.LAF;
import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Theme</b> settings page. A <b>Light / Dark</b> switch comes first;
 * under it, the themes of that mode that FlatLaf provides — its own, the
 * IntelliJ theme pack, and any {@code .theme.json} the owner drops into the
 * data home's {@code themes} folder — so each mode keeps its own chosen theme
 * and the switch flips between them. On FlatLaf's own themes there is also an
 * accent colour: a row of swatches or any colour from the chooser. Every
 * choice applies to the running app at once (the host cross-fades every
 * window) and is remembered on this computer, so there is nothing to save.
 *
 * <p>Dumb: it reports the choice through the {@link Host}; the app persists
 * it and switches the look.
 */
@SuppressWarnings("serial")
public final class ThemePanel extends JPanel {

	/** What a choice does; the app persists it and applies it live. */
	public interface Host {
		/** {@link LAF#LIGHT} or {@link LAF#DARK}. */
		void setMode(String mode);

		/** A {@link LAF.Choice#id() theme id}; it becomes its mode's theme. */
		void setTheme(String id);

		/** The accent as {@code #RRGGBB}, or null for the default. */
		void setAccent(String accent);

		/** Open the folder the owner's own {@code .theme.json} files go in. */
		void openThemesFolder();
	}

	/** A named accent on offer. */
	private record Accent(String label, String hex) {
	}

	private static final List<Accent> ACCENTS = List.of(
		new Accent("Brightside", LAF.BRIGHTSIDE_ACCENT),
		new Accent("Sky", "#3B82F6"),
		new Accent("Teal", "#14B8A6"),
		new Accent("Leaf", "#3FB950"),
		new Accent("Amber", "#F59E0B"),
		new Accent("Rose", "#EC4899"));

	private static final int SWATCH = 22;

	private final Host host;
	private final JRadioButton dark = new JRadioButton("Dark");
	private final JRadioButton light = new JRadioButton("Light");
	private final DefaultListModel<LAF.Choice> themes = new DefaultListModel<>();
	private final JList<LAF.Choice> list = new JList<>(themes);
	/** Swatches by their colour, upper-case {@code #RRGGBB}. */
	private final Map<String, Swatch> swatches = new LinkedHashMap<>();
	private final JButton custom = Buttons.secondary("Custom…");
	private final JLabel accentNote = Labels.small(" ");
	private String mode = LAF.DARK;
	private String currentAccent = LAF.BRIGHTSIDE_ACCENT;
	private boolean syncing;

	public ThemePanel(Host host) {
		super(new BorderLayout());
		this.host = host;
		JPanel form = new JPanel(new MigLayout("insets 24 28 20 28, fill, wrap 1", "[grow,fill]",
			"[]12[]4[]12[grow,fill]14[]6[]4[]14[]"));

		form.add(SelectableText.description("How Brightside looks. Changes apply straight away and are "
			+ "remembered on this computer."), "growx, wmin 0");

		// The switch. Each mode keeps its own theme from the list below.
		ButtonGroup modes = new ButtonGroup();
		for (JRadioButton b : new JRadioButton[] { dark, light }) {
			b.setOpaque(false);
			modes.add(b);
		}
		dark.addActionListener(e -> chooseMode(LAF.DARK));
		light.addActionListener(e -> chooseMode(LAF.LIGHT));
		JPanel switcher = Panels.row();
		switcher.add(dark);
		switcher.add(Box.createHorizontalStrut(18));
		switcher.add(light);
		form.add(switcher);

		form.add(Labels.small("The theme for this mode: any that FlatLaf provides."), "growx, wmin 0");
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new Row());
		list.setVisibleRowCount(8);
		list.addListSelectionListener(e -> {
			if (syncing || e.getValueIsAdjusting()) return;
			LAF.Choice chosen = list.getSelectedValue();
			if (chosen != null) host.setTheme(chosen.id());
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(360, 240));
		form.add(scroll, "grow, wmax 520");

		JLabel accentHeading = Labels.section("Accent colour");
		accentHeading.setBorder(Borders.hairlineTop());
		form.add(accentHeading, "growx, gapbottom 2");
		JPanel row = Panels.row();
		for (Accent a : ACCENTS) {
			Swatch s = new Swatch(a.label(), a.hex());
			swatches.put(key(a.hex()), s);
			row.add(s);
			row.add(Box.createHorizontalStrut(4));
		}
		row.add(Box.createHorizontalStrut(8));
		custom.setToolTipText("Choose any colour");
		custom.addActionListener(e -> chooseCustom());
		row.add(custom);
		form.add(row);
		form.add(accentNote, "growx, wmin 0");

		JPanel own = new JPanel(new MigLayout("insets 0, fillx", "[grow]12[]", ""));
		own.setOpaque(false);
		own.setBorder(Borders.hairlineTop());
		own.add(SelectableText.description("Your own themes: drop a FlatLaf or IntelliJ .theme.json into the "
			+ "themes folder and restart Brightside; it appears under its mode.").small(), "growx, wmin 0, gaptop 12");
		JButton open = Buttons.secondary("Open themes folder");
		open.addActionListener(e -> host.openThemesFolder());
		own.add(open, "aligny top, gaptop 8");
		form.add(own, "growx, wmin 0");

		add(form, BorderLayout.CENTER);
	}

	/** Reflect the app's current mode, theme and accent; fires no host call. */
	public void refresh(String mode, String themeId, String accent) {
		syncing = true;
		try {
			this.mode = LAF.isLight(mode) ? LAF.LIGHT : LAF.DARK;
			(LAF.isLight(mode) ? light : dark).setSelected(true);

			themes.clear();
			themes.addAll(LAF.themes(this.mode));
			LAF.Choice chosen = null;
			int index = -1;
			for (int i = 0; i < themes.size(); i++) {
				if (themes.get(i).id().equals(themeId)) {
					index = i;
					chosen = themes.get(i);
				}
			}
			if (index >= 0) {
				list.setSelectedIndex(index);
				list.ensureIndexIsVisible(index);
			} else {
				list.clearSelection();
			}

			// Only FlatLaf's own themes take an accent; the others bring their own.
			boolean core = chosen != null && chosen.core();
			String valid = LAF.validAccent(accent);
			currentAccent = (valid != null) ? valid : LAF.BRIGHTSIDE_ACCENT;
			Swatch match = swatches.get(key(currentAccent));
			for (Swatch s : swatches.values()) {
				s.setEnabled(core);
				s.setSelected(core && s == match);
			}
			custom.setEnabled(core);
			// A colour from the chooser shows on the Custom button instead.
			custom.setIcon(core && match == null ? new Dot(Color.decode(currentAccent), () -> true, () -> true) : null);
			accentNote.setText(core
				? "The accent colours selection, focus and the main buttons."
				: "This theme sets its own colours; the accent applies to FlatLaf's own themes.");
		} finally {
			syncing = false;
		}
	}

	private void chooseMode(String chosen) {
		if (syncing || chosen.equals(mode)) return;
		host.setMode(chosen);
	}

	private void chooseAccent(String hex) {
		if (syncing) return;
		LAF.Choice chosen = list.getSelectedValue();
		refresh(mode, chosen != null ? chosen.id() : null, hex);
		host.setAccent(LAF.BRIGHTSIDE_ACCENT.equalsIgnoreCase(hex) ? null : hex);
	}

	private void chooseCustom() {
		Color picked = JColorChooser.showDialog(this, "Accent colour", Color.decode(currentAccent));
		if (picked != null) chooseAccent(hex(picked));
	}

	private static String key(String hex) {
		return hex.trim().toUpperCase();
	}

	static String hex(Color c) {
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** One theme: its name, with its family in small muted type. */
	private static final class Row extends JPanel implements ListCellRenderer<LAF.Choice> {
		private final JLabel name = Labels.text("");
		private final JLabel tag = Labels.small("");

		Row() {
			super(new BorderLayout(12, 0));
			setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
			add(name, BorderLayout.CENTER);
			add(tag, BorderLayout.EAST);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends LAF.Choice> list, LAF.Choice value,
				int index, boolean selected, boolean focus) {
			name.setText(value.name());
			tag.setText(value.group());
			setOpaque(selected);
			setBackground(UIManager.getColor("List.selectionBackground"));
			Color selectedText = UIManager.getColor("List.selectionForeground");
			name.setForeground(selected ? selectedText : list.getForeground());
			Styles.classes(tag, selected ? new String[] { Styles.SMALL } : new String[] { Styles.SMALL, Styles.MUTED });
			tag.setForeground(selected ? selectedText : null);
			return this;
		}
	}

	/** One accent on offer: a {@link PressButton} whose icon is a dot of the colour; the chosen one is selected. */
	private final class Swatch extends PressButton {
		Swatch(String label, String hex) {
			super("");
			setToolTipText(label);
			setMargin(new Insets(4, 4, 4, 4));
			setIcon(new Dot(Color.decode(hex), this::isSelected, this::isEnabled));
			onPress(() -> chooseAccent(hex));
		}
	}

	/** A filled disc of a colour — faded when unavailable — with a white centre when it is the chosen one. */
	private static final class Dot implements Icon {
		private final Color colour;
		private final BooleanSupplier chosen;
		private final BooleanSupplier enabled;

		Dot(Color colour, BooleanSupplier chosen, BooleanSupplier enabled) {
			this.colour = colour;
			this.chosen = chosen;
			this.enabled = enabled;
		}

		@Override
		public int getIconWidth() {
			return UIScale.scale(SWATCH);
		}

		@Override
		public int getIconHeight() {
			return UIScale.scale(SWATCH);
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g2);
				int d = getIconWidth();
				g2.setColor(enabled.getAsBoolean() ? colour : Theme.fade(colour, 0.3f));
				g2.fillOval(x, y, d, d);
				if (chosen.getAsBoolean()) {
					int inner = d / 3;
					g2.setColor(Color.WHITE);
					g2.fillOval(x + (d - inner) / 2, y + (d - inner) / 2, inner, inner);
				}
			} finally {
				g2.dispose();
			}
		}
	}
}
