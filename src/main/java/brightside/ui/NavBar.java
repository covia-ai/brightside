package brightside.ui;

import static brightside.BrightSide.APP_NAME;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Borders;
import brightside.ui.components.Lucide;
import brightside.ui.components.PressButton;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * A centred bottom navigation bar: one {@link PressButton} per screen (Home,
 * Sessions, Skills, Inbox, Settings), each a painted icon over a label. The theme
 * supplies hover, pressed and selected looks; the active tab is selected and
 * drawn in the accent colour; a tab can carry a count badge (requests waiting
 * in the Inbox). A tab reports through the {@link Listener} the moment it is
 * pressed. Glyphs are {@link Lucide} icons, like the rest of the app's.
 */
@SuppressWarnings("serial")
public final class NavBar extends JPanel {

	public enum Tab {
		HOME("Home", "house", "Your conversation"),
		SESSIONS("Sessions", "messages-square", "Browse assistants and saved conversations"),
		FILES("Files", "folder-open", "Browse folders and personal drives"),
		SKILLS("Skills", "book-open-check", "What your assistant can help with"),
		PEERS("Peers", "orbit", "The people, devices, and services your " + APP_NAME + " knows"),
		INBOX("Inbox", "inbox", "Requests waiting for your decision"),
		SETTINGS("Settings", "settings", "Personalise and manage " + APP_NAME);

		final String label;
		final String glyph;
		final String hint;

		Tab(String label, String glyph, String hint) {
			this.label = label;
			this.glyph = glyph;
			this.hint = hint;
		}
	}

	public interface Listener {
		void onSelect(Tab tab);
	}

	private static final int ICON = 22;

	private final Listener listener;
	private final List<Item> items = new ArrayList<>();
	private final Map<Tab, Integer> badges = new EnumMap<>(Tab.class);
	private Tab active = Tab.HOME;

	public NavBar(Listener listener) {
		super(new GridBagLayout()); // centres the row of items
		this.listener = listener;
		setBorder(Borders.hairlineTop());

		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		for (Tab tab : Tab.values()) addItem(row, tab);
		add(row);
		setActive(active);
	}

	private void addItem(JPanel row, Tab tab) {
		if (!items.isEmpty()) row.add(Box.createHorizontalStrut(8));
		Item it = new Item(tab);
		items.add(it);
		row.add(it);
	}

	/** Highlight the given tab as active (does not fire the listener). */
	public void setActive(Tab tab) {
		this.active = tab;
		for (Item it : items) it.refreshLook();
	}

	/** Show {@code count} on the tab's icon (0 clears it). */
	public void setBadge(Tab tab, int count) {
		badges.put(tab, count);
		for (Item it : items) if (it.tab == tab) it.repaint();
	}

	public int badge(Tab tab) {
		return badges.getOrDefault(tab, 0);
	}

	/**
	 * One tab: a {@link PressButton} whose icon paints the tab's glyph and badge
	 * and whose text is the label beneath; the active tab is selected (tinted)
	 * and drawn in the accent.
	 */
	private final class Item extends PressButton {
		private final Tab tab;

		Item(Tab tab) {
			super(tab.label);
			this.tab = tab;
			setIcon(new TabIcon());
			setHorizontalTextPosition(SwingConstants.CENTER);
			setVerticalTextPosition(SwingConstants.BOTTOM);
			setIconTextGap(3);
			setMargin(new Insets(6, 10, 5, 10));
			setPreferredSize(new Dimension(84, 58));
			setMaximumSize(new Dimension(84, 58));
			setToolTipText(tab.hint);
			onPress(() -> listener.onSelect(tab));
			getModel().addChangeListener(e -> repaint()); // rollover brightens the glyph
		}

		void refreshLook() {
			boolean on = tab == active;
			setSelected(on);
			Styles.classes(this, on ? Styles.NAVIGATION_ACTIVE : Styles.NAVIGATION);
			repaint();
		}

		/**
		 * The Lucide glyph in one of three tints — accent when active, foreground
		 * under the pointer, muted otherwise — with the badge drawn over its
		 * top-right corner. Its size is the SVG icon's own, which is UI-scaled:
		 * the button lays the label out under whatever width the icon reports,
		 * so reporting anything else would shift the two apart on a scaled display.
		 */
		private final class TabIcon implements Icon {
			private final Icon accent = Lucide.icon(tab.glyph, ICON, Theme::accentText);
			private final Icon bright = Lucide.icon(tab.glyph, ICON, Theme::foreground);
			private final Icon quiet = Lucide.icon(tab.glyph, ICON, Theme::muted);

			@Override
			public int getIconWidth() {
				return accent.getIconWidth();
			}

			@Override
			public int getIconHeight() {
				return accent.getIconHeight();
			}

			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				boolean on = tab == active;
				(on ? accent : (getModel().isRollover() ? bright : quiet)).paintIcon(c, g, x, y);
				int count = badge(tab);
				if (count > 0) {
					Graphics2D g2 = (Graphics2D) g.create();
					try {
						FlatUIUtils.setRenderingHints(g2);
						drawBadge(g2, count > 99 ? "99+" : Integer.toString(count),
							x + getIconWidth() - UIScale.scale(4), y - UIScale.scale(1));
					} finally {
						g2.dispose();
					}
				}
			}
		}
	}

	/** The count pill, top-right of the icon: navy with amber text on Light, amber with navy text on Dark. */
	private static void drawBadge(Graphics2D g2, String text, int cx, int cy) {
		g2.setFont(g2.getFont().deriveFont(Font.BOLD, UIScale.scale(9f)));
		FontMetrics fm = g2.getFontMetrics();
		int h = UIScale.scale(14);
		int w = Math.max(h, fm.stringWidth(text) + UIScale.scale(6));
		g2.setColor(Theme.countPill());
		g2.fillRoundRect(cx - w / 2, cy, w, h, h, h);
		g2.setColor(Theme.countPillText());
		g2.drawString(text, cx - fm.stringWidth(text) / 2f, cy + (h + fm.getAscent() - fm.getDescent()) / 2f);
	}

}
