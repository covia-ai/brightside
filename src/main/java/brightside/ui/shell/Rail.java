package brightside.ui.shell;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Lucide;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The sidebar: the TAU wordmark, the destinations in their order, then at
 * the foot the status slot, the identity menu, and the Settings gear. The
 * active item sits on its own ground with a 3 px bar on the left; network
 * screens carry a globe and private ones a lock, each with its word in the
 * tooltip and accessible name. The Inbox carries the count pill. Collapsed,
 * the rail shows icons only, and each label moves to the tooltip and the
 * accessible name. Colours are the theme's, read when painting.
 */
@SuppressWarnings("serial")
public final class Rail extends JPanel {

	/** What the rail asks of the window. */
	public interface Host {
		void go(Destination d);

		void openSettings();

		/** The identity menu's items, built by the window: Switch user, Copy my identity, Lock. */
		void fillIdentityMenu(JPopupMenu menu);
	}

	private final Host host;
	private final Map<Destination, Item> items = new EnumMap<>(Destination.class);
	private final JLabel wordmark = new JLabel();
	private final StatusSlot slot = new StatusSlot();
	private final JButton identity = new JButton();
	private final JButton settings = new JButton();
	private Destination active = Destination.HOME;
	private boolean settingsActive;
	private boolean collapsed;
	private int inboxCount;
	private JPanel footRow;

