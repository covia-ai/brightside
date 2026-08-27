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
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * A centred bottom navigation bar: small painted icons with labels for the app's
 * screens (Home, Sessions, Settings). The active tab is drawn in the accent
 * colour; clicking one reports through the {@link Listener}. Icons are painted
 * (no font glyphs), matching the rest of the app.
 */
@SuppressWarnings("serial")
public final class NavBar extends JPanel {

	public enum Tab {
		HOME, SESSIONS, SETTINGS
	}

	public interface Listener {
		void onSelect(Tab tab);
	}

	private final Listener listener;
	private final List<Item> items = new ArrayList<>();
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
			g2.dispose();
		}
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
