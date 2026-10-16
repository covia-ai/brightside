package brightside.ui.peers;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.Timer;

import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Theme;

/**
 * The Peers map: your own venue as a sun, the peers it knows as planets on
 * orbits by how close they are (your devices innermost, then people you
 * trust, services you use, and peers known but not connected), and each
 * peer's agents as moons. Tilted for depth, lit from the sun, slowly
 * turning. Live links carry packets to and fro.
 *
 * <p>Hover to see a peer, click to choose one (the listener hears it, or null
 * for empty space), scroll to zoom, drag to pan, double-click to recentre.
 * Animates only while showing and not paused. The map is always a night sky,
 * whichever theme is in use, the way a star chart is.
 */
@SuppressWarnings("serial")
final class SolarMap extends JComponent {

	/** The vertical squash of an orbit: how far the plane is tilted towards you. */
	private static final double TILT = 0.58;
	/** Orbit radii as fractions of the map's half-size, innermost first. */
	private static final double[] RINGS = { 0.34, 0.54, 0.74, 0.94 };
	private static final Color SPACE_CENTRE = new Color(0x161C33);
	private static final Color SPACE_EDGE = new Color(0x06080E);
	private static final Color ONLINE = new Color(0x4ADE80);
	private static final Color IDLE = new Color(0xFBBF24);
	private static final Color OFFLINE = new Color(0x6B7280);

	private final List<SamplePeers.Peer> peers;
	private final Consumer<SamplePeers.Peer> onSelect;
	private final Timer timer;
	private final float[][] stars;
	private final List<Placed> placed = new ArrayList<>();
	private double t;
	private long lastTick;
	private boolean paused;
	private double zoom = 1;
	private double panX, panY;
	private SamplePeers.Peer hover, selected;
	private Point dragFrom;

	/** Where a peer was drawn this frame, for hit-testing. */
	private record Placed(SamplePeers.Peer peer, double x, double y, double r) {
	}