	public Rail(Host host) {
		super(new BorderLayout());
		this.host = host;
		setOpaque(true);

		JPanel top = column();
		wordmark.setText(wordmarkText());
		wordmark.setFont(wordmark.getFont().deriveFont(Font.BOLD, UIScale.scale(18f)));
		wordmark.setBorder(BorderFactory.createEmptyBorder(12, 16, 10, 8));
		wordmark.getAccessibleContext().setAccessibleName(APP_NAME);
		wordmark.setAlignmentX(LEFT_ALIGNMENT);
		top.add(wordmark);
		int shortcut = Keys.menu();
		for (Destination d : Destination.values()) {
			Item item = new Item(d, KeyStroke.getKeyStroke(KeyEvent.VK_0 + (d.key - '0'), shortcut));
			items.put(d, item);
			top.add(item);
			top.add(Box.createVerticalStrut(UIScale.scale(2)));
		}

		JPanel foot = column();
		foot.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		slot.setAlignmentX(LEFT_ALIGNMENT);
		foot.add(slot);
		foot.add(Box.createVerticalStrut(UIScale.scale(8)));
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		identity.setIcon(Lucide.icon("user-round", 16, Theme::foreground));
		identity.setHorizontalAlignment(SwingConstants.LEFT);
		identity.putClientProperty("JButton.buttonType", "toolBarButton");
		identity.setToolTipText("Your identity: switch user, copy it, or lock " + APP_NAME);
		identity.addActionListener(e -> {
			JPopupMenu menu = new JPopupMenu();
			host.fillIdentityMenu(menu);
			menu.show(identity, 0, -menu.getPreferredSize().height);
		});
		settings.setIcon(Lucide.icon("settings", 18, () -> settingsActive ? Theme.accentText() : Theme.foreground()));
		settings.putClientProperty("JButton.buttonType", "toolBarButton");
		settings.setToolTipText("Settings (" + keyText(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcut)) + ")");
		settings.getAccessibleContext().setAccessibleName("Settings");
		settings.addActionListener(e -> host.openSettings());
		footRow = row;
		row.add(identity, BorderLayout.CENTER);
		row.add(settings, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		foot.add(row);

		// The destinations scroll if the window is ever too short for them all, so none is out of reach.
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(top,
			javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(16));
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(UIScale.scale(6), 0));
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.add(top, BorderLayout.NORTH);
		scroll.setViewportView(holder);
		add(scroll, BorderLayout.CENTER);
		add(foot, BorderLayout.SOUTH);
		setActive(Destination.HOME);
		setIdentity(null);
	}

	private static JPanel column() {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		return p;
	}

	private String wordmarkText() {
		return collapsed ? "τ" : "τ  TAU";
	}

	@Override
	public Color getBackground() {
		return Theme.sidebar();
	}

	@Override
	public Dimension getPreferredSize() {
		Dimension d = super.getPreferredSize();
		// Window points, as ShellLayout reckons them.
		return new Dimension(collapsed ? ShellLayout.RAIL_ICONS : ShellLayout.RAIL, d.height);
	}

	@Override
	public Dimension getMinimumSize() {
		return new Dimension(getPreferredSize().width, 0);
	}

	/** Labels or icons only; the window decides from {@link ShellLayout} and the person's choice. */
	public void setCollapsed(boolean collapsed) {
		if (this.collapsed == collapsed) return;
		this.collapsed = collapsed;
		wordmark.setText(wordmarkText());
		for (Item i : items.values()) i.updateLabel();
		slot.setCollapsed(collapsed);
		identity.setText(collapsed ? null : identityName);
		// Collapsed, the identity menu and the gear stack, each a full-width icon.
		footRow.removeAll();
		footRow.add(identity, collapsed ? BorderLayout.NORTH : BorderLayout.CENTER);
		footRow.add(settings, collapsed ? BorderLayout.SOUTH : BorderLayout.EAST);
		identity.setHorizontalAlignment(collapsed ? SwingConstants.CENTER : SwingConstants.LEFT);
		footRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, footRow.getPreferredSize().height));
		revalidate();
		repaint();
	}

	public boolean isCollapsed() {
		return collapsed;
	}

	public void setActive(Destination d) {
		active = d;
		settingsActive = false;
		for (Map.Entry<Destination, Item> e : items.entrySet()) e.getValue().setSelected(e.getKey() == d);
		repaint();
	}

	/** Settings is open: no destination is active, the gear is. */
	public void setSettingsActive() {
		settingsActive = true;
		for (Item i : items.values()) i.setSelected(false);
		repaint();
	}

	public Destination active() {
		return settingsActive ? null : active;
	}

	public void setInboxCount(int count) {
		inboxCount = Math.max(0, count);
		items.get(Destination.INBOX).updateLabel();
		repaint();
	}

	public int inboxCount() {
		return inboxCount;
	}

	private String identityName;

	/** The name in the identity menu's button, or null before there is one. */
	public void setIdentity(String name) {
		identityName = name;
		identity.setText(collapsed ? null : name);
		identity.getAccessibleContext().setAccessibleName("Identity menu" + (name != null ? ": " + name : ""));
	}

	public StatusSlot slot() {
		return slot;
	}

	/** The item for a destination, for tests and focus. */
	public JToggleButton item(Destination d) {
		return items.get(d);
	}

	public JButton settingsButton() {
		return settings;
	}

	static String keyText(KeyStroke k) {
		return Keys.text(k);
	}

	/** One destination: icon, label, register marker, and for the Inbox the count pill. */
	private final class Item extends JToggleButton {
		private final Destination d;
		private final KeyStroke key;
		private final Icon on, off;
		private final Icon marker;
		/** Focus arrived from the keyboard (Tab and friends): only then does the ring show. */
		private boolean keyboardFocus;

		Item(Destination d, KeyStroke key) {
			this.d = d;
			this.key = key;
			on = Lucide.icon(d.icon, 18, Theme::accentText);
			off = Lucide.icon(d.icon, 18, Theme::foreground);
			marker = (d.register.icon != null) ? Lucide.icon(d.register.icon, 12, Theme::muted) : null;
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
			setHorizontalAlignment(SwingConstants.LEFT);
			setIconTextGap(UIScale.scale(10));
			setMargin(new Insets(4, 14, 4, 10));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(LEFT_ALIGNMENT);
			addFocusListener(new java.awt.event.FocusAdapter() {
				@Override
				public void focusGained(java.awt.event.FocusEvent e) {
					java.awt.event.FocusEvent.Cause cause = e.getCause();
					keyboardFocus = LastInput.wasKey() && (cause == java.awt.event.FocusEvent.Cause.TRAVERSAL
						|| cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD
						|| cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_BACKWARD
						|| cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_UP
						|| cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_DOWN);
					repaint();
				}

				@Override
				public void focusLost(java.awt.event.FocusEvent e) {
					keyboardFocus = false;
					repaint();
				}
			});
			addActionListener(e -> {
				setActive(d); // a press always lands on the destination
				host.go(d);
			});
			updateLabel();
		}

		void updateLabel() {
			setText(collapsed ? null : d.label);
			setHorizontalAlignment(collapsed ? SwingConstants.CENTER : SwingConstants.LEFT);
			String reg = (d.register.word != null) ? " · " + d.register.word : "";
			String count = (d == Destination.INBOX && inboxCount > 0) ? " · " + inboxCount + " waiting" : "";
			setToolTipText(d.label + reg + count + ": " + d.hint + " (" + keyText(key) + ")");
			getAccessibleContext().setAccessibleName(d.label + reg + count);
		}

		@Override
		public Icon getIcon() {
			return isSelected() ? on : off;
		}

		@Override
		public Color getForeground() {
			return Theme.foreground();
		}

		@Override
		public Font getFont() {
			Font f = super.getFont();
			return (f != null && isSelected()) ? f.deriveFont(Font.BOLD) : f;
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width, UIScale.scale(32));
		}

		@Override
		public Dimension getMinimumSize() {
			return new Dimension(0, getPreferredSize().height);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g2);
				int inset = UIScale.scale(8), w = getWidth() - 2 * inset, h = getHeight();
				if (isSelected()) {
					g2.setColor(Theme.sidebarSelected());
					g2.fill(FlatUIUtils.createComponentRectangle(inset, 0, w, h, UIScale.scale(16f)));
					g2.setColor(Theme.sidebarBar());
					g2.fillRect(inset, UIScale.scale(6), UIScale.scale(3), h - UIScale.scale(12));
				} else if (getModel().isRollover() || (hasFocus() && keyboardFocus)) {
					g2.setColor(Theme.warmSurface());
					g2.fill(FlatUIUtils.createComponentRectangle(inset, 0, w, h, UIScale.scale(16f)));
				}
				if (hasFocus() && keyboardFocus) {
					g2.setColor(UIManagerColor.focus());
					FlatUIUtils.paintOutline(g2, inset, 0, w, h, UIScale.scale(1.5f), UIScale.scale(16f));
				}
			} finally {
				g2.dispose();
			}
			super.paintComponent(g);
			Graphics2D g3 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g3);
				int right = getWidth() - UIScale.scale(16);
				if (d == Destination.INBOX && inboxCount > 0) {
					right = paintPill(g3, inboxCount > 99 ? "99+" : Integer.toString(inboxCount), right);
				}
				if (marker != null && !collapsed) {
					marker.paintIcon(this, g3, right - marker.getIconWidth(), (getHeight() - marker.getIconHeight()) / 2);
				}
			} finally {
				g3.dispose();
			}
		}

		/** The count pill, right-aligned (top-right of the icon when collapsed); returns the x left of it. */
		private int paintPill(Graphics2D g2, String text, int right) {
			g2.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(10f)));
			FontMetrics fm = g2.getFontMetrics();
			int h = UIScale.scale(16);
			int w = Math.max(h, fm.stringWidth(text) + UIScale.scale(8));
			int x = collapsed ? getWidth() / 2 + UIScale.scale(2) : right - w;
			int y = collapsed ? UIScale.scale(2) : (getHeight() - h) / 2;
			g2.setColor(Theme.countPill());
			g2.fillRoundRect(x, y, w, h, h, h);
			g2.setColor(Theme.countPillText());
			g2.drawString(text, x + (w - fm.stringWidth(text)) / 2f, y + (h + fm.getAscent() - fm.getDescent()) / 2f);
			return x - UIScale.scale(6);
		}
	}

	/**
	 * Whether the person's last input was a key press rather than the mouse: a
	 * window's first focus also arrives "by traversal", and must not show the ring.
	 */
	static final class LastInput {
		private static volatile boolean key;

		static {
			try {
				java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
					if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) key = true;
					else if (e.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) key = false;
				}, java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.MOUSE_EVENT_MASK);
			} catch (RuntimeException e) {
				// Headless or restricted: the ring then never shows, which errs on the quiet side.
			}
		}

		static boolean wasKey() {
			return key;
		}
	}

	/** The focus colour, read when painting. */
	private static final class UIManagerColor {
		static Color focus() {
			Color c = javax.swing.UIManager.getColor("Component.focusColor");
			return (c != null) ? c : Theme.blue();
		}
	}

	@Override
	public void updateUI() {
		super.updateUI();
		if (wordmark != null) {
			Styles.classes(wordmark);
			wordmark.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(Font.BOLD, UIScale.scale(18f)));
		}
	}
}
