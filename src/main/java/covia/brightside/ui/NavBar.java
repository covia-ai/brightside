package covia.brightside.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * A centred bottom navigation bar: small painted icons with labels for the app's
 * screens (Home, Sessions, Inbox, Settings). The active tab is drawn in the
 * accent colour; a tab can carry a count badge (requests waiting in the Inbox);
 * clicking one reports through the {@link Listener}. Icons are painted (no font
 * glyphs), matching the rest of the app.
 */
@SuppressWarnings("serial")
public final class NavBar extends JPanel {

	public enum Tab {
		HOME, SESSIONS, INBOX, SETTINGS
	}

	public interface Listener {
		void onSelect(Tab tab);
	}

	private final Listener listener;
	private final List<Item> items = new ArrayList<>();
	private final Map<Tab, Integer> badges = new EnumMap<>(Tab.class);
	private Tab active = Tab.HOME;

	public NavBar(Listener listener) {
		super(new GridBagLayout()); // centres the row of items
		this.listener = listener;
		setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, sep()));

		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		addItem(row, Tab.HOME, "Home");
		addItem(row, Tab.SESSIONS, "Sessions");
		addItem(row, Tab.INBOX, "Inbox");
		addItem(row, Tab.SETTINGS, "Settings");
		add(row);
	}

	private void addItem(JPanel row, Tab tab, String label) {
		if (!items.isEmpty()) row.add(Box.createHorizontalStrut(30));
		Item it = new Item(tab, label);
		items.add(it);
		row.add(it);
	}

	/** Highlight the given tab as active (does not fire the listener). */
	public void setActive(Tab tab) {
		this.active = tab;
		for (Item it : items) it.repaint();
	}

	/** Show {@code count} on the tab's icon (0 clears it). */
	public void setBadge(Tab tab, int count) {
		badges.put(tab, count);
		for (Item it : items) if (it.tab == tab) it.repaint();
	}

	public int badge(Tab tab) {
		return badges.getOrDefault(tab, 0);
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	private static Color sep() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}

	private final class Item extends JComponent {
		private final Tab tab;
		private final String label;

		Item(Tab tab, String label) {
			this.tab = tab;
			this.label = label;
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText(switch (tab) {
				case HOME -> "Start a new chat";
				case SESSIONS -> "Chat with the agents and conversations panel";
				case INBOX -> "Requests from your agents waiting for your decision";
				case SETTINGS -> "Model, identity, vault and auth settings";
			});
			setPreferredSize(new Dimension(66, 48));
			setMaximumSize(new Dimension(66, 48));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					listener.onSelect(tab);
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			boolean on = tab == active;
			g2.setColor(on ? LAF.ACCENT : muted());
			int size = 22;
			int x = (getWidth() - size) / 2;
			int y = 5;
			drawIcon(g2, tab, x, y, size);
			g2.setFont(getFont().deriveFont(on ? Font.BOLD : Font.PLAIN, 10.5f));
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(label, (getWidth() - fm.stringWidth(label)) / 2f, y + size + 12);
			int count = badge(tab);
			if (count > 0) drawBadge(g2, count > 99 ? "99+" : Integer.toString(count), x + size - 4, y - 1);
			g2.dispose();
		}
	}

	/** A small filled disc with the count, top-right of the icon. */
	private static void drawBadge(Graphics2D g2, String text, int cx, int cy) {
		g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
		FontMetrics fm = g2.getFontMetrics();
		int w = Math.max(14, fm.stringWidth(text) + 6);
		g2.setColor(LAF.ACCENT);
		g2.fillRoundRect(cx - w / 2, cy, w, 14, 14, 14);
		g2.setColor(Color.WHITE);
		g2.drawString(text, cx - fm.stringWidth(text) / 2f, cy + 10.5f);
	}

	private static void drawIcon(Graphics2D g2, Tab tab, int x, int y, int s) {
		g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (tab) {
			case HOME -> {
				int mid = x + s / 2;
				g2.drawLine(x + 2, y + s / 2, mid, y + 2); // roof left
				g2.drawLine(mid, y + 2, x + s - 2, y + s / 2); // roof right
				g2.drawRect(x + 4, y + s / 2, s - 8, s / 2 - 3); // body
			}
			case SESSIONS -> {
				for (int i = 0; i < 3; i++) {
					g2.fillRoundRect(x + 2, y + 4 + i * 6, s - 4, 3, 3, 3); // list rows
				}
			}
			case INBOX -> {
				int cx = x + s / 2, top = y + s / 2 + 3, bottom = y + s - 3;
				g2.drawLine(cx, y + 2, cx, top - 2); // arrow into the tray
				g2.drawLine(cx - 4, top - 6, cx, top - 2);
				g2.drawLine(cx + 4, top - 6, cx, top - 2);
				g2.drawLine(x + 2, top, x + 2, bottom); // tray sides and base
				g2.drawLine(x + 2, bottom, x + s - 2, bottom);
				g2.drawLine(x + s - 2, bottom, x + s - 2, top);
				g2.drawLine(x + 2, top, x + 6, top); // rim, open in the middle
				g2.drawLine(x + s - 6, top, x + s - 2, top);
			}
			case SETTINGS -> {
				int cx = x + s / 2, cy = y + s / 2, r = s / 2 - 4;
				g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
				g2.drawOval(cx - 2, cy - 2, 4, 4);
				for (int a = 0; a < 360; a += 45) {
					double rad = Math.toRadians(a);
					int x1 = cx + (int) Math.round(Math.cos(rad) * r);
					int y1 = cy + (int) Math.round(Math.sin(rad) * r);
					int x2 = cx + (int) Math.round(Math.cos(rad) * (r + 3));
					int y2 = cy + (int) Math.round(Math.sin(rad) * (r + 3));
					g2.drawLine(x1, y1, x2, y2);
				}
			}
		}
	}
}
