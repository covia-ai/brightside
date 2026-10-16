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
import java.awt.geom.Rectangle2D;
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
 * The Peers map, from your own system out to the universe of venues. Your
 * venue is a sun; the peers it knows are planets on orbits by closeness
 * (your devices innermost, then people you trust, services you use, and peers
 * known but not connected), each peer's agents its moons. Zoom out and other
 * venues appear as star systems of their own, the ones you know linked to
 * yours.
 *
 * <p>Everything sits on one tilted plane in world units, where your outermost
 * orbit is about 1, so a single zoom covers a planet's moons and the whole
 * universe. What is drawn follows what there is room for: ring names, then
 * planet names and moons, then a system's planets, fade out as they shrink,
 * and come back as you zoom in. Whatever is under the pointer is always
 * named.
 *
 * <p>Hover to see, click to choose (the listener hears a {@link SamplePeers.Peer},
 * a {@link SamplePeers.StarSystem}, or null), wheel to zoom about the pointer,
 * drag to pan, double-click to come home. It animates only while showing and
 * not paused. The map is always a night sky, whichever theme is in use.
 */
@SuppressWarnings("serial")
final class SolarMap extends JComponent {

	/** The vertical squash of the plane: how far it is tilted towards you. */
	private static final double TILT = 0.58;
	/** Your orbits, in world units, innermost first. */
	private static final double[] RINGS = { 0.34, 0.54, 0.74, 0.94 };
	/** A planet of size 1, and your sun, in world units. */
	private static final double PLANET = 0.042, SUN = 0.088;
	/** Zoom limits: from the whole universe and some, to a planet's moons. */
	static final double MIN_ZOOM = 0.035, MAX_ZOOM = 3.2;
	/** How much of the universe a "whole universe" view takes in, in world units. */
	private static final double UNIVERSE = 17.5;
	private static final Color SPACE_CENTRE = new Color(0x161C33);
	private static final Color SPACE_EDGE = new Color(0x06080E);
	static final Color ONLINE = new Color(0x4ADE80);
	static final Color IDLE = new Color(0xFBBF24);
	static final Color OFFLINE = new Color(0x6B7280);

	private final List<SamplePeers.Peer> peers;
	private final List<SamplePeers.StarSystem> systems;
	private final Consumer<Object> onSelect;
	private final Runnable onView;
	private final Timer timer;
	private final Timer glide;
	private final float[][] stars;
	private final List<Placed> placed = new ArrayList<>();
	private double t;
	private long lastTick;
	private boolean paused;
	private double zoom = 1, panX, panY;
	private double toZoom = 1, toPanX, toPanY;
	private Object hover, selected;
	private Point dragFrom;

	/** Something drawn this frame, for hit-testing: a peer or a system, its centre and radius on screen. */
	private record Placed(Object item, double x, double y, double r) {
	}