	SolarMap(List<SamplePeers.Peer> peers, Consumer<SamplePeers.Peer> onSelect) {
		this.peers = peers;
		this.onSelect = onSelect;
		setOpaque(true);
		setToolTipText("");

		// A fixed sky: positions in [0,1), size, and a twinkle offset.
		Random r = new Random(7);
		stars = new float[260][];
		for (int i = 0; i < stars.length; i++) {
			stars[i] = new float[] { r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * (r.nextInt(12) == 0 ? 1.8f : 0.9f),
				r.nextFloat() * 6.28f, 0.6f + r.nextFloat() * 1.6f };
		}

		timer = new Timer(33, e -> tick());
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
			syncTimer();
		});

		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				SamplePeers.Peer h = hit(e.getPoint());
				if (h != hover) {
					hover = h;
					setCursor(Cursor.getPredefinedCursor(h != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
					repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				dragFrom = e.getPoint();
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (dragFrom == null) return;
				panX += e.getX() - dragFrom.x;
				panY += e.getY() - dragFrom.y;
				dragFrom = e.getPoint();
				setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				dragFrom = null;
				setCursor(Cursor.getDefaultCursor());
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					resetView();
					return;
				}
				select(hit(e.getPoint()));
			}

			@Override
			public void mouseExited(MouseEvent e) {
				hover = null;
				repaint();
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				double z = Math.max(0.6, Math.min(2.4, zoom * Math.pow(1.12, -e.getPreciseWheelRotation())));
				// Zoom about the pointer, so what is under it stays put.
				double cx = getWidth() / 2.0 + panX, cy = getHeight() / 2.0 + panY;
				panX += (e.getX() - cx) * (1 - z / zoom);
				panY += (e.getY() - cy) * (1 - z / zoom);
				zoom = z;
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
	}

	void select(SamplePeers.Peer p) {
		selected = p;
		onSelect.accept(p);
		repaint();
	}

	void setPaused(boolean paused) {
		this.paused = paused;
		syncTimer();
	}

	boolean isPaused() {
		return paused;
	}

	void resetView() {
		zoom = 1;
		panX = panY = 0;
		repaint();
	}

	private void syncTimer() {
		if (isShowing() && !paused) {
			lastTick = System.nanoTime();
			timer.start();
		} else {
			timer.stop();
		}
	}

	private void tick() {
		long now = System.nanoTime();
		t += (now - lastTick) / 1e9;
		lastTick = now;
		repaint();
	}

	@Override
	public String getToolTipText(MouseEvent e) {
		SamplePeers.Peer p = hit(e.getPoint());
		if (p == null) return null;
		return p.name() + " · " + p.status().label + (p.moons().isEmpty() ? "" : " · " + plural(p.moons().size(), "agent"));
	}

	private SamplePeers.Peer hit(Point at) {
		for (int i = placed.size() - 1; i >= 0; i--) { // front-most first
			Placed p = placed.get(i);
			double slop = Math.max(p.r() * 1.25, UIScale.scale(10));
			if (at.distance(p.x(), p.y()) <= slop) return p.peer();
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Painting
	// ------------------------------------------------------------------

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			int w = getWidth(), h = getHeight();
			paintSky(g, w, h);

			double cx = w / 2.0 + panX, cy = h / 2.0 + panY;
			// Fit the widest orbit to the width, and its tilted height to the height.
			double half = Math.min(w / 2.0 - UIScale.scale(40), (h / 2.0 - UIScale.scale(46)) / TILT) * zoom;
			double unit = Math.max(UIScale.scale(10), half * 0.042); // a planet of size 1

			for (int i = 0; i < RINGS.length; i++) paintOrbit(g, cx, cy, half * RINGS[i], i);

			// Where every peer is now, with its depth: the back half of the
			// orbit plane is behind the sun, and smaller.
			List<Object[]> at = new ArrayList<>();
			for (SamplePeers.Peer p : peers) {
				double a = Math.toRadians(p.phase()) + t * 2 * Math.PI / p.period();
				double ro = half * RINGS[p.ring() - 1];
				double depth = Math.sin(a); // -1 far, +1 near
				double x = cx + Math.cos(a) * ro;
				double y = cy + depth * ro * TILT;
				double scale = 0.82 + 0.2 * (depth + 1) / 2;
				at.add(new Object[] { p, x, y, unit * p.size() * scale, depth });
			}
			at.sort(Comparator.comparingDouble(o -> (double) o[4]));

			placed.clear();
			for (Object[] o : at) paintLink(g, cx, cy, (SamplePeers.Peer) o[0], (double) o[1], (double) o[2]);
			boolean sunDrawn = false;
			for (Object[] o : at) {
				if (!sunDrawn && (double) o[4] >= 0) {
					paintSun(g, cx, cy, unit);
					sunDrawn = true;
				}
				SamplePeers.Peer p = (SamplePeers.Peer) o[0];
				double x = (double) o[1], y = (double) o[2], r = (double) o[3];
				paintPlanet(g, p, x, y, r, (double) o[4]);
				placed.add(new Placed(p, x, y, r));
			}
			if (!sunDrawn) paintSun(g, cx, cy, unit);
			for (Placed p : placed) paintLabel(g, p);
		} finally {
			g.dispose();
		}
	}

	private void paintSky(Graphics2D g, int w, int h) {
		float rad = (float) Math.max(w, h) * 0.75f;
		g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0 + panX * 0.3, h / 2.0 + panY * 0.3), rad,
			new float[] { 0f, 1f }, new Color[] { SPACE_CENTRE, SPACE_EDGE }));
		g.fillRect(0, 0, w, h);
		// Two faint nebulae in the accent's family.
		Color accent = Theme.accent();
		nebula(g, w * 0.22 + panX * 0.2, h * 0.28 + panY * 0.2, Math.max(w, h) * 0.38, accent, 0.10f);
		nebula(g, w * 0.82 + panX * 0.2, h * 0.78 + panY * 0.2, Math.max(w, h) * 0.30, new Color(0x2DD4BF), 0.07f);
		// Stars drift a little with the pan, for parallax, and twinkle.
		for (float[] s : stars) {
			double x = ((s[0] * w + panX * 0.15) % w + w) % w;
			double y = ((s[1] * h + panY * 0.15) % h + h) % h;
			float tw = 0.55f + 0.45f * (float) Math.sin(t * s[4] + s[3]);
			float size = UIScale.scale(s[2]);
			g.setColor(new Color(1f, 1f, 1f, Math.min(1f, 0.25f + 0.6f * tw * (s[2] / 2f))));
			g.fill(new Ellipse2D.Double(x - size / 2, y - size / 2, size, size));
		}
	}

	private static void nebula(Graphics2D g, double x, double y, double r, Color c, float alpha) {
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) r, new float[] { 0f, 1f },
			new Color[] { fade(c, alpha), fade(c, 0f) }));
		g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
	}

	private void paintOrbit(Graphics2D g, double cx, double cy, double r, int ring) {
		boolean lit = (hover != null && hover.ring() == ring + 1) || (selected != null && selected.ring() == ring + 1);
		float dash = UIScale.scale(3f);
		g.setStroke(new BasicStroke(UIScale.scale(lit ? 1.4f : 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
			ring == RINGS.length - 1 ? new float[] { dash, dash * 2 } : null, (float) (t * 4)));
		g.setColor(new Color(1f, 1f, 1f, lit ? 0.32f : 0.11f));
		g.draw(new Ellipse2D.Double(cx - r, cy - r * TILT, 2 * r, 2 * r * TILT));
		// The ring's name, centred just below the near edge of its orbit, where
		// the orbits are furthest apart; planets pass over it.
		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(9.5f)));
		String name = SamplePeers.RINGS.get(ring).toUpperCase();
		g.setColor(new Color(1f, 1f, 1f, lit ? 0.7f : 0.32f));
		float tracking = UIScale.scale(1.2f);
		FontMetrics fm = g.getFontMetrics();
		float width = fm.stringWidth(name) + tracking * (name.length() - 1);
		drawTracked(g, name, (float) (cx - width / 2), (float) (cy + r * TILT + UIScale.scale(13)), tracking);
	}

	private void paintSun(Graphics2D g, double cx, double cy, double unit) {
		double r = unit * 2.1;
		Color accent = Theme.accent();
		double pulse = 1 + 0.04 * Math.sin(t * 1.3);
		double corona = r * 4.2 * pulse;
		g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) corona,
			new float[] { 0f, 0.22f, 0.5f, 1f },
			new Color[] { fade(Color.WHITE, 0.55f), fade(accent, 0.35f), fade(accent, 0.08f), fade(accent, 0f) }));
		g.fill(new Ellipse2D.Double(cx - corona, cy - corona, 2 * corona, 2 * corona));
		g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - r * 0.25, cy - r * 0.3), (float) (r * 1.3),
			new float[] { 0f, 0.45f, 1f }, new Color[] { Color.WHITE, Theme.blend(accent, Color.WHITE, 0.35f), accent }));
		g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));

		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(12f)));
		centred(g, "You", cx, cy + r + UIScale.scale(16), new Color(1f, 1f, 1f, 0.92f));
	}

	/** A faint line from you to a connected peer, with packets travelling it. */
	private void paintLink(Graphics2D g, double cx, double cy, SamplePeers.Peer p, double x, double y) {
		if (p.status() == SamplePeers.Status.KNOWN || p.status() == SamplePeers.Status.OFFLINE) return;
		boolean live = p.status() == SamplePeers.Status.ONLINE;
		boolean focus = p == hover || p == selected;
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.3f : 0.9f)));
		g.setColor(fade(p.hue(), focus ? 0.45f : live ? 0.16f : 0.08f));
		g.draw(new Line2D.Double(cx, cy, x, y));
		if (!live) return;
		double speed = 0.18 + 0.05 * p.ring();
		for (int k = 0; k < 2; k++) {
			double f = (t * speed + p.phase() / 360.0 + k * 0.5) % 1.0;
			if (k == 1) f = 1 - f; // one each way
			double px = cx + (x - cx) * f, py = cy + (y - cy) * f;
			double pr = UIScale.scale(2.2f);
			float a = (float) Math.sin(f * Math.PI);
			g.setColor(fade(Theme.blend(p.hue(), Color.WHITE, 0.5f), 0.85f * a));
			g.fill(new Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr));
		}
	}

	private void paintPlanet(Graphics2D g, SamplePeers.Peer p, double x, double y, double r, double depth) {
		boolean known = p.status() == SamplePeers.Status.KNOWN;
		boolean offline = p.status() == SamplePeers.Status.OFFLINE;
		Color hue = (offline || known) ? Theme.blend(p.hue(), new Color(0x8A93A6), 0.6f) : p.hue();
		Composite was = g.getComposite();
		if (known) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));

		// A soft glow, brighter when hovered or chosen.
		boolean focus = p == hover || p == selected;
		double glow = r * (focus ? 2.6 : 1.9);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) glow, new float[] { 0f, 1f },
			new Color[] { fade(hue, focus ? 0.45f : 0.22f), fade(hue, 0f) }));
		g.fill(new Ellipse2D.Double(x - glow, y - glow, 2 * glow, 2 * glow));

		// Moons behind the planet first.
		List<double[]> moons = moons(p, x, y, r);
		for (double[] m : moons) if (m[3] < 0) paintMoon(g, p, m);

		Ellipse2D body = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
		if (p.ringed()) paintRings(g, x, y, r, hue, true);

		// Lit from the sun: the bright side faces the centre of the map.
		double cx = getWidth() / 2.0 + panX, cy = getHeight() / 2.0 + panY;
		double dx = cx - x, dy = cy - y, d = Math.max(1, Math.hypot(dx, dy));
		Point2D focusPt = new Point2D.Double(x + dx / d * r * 0.45, y + dy / d * r * 0.45);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 1.25), focusPt,
			new float[] { 0f, 0.5f, 1f },
			new Color[] { Theme.blend(hue, Color.WHITE, 0.45f), hue, Theme.blend(hue, Color.BLACK, 0.72f) },
			MultipleGradientPaint.CycleMethod.NO_CYCLE));
		g.fill(body);
		// A thin atmosphere on the lit limb.
		g.setStroke(new BasicStroke(UIScale.scale(1f)));
		g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.6f), 0.35f));
		g.draw(body);

		if (p.ringed()) paintRings(g, x, y, r, hue, false);
		for (double[] m : moons) if (m[3] >= 0) paintMoon(g, p, m);
		g.setComposite(was);

		// Chosen: a slowly breathing ring.
		if (p == selected) {
			double sr = r * (1.55 + 0.08 * Math.sin(t * 3));
			g.setStroke(new BasicStroke(UIScale.scale(1.6f)));
			g.setColor(fade(Color.WHITE, 0.75f));
			g.draw(new Ellipse2D.Double(x - sr, y - sr, 2 * sr, 2 * sr));
		}

		// Status, as a lamp on the planet's shoulder.
		double lr = Math.max(UIScale.scale(3f), r * 0.22);
		double lx = x + r * 0.72, ly = y - r * 0.72;
		g.setColor(SPACE_EDGE);
		g.fill(new Ellipse2D.Double(lx - lr - 1.5, ly - lr - 1.5, 2 * lr + 3, 2 * lr + 3));
		g.setColor(switch (p.status()) {
			case ONLINE -> ONLINE;
			case IDLE -> IDLE;
			default -> OFFLINE;
		});
		g.fill(new Ellipse2D.Double(lx - lr, ly - lr, 2 * lr, 2 * lr));
	}

	/** A tilted ring system: the far half behind the planet, the near half in front. */
	private static void paintRings(Graphics2D g, double x, double y, double r, Color hue, boolean back) {
		double rw = r * 2.15, rh = r * 0.62;
		Shape clip = g.getClip();
		g.clip(new java.awt.geom.Rectangle2D.Double(x - rw - 2, back ? y - rh - 2 : y, 2 * rw + 4, rh + 2));
		for (int i = 0; i < 3; i++) {
			double k = 1 - i * 0.14;
			g.setStroke(new BasicStroke((float) (r * (0.16 - i * 0.03))));
			g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.3f), back ? 0.28f : 0.6f - i * 0.12f));
			g.draw(new Arc2D.Double(x - rw * k, y - rh * k, 2 * rw * k, 2 * rh * k, 0, 360, Arc2D.OPEN));
		}
		g.setClip(clip);
	}

	/** Each moon now: {x, y, radius, depth}. */
	private List<double[]> moons(SamplePeers.Peer p, double x, double y, double r) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < p.moons().size(); i++) {
			double orbit = r * (1.9 + i * 0.62);
			double a = t * 2 * Math.PI / (7 + i * 4) + i * 2.1 + p.phase();
			double depth = Math.sin(a);
			out.add(new double[] { x + Math.cos(a) * orbit, y + depth * orbit * TILT, Math.max(UIScale.scale(3f), r * 0.32),
				depth, i });
		}
		return out;
	}

	private static void paintMoon(Graphics2D g, SamplePeers.Peer p, double[] m) {
		SamplePeers.Moon moon = p.moons().get((int) m[4]);
		Color c = moon.status() == SamplePeers.Status.ONLINE ? new Color(0xE6E9F0)
			: moon.status() == SamplePeers.Status.IDLE ? new Color(0xB9BECB) : new Color(0x6B7280);
		double r = m[2];
		g.setPaint(new RadialGradientPaint(new Point2D.Double(m[0] - r * 0.3, m[1] - r * 0.3), (float) (r * 1.4),
			new float[] { 0f, 1f }, new Color[] { c, Theme.blend(c, Color.BLACK, 0.6f) }));
		g.fill(new Ellipse2D.Double(m[0] - r, m[1] - r, 2 * r, 2 * r));
	}

	private void paintLabel(Graphics2D g, Placed pl) {
		SamplePeers.Peer p = pl.peer();
		boolean focus = p == hover || p == selected;
		g.setFont(getFont().deriveFont(focus ? Font.BOLD : Font.PLAIN, UIScale.scale(focus ? 12f : 11f)));
		double y = pl.y() + pl.r() + UIScale.scale(15) + (p.moons().isEmpty() ? 0 : pl.r() * 0.5);
		centred(g, p.name(), pl.x(), y, new Color(1f, 1f, 1f, focus ? 0.98f : p.status() == SamplePeers.Status.KNOWN ? 0.5f : 0.82f));
		if (focus) {
			g.setFont(getFont().deriveFont(UIScale.scale(10f)));
			centred(g, p.status().label, pl.x(), y + UIScale.scale(13), new Color(1f, 1f, 1f, 0.55f));
		}
	}

	private static void centred(Graphics2D g, String text, double x, double y, Color colour) {
		FontMetrics fm = g.getFontMetrics();
		float tx = (float) (x - fm.stringWidth(text) / 2.0);
		g.setColor(new Color(0, 0, 0, Math.min(160, colour.getAlpha())));
		g.drawString(text, tx + 1, (float) y + 1);
		g.setColor(colour);
		g.drawString(text, tx, (float) y);
	}

	private static void drawTracked(Graphics2D g, String text, float x, float y, float tracking) {
		FontMetrics fm = g.getFontMetrics();
		for (char c : text.toCharArray()) {
			g.drawString(String.valueOf(c), x, y);
			x += fm.charWidth(c) + tracking;
		}
	}

	private static Color fade(Color c, float alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(alpha * 255))));
	}

	private static String plural(int n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}
}
