package brightside.ui.peers;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

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
 * The <b>Peers</b> screen: the agents and venues yours knows, as a solar map
 * ({@link SolarMap}) with a card beside it for the one you choose. A preview:
 * every peer on it is a sample ({@link SamplePeers}), shown so the shape of
 * the networked product can be seen and felt before federation exists. It
 * reads nothing from, and sends nothing to, any network.
 */
@SuppressWarnings("serial")
public final class PeersScreen extends JPanel {

	private final SolarMap map;
	private final JPanel side = new JPanel(new BorderLayout());
	private final JButton pause = Buttons.icon(null, null);

	public PeersScreen() {
		super(new BorderLayout());
		map = new SolarMap(SamplePeers.ALL, this::show);

		JPanel header = new JPanel(new MigLayout("insets 14 22 12 16, fillx", "[]10[]push[]4[]", ""));
		header.setBorder(Borders.hairline(false, false, true, false));
		header.add(Styles.style(new JLabel("Peers"), "font: bold +4"));
		header.add(chip("Preview · sample peers",
			"Federation isn't connected yet: these peers are examples of what will appear here"));
		pause.addActionListener(e -> {
			map.setPaused(!map.isPaused());
			syncPause();
		});
		syncPause();
		JButton recentre = Buttons.icon(Lucide.icon("orbit", 16, Theme::muted), "Recentre the map (or double-click it)");
		recentre.addActionListener(e -> map.resetView());
		header.add(pause);
		header.add(recentre);

		side.setOpaque(false);
		side.setPreferredSize(new Dimension(310, 0));
		side.setBorder(Borders.hairline(false, true, false, false));

		JPanel body = new JPanel(new BorderLayout());
		body.add(map, BorderLayout.CENTER);
		body.add(side, BorderLayout.EAST);

		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		show(null);
	}

	private void syncPause() {
		boolean p = map.isPaused();
		pause.setIcon(Lucide.icon(p ? "play" : "circle-pause", 16, Theme::muted));
		pause.setToolTipText(p ? "Set the map turning again" : "Hold the map still");
	}

	/** The side card: an overview with nothing chosen, else the chosen peer. */
	private void show(SamplePeers.Peer p) {
		side.removeAll();
		JPanel content = new JPanel(new MigLayout("insets 18 18 18 22, fillx, wrap 1, gapy 6", "[grow,fill]", ""));
		content.setOpaque(false);
		if (p == null) overview(content);
		else peer(content, p);
		side.add(Scrolls.vertical(content), BorderLayout.CENTER);
		side.revalidate();
		side.repaint();
	}

	private static void overview(JPanel c) {
		long connected = SamplePeers.ALL.stream().filter(p -> p.status() == SamplePeers.Status.ONLINE).count();
		int agents = SamplePeers.ALL.stream().mapToInt(p -> p.moons().size()).sum();
		c.add(Styles.style(new JLabel("Your constellation"), "font: bold +2"));
		c.add(Labels.small(SamplePeers.ALL.size() + " peers · " + connected + " connected · " + agents + " agents",
			Styles.MUTED), "gapbottom 10");

		c.add(heading("Orbits"));
		for (int i = 0; i < SamplePeers.RINGS.size(); i++) {
			int ring = i + 1;
			long n = SamplePeers.ALL.stream().filter(p -> p.ring() == ring).count();
			c.add(legendRow(new RingIcon(ring), SamplePeers.RINGS.get(i), String.valueOf(n)));
		}
		c.add(heading("Status"), "gaptop 10");
		c.add(legendRow(lamp(new Color(0x4ADE80)), "Connected", null));
		c.add(legendRow(lamp(new Color(0xFBBF24)), "Idle", null));
		c.add(legendRow(lamp(new Color(0x6B7280)), "Offline or not connected", null));
		c.add(heading("Reading the map"), "gaptop 10");
		c.add(legendRow(new SunIcon(), "You, at the centre", null));
		c.add(legendRow(lamp(new Color(0xA9AFBC)), "Moons are a peer's agents", null));
		c.add(legendRow(new RingedIcon(), "Rings mark a service", null));

		c.add(new SelectableText("Click a planet to see it. Scroll to zoom, drag to pan, double-click to recentre.")
			.small().muted(), "gaptop 16, wmin 0");
	}

	private static void peer(JPanel c, SamplePeers.Peer p) {
		JPanel title = new JPanel(new MigLayout("insets 0, gap 12", "[][grow,fill]", "[][]"));
		title.setOpaque(false);
		title.add(new JLabel(new PlanetIcon(p.hue(), 40, p.ringed())), "spany 2");
		title.add(Styles.style(new JLabel(p.name()), "font: bold +3"), "wrap");
		title.add(Labels.small(p.kind().label, Styles.MUTED));
		c.add(title, "gapbottom 8");

		Color lampColour = switch (p.status()) {
			case ONLINE -> new Color(0x4ADE80);
			case IDLE -> new Color(0xFBBF24);
			default -> new Color(0x6B7280);
		};
		c.add(fact("Status", new JLabel(p.status().label, lamp(lampColour), SwingConstants.LEADING)));
		SelectableText handle = new SelectableText(p.handle()).mono();
		c.add(fact("Address", handle));
		c.add(fact("Reach", new SelectableText(p.reach())));
		c.add(fact("How you met", new SelectableText(p.since())));

		c.add(heading("What they can do"), "gaptop 12");
		for (String can : p.canDo()) {
			c.add(new JLabel(can, Lucide.icon("circle-check", 14, Theme::success), SwingConstants.LEADING));
		}
		if (!p.shared().isEmpty()) {
			c.add(heading("Shared with them"), "gaptop 12");
			JPanel chips = new JPanel(new MigLayout("insets 0, gap 6", "", ""));
			chips.setOpaque(false);
			for (String s : p.shared()) chips.add(chip(s, null));
			c.add(chips);
		}
		if (!p.moons().isEmpty()) {
			c.add(heading("Their agents"), "gaptop 12");
			for (SamplePeers.Moon m : p.moons()) {
				Color mc = m.status() == SamplePeers.Status.ONLINE ? new Color(0x4ADE80)
					: m.status() == SamplePeers.Status.IDLE ? new Color(0xFBBF24) : new Color(0x6B7280);
				c.add(legendRow(lamp(mc), m.name(), m.status().label));
			}
		}

		JPanel actions = new JPanel(new MigLayout("insets 0, gap 8", "", ""));
		actions.setOpaque(false);
		JButton message = Buttons.secondary("Message");
		JButton access = Buttons.plain("Manage access…");
		for (JButton b : new JButton[] { message, access }) {
			b.setEnabled(false);
			b.setToolTipText("Arrives with federation; this is a preview");
			actions.add(b);
		}
		c.add(actions, "gaptop 18");
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

	/** A shaded planet, as on the map, for the card's title. */
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

	/** Concentric arcs, the ring's own one picked out. */
	private record RingIcon(int ring) implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int s = getIconWidth();
			for (int i = 1; i <= 4; i++) {
				float r = s * (0.14f + 0.09f * i);
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
			Supplier<Color> a = Theme::accent;
			g2.setPaint(new java.awt.RadialGradientPaint(x + s / 2f, y + s / 2f, s / 2f, new float[] { 0f, 0.45f, 1f },
				new Color[] { Color.WHITE, a.get(), Theme.fade(a.get(), 0f) }));
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

	private static final class RingedIcon implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			new PlanetIcon(new Color(0xE8C66A), 18, true).paintIcon(c, g, x, y);
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
}