	/** @param onView told whenever the zoom changes, so the header can name the view */
	SolarMap(List<SamplePeers.Peer> peers, List<SamplePeers.StarSystem> systems, Consumer<Object> onSelect,
			Runnable onView) {
		this.peers = peers;
		this.systems = systems;
		this.onSelect = onSelect;
		this.onView = onView;
		setOpaque(true);
		setToolTipText("");

		// A fixed sky: positions in [0,1), size, twinkle offset and speed.
		Random r = new Random(7);
		stars = new float[300][];
		for (int i = 0; i < stars.length; i++) {
			stars[i] = new float[] { r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * (r.nextInt(12) == 0 ? 1.8f : 0.9f),
				r.nextFloat() * 6.28f, 0.6f + r.nextFloat() * 1.6f };
		}

		timer = new Timer(33, e -> tick());
		glide = new Timer(16, e -> glideStep());
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) syncTimer();
		});

		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Object h = hit(e.getPoint());
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
				glide.stop();
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
					home();
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
				zoomAbout(e.getX(), e.getY(), Math.pow(1.15, -e.getPreciseWheelRotation()));
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
	}

	// ------------------------------------------------------------------
	// View
	// ------------------------------------------------------------------

	void select(Object item) {
		selected = item;
		onSelect.accept(item);
		repaint();
	}

	void setPaused(boolean paused) {
		this.paused = paused;
		syncTimer();
	}

	boolean isPaused() {
		return paused;
	}

	double zoom() {
		return zoom;
	}

	/** Scales the view by {@code factor} about a point on screen, so what is under it stays put. */
	void zoomAbout(double x, double y, double factor) {
		glide.stop();
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
		double cx = getWidth() / 2.0 + panX, cy = getHeight() / 2.0 + panY;
		panX += (x - cx) * (1 - z / zoom);
		panY += (y - cy) * (1 - z / zoom);
		zoom = z;
		onView.run();
		repaint();
	}

	/** Zooms in or out by a step about the centre, as the header's buttons do. */
	void zoomStep(boolean in) {
		double f = in ? 1.6 : 1 / 1.6;
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * f));
		glideTo(z, panX * z / zoom, panY * z / zoom);
	}

	/** Back to your own system, filling the map. */
	void home() {
		glideTo(1, 0, 0);
	}

	/** Out to the whole universe of venues. */
	void universe() {
		glideTo(1 / UNIVERSE, 0, 0);
	}

	private void glideTo(double z, double px, double py) {
		toZoom = z;
		toPanX = px;
		toPanY = py;
		if (!isShowing()) {
			zoom = z;
			panX = px;
			panY = py;
			onView.run();
			repaint();
			return;
		}
		glide.start();
	}

	/** One step of a glide: zoom eases in log space, so a long zoom feels even. */
	private void glideStep() {
		double lz = Math.log(zoom), lt = Math.log(toZoom);
		zoom = Math.exp(lz + (lt - lz) * 0.18);
		panX += (toPanX - panX) * 0.18;
		panY += (toPanY - panY) * 0.18;
		if (Math.abs(lt - lz) < 0.002 && Math.abs(toPanX - panX) < 0.5 && Math.abs(toPanY - panY) < 0.5) {
			zoom = toZoom;
			panX = toPanX;
			panY = toPanY;
			glide.stop();
		}
		onView.run();
		repaint();
	}

	/** What the view is showing, for the header. */
	String viewName() {
		if (zoom >= 0.55) return "Your system";
		if (zoom >= 0.12) return "Neighbourhood";
		return "Universe";
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
		Object o = hit(e.getPoint());
		if (o instanceof SamplePeers.Peer p) {
			return p.name() + " · " + p.status().label + (p.moons().isEmpty() ? "" : " · " + plural(p.moons().size(), "agent"));
		}
		if (o instanceof SamplePeers.StarSystem s) {
			return s.known() ? s.name() + " · " + plural(s.planets(), "peer") : "A venue you don't know";
		}
		return null;
	}

	private Object hit(Point at) {
		for (int i = placed.size() - 1; i >= 0; i--) { // front-most first
			Placed p = placed.get(i);
			double slop = Math.max(p.r() * 1.25, UIScale.scale(9));
			if (at.distance(p.x(), p.y()) <= slop) return p.item();
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Painting
	// ------------------------------------------------------------------

	/** Pixels per world unit at the current zoom. */
	private double scale() {
		int w = getWidth(), h = getHeight();
		return Math.max(1, Math.min(w / 2.0 - UIScale.scale(40), (h / 2.0 - UIScale.scale(46)) / TILT)) * zoom;
	}

	/** A world position on the tilted plane, on screen. */
	private Point2D.Double screen(double wx, double wz) {
		double s = scale();
		return new Point2D.Double(getWidth() / 2.0 + panX + wx * s, getHeight() / 2.0 + panY + wz * s * TILT);
	}

	/** 0 below {@code from} pixels, 1 above {@code to}, smooth between: how much of a detail to show. */
	private static float fadeIn(double px, double from, double to) {
		double f = (px - from) / (to - from);
		f = Math.max(0, Math.min(1, f));
		return (float) (f * f * (3 - 2 * f));
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			placed.clear();
			paintSky(g, getWidth(), getHeight());

			double s = scale();
			Point2D.Double you = screen(0, 0);
			// Far out, the links to the venues you know come into view.
			float links = 1 - fadeIn(s, 90, 220);
			if (links > 0) for (SamplePeers.StarSystem sys : systems) paintSystemLink(g, you, sys, links);
			for (SamplePeers.StarSystem sys : systems) paintSystem(g, sys, s);
			paintYourSystem(g, you, s);
			for (Placed p : placed) paintLabel(g, p, s);
		} finally {
			g.dispose();
		}
	}

	private void paintSky(Graphics2D g, int w, int h) {
		float rad = (float) Math.max(w, h) * 0.75f;
		g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0 + panX * 0.05, h / 2.0 + panY * 0.05), rad,
			new float[] { 0f, 1f }, new Color[] { SPACE_CENTRE, SPACE_EDGE }));
		g.fillRect(0, 0, w, h);
		Color accent = Theme.accent();
		nebula(g, w * 0.22 + panX * 0.08, h * 0.28 + panY * 0.08, Math.max(w, h) * 0.38, accent, 0.10f);
		nebula(g, w * 0.82 + panX * 0.08, h * 0.78 + panY * 0.08, Math.max(w, h) * 0.30, new Color(0x2DD4BF), 0.07f);
		// The far sky drifts a little with the pan, for parallax, and twinkles.
		for (float[] st : stars) {
			double x = ((st[0] * w + panX * 0.06) % w + w) % w;
			double y = ((st[1] * h + panY * 0.06) % h + h) % h;
			float tw = 0.55f + 0.45f * (float) Math.sin(t * st[4] + st[3]);
			float size = UIScale.scale(st[2]);
			g.setColor(new Color(1f, 1f, 1f, Math.min(1f, 0.25f + 0.6f * tw * (st[2] / 2f))));
			g.fill(new Ellipse2D.Double(x - size / 2, y - size / 2, size, size));
		}
	}

	private static void nebula(Graphics2D g, double x, double y, double r, Color c, float alpha) {
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) r, new float[] { 0f, 1f },
			new Color[] { fade(c, alpha), fade(c, 0f) }));
		g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
	}

	// ------------------------------------------------------------------
	// Your system
	// ------------------------------------------------------------------

	private void paintYourSystem(Graphics2D g, Point2D.Double you, double s) {
		double cx = you.x, cy = you.y;
		float orbits = 0.25f + 0.75f * fadeIn(s, 30, 140);
		for (int i = 0; i < RINGS.length; i++) paintOrbit(g, cx, cy, s * RINGS[i], i, orbits, fadeIn(s, 170, 240));

		double unit = Math.max(UIScale.scale(1.6f), s * PLANET);
		List<Object[]> at = new ArrayList<>();
		for (SamplePeers.Peer p : peers) {
			double a = Math.toRadians(p.phase()) + t * 2 * Math.PI / p.period();
			double ro = RINGS[p.ring() - 1];
			double depth = Math.sin(a); // -1 far, +1 near
			Point2D.Double xy = screen(Math.cos(a) * ro, depth * ro);
			double r = unit * p.size() * (0.82 + 0.2 * (depth + 1) / 2);
			at.add(new Object[] { p, xy.x, xy.y, r, depth });
		}
		at.sort(Comparator.comparingDouble(o -> (double) o[4]));

		for (Object[] o : at) paintLink(g, cx, cy, (SamplePeers.Peer) o[0], (double) o[1], (double) o[2], s);
		boolean sunDrawn = false;
		for (Object[] o : at) {
			if (!sunDrawn && (double) o[4] >= 0) {
				paintSun(g, cx, cy, s);
				sunDrawn = true;
			}
			SamplePeers.Peer p = (SamplePeers.Peer) o[0];
			double x = (double) o[1], y = (double) o[2], r = (double) o[3];
			paintPlanet(g, p, x, y, r, cx, cy);
			placed.add(new Placed(p, x, y, r));
		}
		if (!sunDrawn) paintSun(g, cx, cy, s);
	}

	private void paintOrbit(Graphics2D g, double cx, double cy, double r, int ring, float alpha, float names) {
		boolean lit = ringOf(hover) == ring + 1 || ringOf(selected) == ring + 1;
		float dash = UIScale.scale(3f);
		g.setStroke(new BasicStroke(UIScale.scale(lit ? 1.4f : 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
			ring == RINGS.length - 1 ? new float[] { dash, dash * 2 } : null, (float) (t * 4)));
		g.setColor(new Color(1f, 1f, 1f, alpha * (lit ? 0.32f : 0.11f)));
		g.draw(new Ellipse2D.Double(cx - r, cy - r * TILT, 2 * r, 2 * r * TILT));
		if (names <= 0) return;
		// The ring's name, centred below its near edge, where orbits are furthest apart.
		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(9.5f)));
		String name = SamplePeers.RINGS.get(ring).toUpperCase();
		float tracking = UIScale.scale(1.2f);
		FontMetrics fm = g.getFontMetrics();
		float width = fm.stringWidth(name) + tracking * (name.length() - 1);
		g.setColor(new Color(1f, 1f, 1f, names * (lit ? 0.7f : 0.32f)));
		drawTracked(g, name, (float) (cx - width / 2), (float) (cy + r * TILT + UIScale.scale(13)), tracking);
	}

	private static int ringOf(Object o) {
		return (o instanceof SamplePeers.Peer p) ? p.ring() : 0;
	}

	private void paintSun(Graphics2D g, double cx, double cy, double s) {
		double r = Math.max(UIScale.scale(4f), s * SUN);
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

		// Far out, a marker says where you are among everything else.
		float marker = 1 - fadeIn(s, 60, 160);
		if (marker > 0) {
			double mr = UIScale.scale(14f) * (1 + 0.12 * Math.sin(t * 2.2));
			g.setStroke(new BasicStroke(UIScale.scale(1.4f)));
			g.setColor(fade(accent, 0.75f * marker));
			g.draw(new Ellipse2D.Double(cx - mr, cy - mr, 2 * mr, 2 * mr));
		}

		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(12f)));
		String you = marker > 0.5 ? "You are here" : "You";
		centred(g, you, cx, cy + Math.max(r, marker > 0 ? UIScale.scale(14f) : 0) + UIScale.scale(16),
			new Color(1f, 1f, 1f, 0.92f));
	}

	/** A faint line from you to a connected peer, with packets travelling it. */
	private void paintLink(Graphics2D g, double cx, double cy, SamplePeers.Peer p, double x, double y, double s) {
		if (p.status() == SamplePeers.Status.KNOWN || p.status() == SamplePeers.Status.OFFLINE) return;
		float a = fadeIn(s, 60, 140);
		if (a <= 0) return;
		boolean live = p.status() == SamplePeers.Status.ONLINE;
		boolean focus = p == hover || p == selected;
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.3f : 0.9f)));
		g.setColor(fade(p.hue(), a * (focus ? 0.45f : live ? 0.16f : 0.08f)));
		g.draw(new Line2D.Double(cx, cy, x, y));
		if (!live) return;
		packets(g, cx, cy, x, y, p.hue(), 0.18 + 0.05 * p.ring(), p.phase() / 360.0, a);
	}

	private void packets(Graphics2D g, double x0, double y0, double x1, double y1, Color hue, double speed,
			double offset, float alpha) {
		for (int k = 0; k < 2; k++) {
			double f = ((t * speed + offset + k * 0.5) % 1.0 + 1) % 1.0;
			if (k == 1) f = 1 - f; // one each way
			double px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
			double pr = UIScale.scale(2.2f);
			float a = (float) Math.sin(f * Math.PI);
			g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.5f), 0.85f * a * alpha));
			g.fill(new Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr));
		}
	}

	private void paintPlanet(Graphics2D g, SamplePeers.Peer p, double x, double y, double r, double cx, double cy) {
		boolean known = p.status() == SamplePeers.Status.KNOWN;
		boolean offline = p.status() == SamplePeers.Status.OFFLINE;
		Color hue = (offline || known) ? Theme.blend(p.hue(), new Color(0x8A93A6), 0.6f) : p.hue();
		Composite was = g.getComposite();
		if (known) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		boolean focus = p == hover || p == selected;

		// Too small to model: a lit dot that still glows when pointed at.
		if (r < UIScale.scale(3.5f)) {
			double gr = r * (focus ? 4 : 2.6);
			g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) gr, new float[] { 0f, 1f },
				new Color[] { fade(hue, focus ? 0.7f : 0.4f), fade(hue, 0f) }));
			g.fill(new Ellipse2D.Double(x - gr, y - gr, 2 * gr, 2 * gr));
			g.setColor(Theme.blend(hue, Color.WHITE, 0.3f));
			g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
			g.setComposite(was);
			if (p == selected) ring(g, x, y, Math.max(r * 2.2, UIScale.scale(7f)));
			return;
		}

		double glow = r * (focus ? 2.6 : 1.9);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) glow, new float[] { 0f, 1f },
			new Color[] { fade(hue, focus ? 0.45f : 0.22f), fade(hue, 0f) }));
		g.fill(new Ellipse2D.Double(x - glow, y - glow, 2 * glow, 2 * glow));

		// Moons only once there is room for them; behind the planet first.
		float moonsShown = fadeIn(r, UIScale.scale(5f), UIScale.scale(8f));
		List<double[]> moons = moonsShown > 0 ? moons(p, x, y, r) : List.of();
		for (double[] m : moons) if (m[3] < 0) paintMoon(g, p, m, moonsShown);

		Ellipse2D body = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
		if (p.ringed()) paintRings(g, x, y, r, hue, true);

		// Lit from the sun: the bright side faces your venue.
		double dx = cx - x, dy = cy - y, d = Math.max(1, Math.hypot(dx, dy));
		Point2D focusPt = new Point2D.Double(x + dx / d * r * 0.45, y + dy / d * r * 0.45);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 1.25), focusPt,
			new float[] { 0f, 0.5f, 1f },
			new Color[] { Theme.blend(hue, Color.WHITE, 0.45f), hue, Theme.blend(hue, Color.BLACK, 0.72f) },
			MultipleGradientPaint.CycleMethod.NO_CYCLE));
		g.fill(body);
		g.setStroke(new BasicStroke(UIScale.scale(1f)));
		g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.6f), 0.35f));
		g.draw(body);

		if (p.ringed()) paintRings(g, x, y, r, hue, false);
		for (double[] m : moons) if (m[3] >= 0) paintMoon(g, p, m, moonsShown);
		g.setComposite(was);

		if (p == selected) ring(g, x, y, r * (1.55 + 0.08 * Math.sin(t * 3)));

		// Status, as a lamp on the planet's shoulder, once there is room for it.
		if (r >= UIScale.scale(6f)) {
			double lr = Math.max(UIScale.scale(3f), r * 0.22);
			double lx = x + r * 0.72, ly = y - r * 0.72;
			g.setColor(SPACE_EDGE);
			g.fill(new Ellipse2D.Double(lx - lr - 1.5, ly - lr - 1.5, 2 * lr + 3, 2 * lr + 3));
			g.setColor(lamp(p.status()));
			g.fill(new Ellipse2D.Double(lx - lr, ly - lr, 2 * lr, 2 * lr));
		}
	}

	private void ring(Graphics2D g, double x, double y, double r) {
		g.setStroke(new BasicStroke(UIScale.scale(1.6f)));
		g.setColor(fade(Color.WHITE, 0.75f));
		g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
	}

	static Color lamp(SamplePeers.Status s) {
		return switch (s) {
			case ONLINE -> ONLINE;
			case IDLE -> IDLE;
			default -> OFFLINE;
		};
	}

	/** A tilted ring system: the far half behind the planet, the near half in front. */
	private static void paintRings(Graphics2D g, double x, double y, double r, Color hue, boolean back) {
		double rw = r * 2.15, rh = r * 0.62;
		Shape clip = g.getClip();
		g.clip(new Rectangle2D.Double(x - rw - 2, back ? y - rh - 2 : y, 2 * rw + 4, rh + 2));
		for (int i = 0; i < 3; i++) {
			double k = 1 - i * 0.14;
			g.setStroke(new BasicStroke((float) (r * (0.16 - i * 0.03))));
			g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.3f), back ? 0.28f : 0.6f - i * 0.12f));
			g.draw(new Arc2D.Double(x - rw * k, y - rh * k, 2 * rw * k, 2 * rh * k, 0, 360, Arc2D.OPEN));
		}
		g.setClip(clip);
	}

	/** Each moon now: {x, y, radius, depth, index}. */
	private List<double[]> moons(SamplePeers.Peer p, double x, double y, double r) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < p.moons().size(); i++) {
			double orbit = r * (1.9 + i * 0.62);
			double a = t * 2 * Math.PI / (7 + i * 4) + i * 2.1 + p.phase();
			double depth = Math.sin(a);
			out.add(new double[] { x + Math.cos(a) * orbit, y + depth * orbit * TILT, Math.max(UIScale.scale(2.4f), r * 0.32),
				depth, i });
		}
		return out;
	}

	private static void paintMoon(Graphics2D g, SamplePeers.Peer p, double[] m, float alpha) {
		SamplePeers.Moon moon = p.moons().get((int) m[4]);
		Color c = moon.status() == SamplePeers.Status.ONLINE ? new Color(0xE6E9F0)
			: moon.status() == SamplePeers.Status.IDLE ? new Color(0xB9BECB) : new Color(0x6B7280);
		double r = m[2];
		g.setPaint(new RadialGradientPaint(new Point2D.Double(m[0] - r * 0.3, m[1] - r * 0.3), (float) (r * 1.4),
			new float[] { 0f, 1f }, new Color[] { fade(c, alpha), fade(Theme.blend(c, Color.BLACK, 0.6f), alpha) }));
		g.fill(new Ellipse2D.Double(m[0] - r, m[1] - r, 2 * r, 2 * r));
	}

	// ------------------------------------------------------------------
	// Other systems
	// ------------------------------------------------------------------

	private void paintSystemLink(Graphics2D g, Point2D.Double you, SamplePeers.StarSystem sys, float alpha) {
		if (!sys.linked()) return;
		Point2D.Double c = screen(sys.x(), sys.z());
		boolean focus = sys == hover || sys == selected;
		float dash = UIScale.scale(4f);
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.4f : 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
			sys.status() == SamplePeers.Status.ONLINE ? null : new float[] { dash, dash }, 0));
		g.setColor(fade(sys.sun(), alpha * (focus ? 0.6f : 0.28f)));
		g.draw(new Line2D.Double(you.x, you.y, c.x, c.y));
		if (sys.status() == SamplePeers.Status.ONLINE) {
			packets(g, you.x, you.y, c.x, c.y, sys.sun(), 0.12, sys.x() * 0.13, alpha);
		}
	}

	private void paintSystem(Graphics2D g, SamplePeers.StarSystem sys, double s) {
		Point2D.Double c = screen(sys.x(), sys.z());
		double rad = sys.radius() * s; // the system's size on screen
		double margin = Math.max(rad, UIScale.scale(20f)) * 2;
		if (c.x < -margin || c.y < -margin || c.x > getWidth() + margin || c.y > getHeight() + margin) {
			return; // off screen: nothing to draw or hit
		}
		boolean focus = sys == hover || sys == selected;
		float dim = sys.known() ? 1f : 0.6f;
		Color sun = sys.status() == SamplePeers.Status.OFFLINE ? Theme.blend(sys.sun(), new Color(0x8A93A6), 0.6f) : sys.sun();

		// Orbits and planets once the system is big enough to have any.
		float detail = fadeIn(rad, UIScale.scale(16f), UIScale.scale(40f));
		if (detail > 0) {
			for (int k = 0; k < sys.planets(); k++) {
				double ro = rad * (0.28 + 0.72 * (k + 1) / sys.planets());
				g.setStroke(new BasicStroke(UIScale.scale(0.8f)));
				g.setColor(new Color(1f, 1f, 1f, 0.07f * detail * dim));
				g.draw(new Ellipse2D.Double(c.x - ro, c.y - ro * TILT, 2 * ro, 2 * ro * TILT));
				double a = t * 2 * Math.PI / (40 + k * 23) + k * 1.7 + sys.x();
				double px = c.x + Math.cos(a) * ro, py = c.y + Math.sin(a) * ro * TILT;
				double pr = Math.max(UIScale.scale(1.4f), rad * 0.035);
				float h = (float) (((sys.x() * 0.07 + k * 0.13) % 1.0 + 1) % 1.0);
				Color hue = Color.getHSBColor(h, 0.45f, 0.95f);
				g.setColor(fade(hue, 0.85f * detail * dim));
				g.fill(new Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr));
			}
		}

		// The star itself.
		double r = Math.max(UIScale.scale(2.6f), rad * 0.12);
		double corona = r * (focus ? 6 : 4.2);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(c.x, c.y), (float) corona,
			new float[] { 0f, 0.25f, 1f },
			new Color[] { fade(Color.WHITE, 0.5f * dim), fade(sun, 0.3f * dim), fade(sun, 0f) }));
		g.fill(new Ellipse2D.Double(c.x - corona, c.y - corona, 2 * corona, 2 * corona));
		g.setColor(fade(Theme.blend(sun, Color.WHITE, 0.4f), dim));
		g.fill(new Ellipse2D.Double(c.x - r, c.y - r, 2 * r, 2 * r));
		if (sys == selected) ring(g, c.x, c.y, Math.max(r * 2.4, UIScale.scale(9f)));
		placed.add(new Placed(sys, c.x, c.y, Math.max(r * 1.6, rad * 0.25)));
	}

	// ------------------------------------------------------------------
	// Labels
	// ------------------------------------------------------------------

	/**
	 * Names, drawn last so nothing covers them: a planet's once it is big
	 * enough to read beside, a known system's once there is room among its
	 * neighbours, and whatever is pointed at or chosen, whatever the zoom.
	 */
	private void paintLabel(Graphics2D g, Placed pl, double s) {
		boolean focus = pl.item() == hover || pl.item() == selected;
		String name, sub = null;
		float alpha;
		double y;
		if (pl.item() instanceof SamplePeers.Peer p) {
			alpha = focus ? 1 : fadeIn(pl.r(), UIScale.scale(5f), UIScale.scale(8f));
			name = p.name();
			if (focus) sub = p.status().label;
			y = pl.y() + pl.r() + UIScale.scale(15) + (pl.r() >= UIScale.scale(5f) && !p.moons().isEmpty() ? pl.r() * 0.5 : 0);
			if (p.status() == SamplePeers.Status.KNOWN && !focus) alpha *= 0.6f;
		} else if (pl.item() instanceof SamplePeers.StarSystem sys) {
			double rad = sys.radius() * s;
			alpha = focus ? 1 : sys.known() ? fadeIn(s, 12, 26) : 0;
			name = sys.known() ? sys.name() : "Unknown venue";
			if (focus) sub = sys.known() ? plural(sys.planets(), "peer") + " · " + plural(sys.agents(), "agent") : null;
			y = pl.y() + Math.max(UIScale.scale(10f), Math.min(rad * TILT, UIScale.scale(60f))) + UIScale.scale(14);
		} else {
			return;
		}
		if (alpha <= 0.02f) return;
		g.setFont(getFont().deriveFont(focus ? Font.BOLD : Font.PLAIN, UIScale.scale(focus ? 12f : 11f)));
		centred(g, name, pl.x(), y, new Color(1f, 1f, 1f, Math.min(1f, 0.85f * alpha + (focus ? 0.13f : 0))));
		if (sub != null) {
			g.setFont(getFont().deriveFont(UIScale.scale(10f)));
			centred(g, sub, pl.x(), y + UIScale.scale(13), new Color(1f, 1f, 1f, 0.55f));
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
