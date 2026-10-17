package brightside.ui.peers;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brightside.peers.MapLayout;
import brightside.peers.MapSettings;
import brightside.peers.MapSettingsStore;
import brightside.peers.Node;
import brightside.peers.PeerGraph;
import brightside.peers.PeerSource;
import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Scrolls;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Peers</b> screen: the nodes a {@link PeerSource} reports, drawn as a
 * solar map ({@link SolarMap}) by this person's {@link MapSettings}, with a
 * card beside it for the one chosen. Peer data and settings stay apart: the
 * map never writes to the source, and the settings, saved shortly after each
 * change, hold nothing but how the map is drawn.
 */
@SuppressWarnings("serial")
public final class PeersScreen extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(PeersScreen.class);
	/** How long after the last change the settings are saved. */
	private static final int SAVE_AFTER_MS = 700;

	private final PeerSource source;
	private final Supplier<MapSettingsStore> stores;
	private final SolarMap map;
	private final JPanel side = new JPanel(new BorderLayout());
	private final JButton pause = Buttons.icon(null, null);
	private final JLabel view = Labels.small(" ", Styles.MUTED);
	private final JButton reset = Buttons.icon(Lucide.icon("rotate-ccw", 16, Theme::muted),
		"Put every planet, moon, venue and orbit back as it started");
	private final JButton follow = Buttons.icon(null, null);
	private final Actions actions;
	private final Timer saveLater;
	private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "brightside-peers-settings");
		t.setDaemon(true);
		return t;
	});
	private MapSettingsStore store;
	private MapSettings unsaved;

	/**
	 * What the map's menu can do in the rest of the app: chat, and switch
	 * between acting as the named user and as the venue operator.
	 */
	public interface Actions {
		/** Nothing beyond the map itself: for previews and tests. */
		Actions NONE = new Actions() {
			@Override
			public boolean actingAsOperator() {
				return false;
			}

			@Override
			public void actAs(boolean operator) {
			}

			@Override
			public void chat(boolean operator, String agentId) {
			}
		};

		boolean actingAsOperator();

		void actAs(boolean operator);

		/** Opens the chat with {@code agentId}, or the principal's standard agent when null, as that principal. */
		void chat(boolean operator, String agentId);
	}

	public PeersScreen(PeerSource source, Supplier<MapSettingsStore> stores) {
		this(source, stores, Actions.NONE);
	}

	/**
	 * @param source  where the peer data comes from
	 * @param stores  the acting person's settings store, asked again each time the tab opens
	 * @param actions what the map's menu can do in the rest of the app
	 */
	public PeersScreen(PeerSource source, Supplier<MapSettingsStore> stores, Actions actions) {
		super(new BorderLayout());
		this.source = source;
		this.stores = stores;
		map = new SolarMap(this::show, this::syncView, this::settingsChanged);
		// Switching who you act as from the menu moves the yellow at once.
		this.actions = new Actions() {
			@Override
			public boolean actingAsOperator() {
				return actions.actingAsOperator();
			}

			@Override
			public void actAs(boolean operator) {
				actions.actAs(operator);
				syncActing();
			}

			@Override
			public void chat(boolean operator, String agentId) {
				actions.chat(operator, agentId);
				syncActing();
			}
		};
		map.setOnMenu((did, at) -> new PeersMenu(map, source.graph(), this.actions).build(did).show(map, at.x, at.y));
		saveLater = new Timer(SAVE_AFTER_MS, e -> save());
		saveLater.setRepeats(false);

		JPanel header = new JPanel(new MigLayout("insets 14 22 12 16, fillx",
			"[]10[]push[]14[]2[]2[]2[]2[]10[]2[]", ""));
		header.setBorder(Borders.hairline(false, false, true, false));
		header.add(Styles.style(new JLabel("Peers"), "font: bold +4"));
		if (source.sample()) {
			header.add(chip("Preview · sample venues",
				"Federation isn't connected yet: other venues are examples of what will appear here"));
		} else {
			header.add(new JLabel());
		}
		pause.addActionListener(e -> {
			map.togglePaused();
			syncPause();
		});
		reset.addActionListener(e -> map.resetLayout());
		header.add(view);
		header.add(button("zoom-out", "Zoom out (or scroll)", () -> map.zoomStep(false)));
		header.add(button("zoom-in", "Zoom in (or scroll)", () -> map.zoomStep(true)));
		header.add(button("locate-fixed", "Your system (or double-click the map)", map::home));
		header.add(button("telescope", "The whole universe of venues", map::universe));
		follow.addActionListener(e -> {
			String you = map.acting();
			map.track(you != null && !you.equals(map.tracking()) ? you : null);
		});
		header.add(follow);
		header.add(pause);
		header.add(reset);

		side.setOpaque(false);
		side.setPreferredSize(new Dimension(310, 0));
		side.setBorder(Borders.hairline(false, true, false, false));

		JPanel body = new JPanel(new BorderLayout());
		body.add(map, BorderLayout.CENTER);
		body.add(side, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);

		source.onChange(() -> SwingUtilities.invokeLater(() -> map.setData(source.graph(), map.settings(), false)));
		reload();
	}

	/**
	 * Reads the acting person's settings and the source's data afresh — on
	 * opening the tab, and after the person acting changes — and opens on the
	 * stored view. Anything still waiting to be saved for the previous store is
	 * saved first.
	 */
	public void reload() {
		if (saveLater.isRunning()) {
			saveLater.stop();
			save();
		}
		store = stores.get();
		source.refresh();
		PeerGraph graph = source.graph();
		map.setData(graph, store.load().prune(graph), true);
		syncActing();
		syncPause();
		syncView();
		show(null);
	}

	/** Tells the map who you are acting as now, so it is drawn in yellow. */
	private void syncActing() {
		map.setActing(PeersMenu.actingPlanet(source.graph(), actions.actingAsOperator()));
		syncView();
	}

	private void settingsChanged(MapSettings s) {
		unsaved = s;
		saveLater.restart();
		syncView();
	}

	private void save() {
		MapSettings s = unsaved;
		MapSettingsStore target = store;
		unsaved = null;
		if (s == null || target == null) return;
		saver.execute(() -> {
			try {
				target.save(s);
			} catch (RuntimeException e) {
				log.warn("Could not save the Peers map settings: {}", e.toString());
			}
		});
	}

	private void syncPause() {
		boolean p = map.isPaused();
		pause.setIcon(Lucide.icon(p ? "play" : "circle-pause", 16, Theme::muted));
		pause.setToolTipText(p ? "Set the map turning again" : "Hold the map still");
	}

	private void syncView() {
		view.setText(map.viewName() + " · " + Math.round(map.zoom() * 100) + "%");
		reset.setEnabled(map.rearranged());
		boolean following = map.tracking() != null;
		String you = map.acting();
		follow.setIcon(Lucide.icon("orbit", 16, following ? Theme::accent : Theme::muted));
		follow.setToolTipText(following ? "Stop tracking" : "Track your planet");
		follow.setEnabled(following || you != null);
	}

	private static JButton button(String icon, String tooltip, Runnable action) {
		JButton b = Buttons.icon(Lucide.icon(icon, 16, Theme::muted), tooltip);
		b.addActionListener(e -> action.run());
		return b;
	}

	// ------------------------------------------------------------------
	// The side card
	// ------------------------------------------------------------------

	/** An overview with nothing chosen, else the chosen body's card. */
	private void show(String did) {
		side.removeAll();
		JPanel content = new JPanel(new MigLayout("insets 18 18 18 22, fillx, wrap 1, gapy 6", "[grow,fill]", ""));
		content.setOpaque(false);
		PeerGraph graph = source.graph();
		MapLayout.Body b = (did == null) ? null : map.body(did);
		if (b == null) overview(content, graph);
		else card(content, b);
		side.add(Scrolls.vertical(content), BorderLayout.CENTER);
		side.revalidate();
		side.repaint();
	}

	private void overview(JPanel c, PeerGraph graph) {
		MapSettings s = map.settings();
		Node home = map.homeVenue();
		List<Node> users = (home == null) ? List.of() : map.childrenOf(home.did());
		int agents = (home == null) ? 0 : map.descendants(home.did()) - users.size();
		long venues = graph.nodes().stream().filter(n -> n.is(Node.VENUE) && n != home).count();
		c.add(Styles.style(new JLabel("Home"), "font: bold +2"));
		if (home != null) c.add(Labels.small(home.label(), Styles.MUTED));
		c.add(Labels.small(SolarMap.plural(users.size(), "user") + " · " + SolarMap.plural(agents, "agent") + " · "
			+ SolarMap.plural((int) venues, "other venue"), Styles.MUTED), "gapbottom 10");

		c.add(heading("Rings"));
		List<MapSettings.Ring> rings = s.rings();
		for (int i = 0; i < rings.size(); i++) {
			int ring = i;
			long count = users.stream().filter(n -> s.ringOf(n) == ring).count();
			c.add(legendRow(new RingIcon(i + 1, rings.size()), rings.get(i).name(), String.valueOf(count)));
		}
		c.add(heading("Reading the map"), "gaptop 10");
		c.add(legendRow(new SunIcon(), "Stars are venues; Home is yours", null));
		c.add(legendRow(new PlanetIcon(new Color(0x5EC8F2), 18, false), "Planets are a venue's users", null));
		c.add(legendRow(lamp(new Color(0xA9AFBC)), "Moons are their agents", null));
		c.add(legendRow(new PlanetIcon(SolarMap.YOU, 18, false), "Who you are acting as, in yellow", null));
		c.add(legendRow(lamp(Theme.accent()), "Your main assistant", null));
		c.add(legendRow(new PlanetIcon(new Color(0xE8C66A), 18, true), "Rings mark a venue's admin", null));
		c.add(legendRow(new PlanetIcon(SolarMap.greyed(new Color(0x5EC8F2)), 18, false), "Greyed out: offline or not connected", null));

		c.add(new SelectableText("Click a body to see it, or drag it somewhere new; drag a ring's line to resize it. "
			+ "Scroll to zoom, drag the sky to pan, double-click a body to visit it, or the sky to come home.")
			.small().muted(), "gaptop 16, wmin 0");
	}

	/**
	 * Any body's card, built the same way whatever it is: what it is and
	 * whose, its status and facts, what orbits it, and what can be done.
	 */
	private void card(JPanel c, MapLayout.Body b) {
		Node n = b.node;
		Node owner = (b.parent == null) ? null : b.parent.node;
		boolean ours = isOurs(b);
		title(c, n, role(n, owner));
		c.add(fact("Status", new JLabel(SolarMap.statusLabel(n), lamp(SolarMap.lamp(n.status())), SwingConstants.LEADING)));
		if (n.name() != null) {
			facts(c, n);
		} else {
			c.add(new SelectableText("Nobody has introduced it to you: nothing is known about it until someone you "
				+ "trust vouches for it.").small().muted(), "gaptop 6, wmin 0");
		}
		List<Node> kids = map.childrenOf(n.did());
		if (!kids.isEmpty()) {
			String noun = MapLayout.childNoun(n);
			c.add(heading(noun.equals("body") ? "Round it" : noun + "s"), "gaptop 12");
			for (Node k : kids) {
				int grand = map.childCount(k.did());
				String trailing = (grand > 0) ? SolarMap.plural(grand, MapLayout.childNoun(k)) : SolarMap.statusLabel(k);
				c.add(bodyRow(k, iconFor(k), trailing));
			}
		}
		List<JButton> buttons = new ArrayList<>();
		if (!kids.isEmpty() && !(b.isRoot() && ours)) {
			JButton visit = Buttons.secondary("Visit");
			visit.setToolTipText("Fly over to " + n.label() + " to see what orbits it (or double-click it)");
			visit.addActionListener(e -> map.visit(n.did()));
			buttons.add(visit);
		}
		if (!ours && n.is(Node.PERSON)) {
			buttons.add(disabled("Message"));
			buttons.add(disabled("Manage access…"));
		} else if (!ours && b.isRoot()) {
			buttons.add(disabled(Node.LINKED.equals(n.relation()) ? "Manage access…" : "Ask for an introduction…"));
		}
		if (!buttons.isEmpty()) actions(c, buttons);
	}

	/** Whether a body is in your own venue's system. */
	private boolean isOurs(MapLayout.Body b) {
		Node home = map.homeVenue();
		return home != null && b.star().node == home;
	}

	/** What a body is, and whose. */
	private String role(Node n, Node owner) {
		String what;
		if (n.is(Node.VENUE)) {
			what = Node.SELF.equals(n.relation()) ? "Home" : n.name() != null ? "Venue" : "Not in your directory";
		} else if (n.is(Node.PERSON)) {
			what = Node.SELF.equals(n.relation()) ? "You" : SolarMap.ringed(n) ? "Admin" : "User";
		} else if (n.is(Node.AGENT)) {
			what = Node.MAIN.equals(n.relation()) ? "Main assistant" : "Agent";
		} else {
			String k = (n.kind() == null) ? "Body" : n.kind();
			what = k.substring(0, 1).toUpperCase() + k.substring(1).replace('-', ' ');
		}
		return (owner == null) ? what : what + " · " + SolarMap.labelOf(owner);
	}

	/** How a body looks in a list: as a moon if it is an agent, else as its planet, coloured as on the map. */
	private Icon iconFor(Node n) {
		if (n.is(Node.AGENT)) return Colours.moon(() -> map.colourOf(n), 14);
		Color colour = SolarMap.disconnected(n) ? SolarMap.greyed(map.colourOf(n)) : map.colourOf(n);
		return new PlanetIcon(colour, 18, SolarMap.ringed(n));
	}

	private void title(JPanel c, Node n, String subtitle) {
		JPanel title = new JPanel(new MigLayout("insets 0, gap 12", "[][grow,fill]", "[][]"));
		title.setOpaque(false);
		title.add(new JLabel(new PlanetIcon(map.colourOf(n), 40, SolarMap.ringed(n))), "spany 2");
		title.add(Styles.style(new JLabel(n.name() != null ? n.name() : SolarMap.labelOf(n)), "font: bold +3"), "wrap, wmin 0");
		title.add(Labels.small(subtitle, Styles.MUTED));
		title.setToolTipText(n.did());
		c.add(title, "gapbottom 8");
	}

	/** Whatever the source says: plain facts as rows, lists as sections, in its order. */
	private static void facts(JPanel c, Node n) {
		for (Node.Fact f : n.facts()) {
			if (f.section()) continue;
			c.add(fact(f.label(), new SelectableText(f.value())));
		}
		for (Node.Fact f : n.facts()) {
			if (!f.section() || f.values().isEmpty()) continue;
			c.add(heading(f.label()), "gaptop 12");
			for (String v : f.values()) {
				c.add(new JLabel(v, Lucide.icon("circle-check", 14, Theme::success), SwingConstants.LEADING));
			}
		}
	}

	private static JButton disabled(String text) {
		JButton b = Buttons.plain(text);
		b.setEnabled(false);
		b.setToolTipText("Arrives with federation; this is a preview");
		return b;
	}

	private static void actions(JPanel c, List<JButton> buttons) {
		JPanel row = new JPanel(new MigLayout("insets 0, gap 8", "", ""));
		row.setOpaque(false);
		for (JButton b : buttons) row.add(b);
		c.add(row, "gaptop 18");
	}

	// ------------------------------------------------------------------
	// Pieces
	// ------------------------------------------------------------------

	private static JComponent heading(String text) {
		JLabel l = Labels.small(text.toUpperCase(), Styles.MUTED);
		Styles.style(l, "font: bold -1");
		return l;
	}

	private static JComponent fact(String label, JComponent value) {
		JPanel row = new JPanel(new MigLayout("insets 0, fillx, gap 10", "[80!][grow,fill]", ""));
		row.setOpaque(false);
		row.add(Labels.small(label, Styles.MUTED), "aligny top, gaptop 1");
		row.add(value, "wmin 0");
		return row;
	}

	/**
	 * A row for a body on the map, drawn as it is there: click to choose it,
	 * right-click for the same menu the map gives it.
	 */
	private JComponent bodyRow(Node n, Icon icon, String trailing) {
		JComponent row = legendRow(icon, n.label(), trailing);
		row.setToolTipText("Click to choose " + n.label() + "; right-click for more");
		java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				if (e.isPopupTrigger()) menu(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				if (e.isPopupTrigger()) menu(e);
			}

			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) map.select(n.did());
			}

			private void menu(java.awt.event.MouseEvent e) {
				new PeersMenu(map, source.graph(), actions).build(n.did()).show(e.getComponent(), e.getX(), e.getY());
			}
		};
		row.addMouseListener(mouse);
		for (Component child : row.getComponents()) child.addMouseListener(mouse);
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return row;
	}

	private static JComponent legendRow(Icon icon, String text, String trailing) {
		JPanel row = new JPanel(new MigLayout("insets 0, fillx, gap 8", "[20!][grow,fill][]", ""));
		row.setOpaque(false);
		row.add(new JLabel(icon), "center");
		row.add(Labels.text(text), "wmin 0");
		if (trailing != null) row.add(Labels.small(trailing, Styles.MUTED));
		return row;
	}

	private static JComponent chip(String text, String tooltip) {
		Card c = new Card(999).fill(() -> Theme.fade(Theme.accent(), Theme.isDark() ? 0.18f : 0.12f));
		c.setLayout(new MigLayout("insets 2 9 2 9", "[]", "[]"));
		JLabel l = Labels.small(text);
		Styles.style(l, "foreground: $Brightside.tinted.foreground");
		c.add(l);
		c.setToolTipText(tooltip);
		return c;
	}

	private static Icon lamp(Color colour) {
		return new Painted(10, (g, s) -> {
			g.setColor(colour);
			g.fillOval(1, 1, s - 2, s - 2);
		});
	}

	/** A small painted icon, drawn by {@code painter} into a square of {@code size}. */
	private record Painted(int size, Painter painter) implements Icon {
		interface Painter {
			void paint(Graphics2D g, int size);
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.translate(x, y);
			painter.paint(g2, getIconWidth());
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return com.formdev.flatlaf.util.UIScale.scale(size);
		}

		@Override
		public int getIconHeight() {
			return getIconWidth();
		}
	}

	/** A shaded planet, as on the map, for the card's title and the legend. */
	private static final class PlanetIcon implements Icon {
		private final Color hue;
		private final int size;
		private final boolean ringed;

		PlanetIcon(Color hue, int size, boolean ringed) {
			this.hue = hue;
			this.size = size;
			this.ringed = ringed;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int s = getIconWidth();
			float r = s * 0.34f;
			float cx = x + s / 2f, cy = y + s / 2f;
			g2.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(cx, cy), r * 1.25f,
				new java.awt.geom.Point2D.Float(cx - r * 0.4f, cy - r * 0.4f), new float[] { 0f, 0.5f, 1f },
				new Color[] { Theme.blend(hue, Color.WHITE, 0.45f), hue, Theme.blend(hue, Color.BLACK, 0.7f) },
				java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE));
			g2.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
			if (ringed) {
				g2.setStroke(new java.awt.BasicStroke(s * 0.05f));
				g2.setColor(Theme.fade(Theme.blend(hue, Color.WHITE, 0.3f), 0.8f));
				g2.draw(new java.awt.geom.Ellipse2D.Float(cx - r * 1.9f, cy - r * 0.5f, r * 3.8f, r * 1.0f));
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return com.formdev.flatlaf.util.UIScale.scale(size);
		}

		@Override
		public int getIconHeight() {
			return getIconWidth();
		}
	}

	/** Concentric orbits, the one in question picked out. */
	private record RingIcon(int ring, int of) implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int s = getIconWidth();
			for (int i = 1; i <= of; i++) {
				float r = s * (0.14f + 0.36f * i / of);
				g2.setColor(i == ring ? Theme.accent() : Theme.fade(Theme.muted(), 0.35f));
				g2.setStroke(new java.awt.BasicStroke(i == ring ? 1.6f : 1f));
				g2.draw(new java.awt.geom.Ellipse2D.Float(x + s / 2f - r, y + s / 2f - r * 0.6f, 2 * r, 2 * r * 0.6f));
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return com.formdev.flatlaf.util.UIScale.scale(18);
		}

		@Override
		public int getIconHeight() {
			return getIconWidth();
		}
	}

	private static final class SunIcon implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int s = getIconWidth();
			Color a = Theme.accent();
			g2.setPaint(new java.awt.RadialGradientPaint(x + s / 2f, y + s / 2f, s / 2f, new float[] { 0f, 0.45f, 1f },
				new Color[] { Color.WHITE, a, Theme.fade(a, 0f) }));
			g2.fillOval(x, y, s, s);
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return com.formdev.flatlaf.util.UIScale.scale(16);
		}

		@Override
		public int getIconHeight() {
			return getIconWidth();
		}
	}
}
