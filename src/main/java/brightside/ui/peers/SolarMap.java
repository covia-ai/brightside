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
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.formdev.flatlaf.util.UIScale;

import brightside.peers.MapSettings;
import brightside.peers.Node;
import brightside.peers.PeerGraph;
import brightside.ui.components.Theme;

/**
 * The Peers map: peer data ({@link PeerGraph}) drawn as you see fit
 * ({@link MapSettings}), the two joined only by DID. Venues are stars: your own
 * is the sun at the centre, the rest are star systems far out, linked to you
 * when the relation says so. A venue's users are its planets: yours sit on
 * orbits chosen by their relation (the admin, then you and anyone else), and
 * another venue's circle its star. A user's agents are its moons.
 *
 * <p>Everything sits on one tilted plane in world units, where the outermost
 * default orbit is about 1, so a single zoom runs from a planet's moons to the
 * whole universe. Details fade as they shrink — ring names, then planet names,
 * moons and lamps, then a system's planets — and whatever is under the pointer
 * is always named.
 *
 * <p>Hover to see, click to choose, drag a body to move it (a moon round its
 * own planet), drag an orbit's line to resize it, wheel to zoom about the
 * pointer, drag the sky to pan, double-click a star to visit it, or anywhere
 * else to come home. Each of these that changes how the map is drawn hands
 * the new settings to {@code onSettings}; none touches the peer data.
 * The map's clock is the wall clock, so placements hold across launches.
 */
@SuppressWarnings("serial")
final class SolarMap extends JComponent {

	/** The vertical squash of the plane: how far it is tilted towards you. */
	private static final double TILT = 0.58;
	/** A planet of size 1, and your sun, in world units. */
	private static final double PLANET = 0.042, SUN = 0.088;
	/** Seconds per turn on an orbit of radius {@link #KEPLER_RADIUS}; further out is slower, by Kepler's law. */
	private static final double KEPLER = 80, KEPLER_RADIUS = 0.34;
	/** Zoom limits: from the whole universe and some, to a planet's moons. */
	static final double MIN_ZOOM = 0.035, MAX_ZOOM = 3.2;
	/** How much of the universe the "whole universe" view takes in, in world units. */
	private static final double UNIVERSE = 17.5;
	/** How near an orbit's line the pointer must be to take hold of it, in pixels. */
	private static final float ORBIT_GRIP = 6f;
	private static final Color SPACE_CENTRE = new Color(0x161C33);
	private static final Color SPACE_EDGE = new Color(0x06080E);
	static final Color CONNECTED = new Color(0x4ADE80);
	static final Color IDLE = new Color(0xFBBF24);
	static final Color OFFLINE = new Color(0x6B7280);
	/** Your own planet. */
	static final Color YOU = new Color(0xFACC15);
	/** Planet and star colours, chosen by DID. */
	private static final Color[] PALETTE = { new Color(0x5EC8F2), new Color(0x7FE0C4), new Color(0xF2A65E),
		new Color(0xE58BB4), new Color(0xB79CF2), new Color(0xE8C66A), new Color(0x6FA8F7), new Color(0xA8B48A),
		new Color(0xF29F8E), new Color(0x8FD3A6) };

	private final Consumer<String> onSelect;
	private final Runnable onView;
	private final Consumer<MapSettings> onSettings;
	private final Timer timer;
	private final Timer glide;
	private final float[][] stars;
	private final List<Placed> placed = new ArrayList<>();

	private PeerGraph graph = PeerGraph.EMPTY;
	private MapSettings settings = MapSettings.DEFAULT;
	/** Your venue, the sun; null until a source reports one. */
	private Node home;
	/** Your venue's users, on its orbits. */
	private List<Node> planets = List.of();
	private List<Node> systems = List.of();
	/** Each other venue's users, by the venue's DID. */
	private Map<String, List<Node>> users = Map.of();
	/** Each user's agents, by the user's DID. */
	private Map<String, List<Node>> moons = Map.of();
	/** Where each distant venue is drawn: as placed, else spread out from where its DID puts it. */
	private Map<String, Point2D.Double> spots = Map.of();
	/** Where each planet was drawn this frame, {x, y, radius} on screen, so a moon can be dropped round it. */
	private final Map<String, double[]> planetAt = new LinkedHashMap<>();
	/** Labels already drawn this frame, so a lesser one never lands on top of them. */
	private final List<Rectangle2D> labelled = new ArrayList<>();

	/** Seconds on the map's clock: the wall clock, less any time spent paused. */
	private double t = clock();
	private double pausedFor;
	private boolean paused;
	private double zoom = 1, panX, panY;
	private double toZoom = 1, toPanX, toPanY;
	private String hover, selected;
	/** The body the view follows, kept centred as it moves, or null. */
	/** The planet of whoever you are acting as, or null. */
	private String acting;
	private String tracking;
	/** Where on screen the body followed is held, from the centre: moved by panning while following. */
	private double trackDX, trackDY;
	/** Hears a right-click: the DID of the body under it, or null for the sky, and where. */
	private BiConsumer<String, Point> onMenu = (did, at) -> {
	};
	private Point dragFrom;
	/** What is being carried: a body's DID, or an orbit's index; where it is; and where on it the pointer took hold. */
	private String dragging;
	private int resizing = -1;
	private Point2D.Double dragAt;
	private double grabX, grabY;

	/** What a node is drawn as: your venue, another venue, one of your users, or a user of another venue. */
	enum Body {
		SUN, STAR, PLANET, FAR_PLANET, MOON;

		boolean star() {
			return this == SUN || this == STAR;
		}

		/** Whether dragging it moves it (a moon round its own planet); the rest stay put, and dragging them pans. */
		boolean movable() {
			return this == STAR || this == PLANET || this == MOON;
		}
	}

	/** Something drawn this frame, for hit-testing: a node, as what, its centre and radius on screen. */
	private record Placed(Node node, Body body, double x, double y, double r) {
		boolean star() {
			return body.star();
		}
	}

	/**
	 * @param onSelect   hears the DID of the body chosen, or null
	 * @param onView     told whenever the zoom changes, so the header can name the view
	 * @param onSettings handed the new settings whenever the arrangement or the view changes
	 */
	SolarMap(Consumer<String> onSelect, Runnable onView, Consumer<MapSettings> onSettings) {
		this.onSelect = onSelect;
		this.onView = onView;
		this.onSettings = onSettings;
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
				String h = didOf(hit(e.getPoint()));
				int ring = (h == null) ? orbitNear(e.getPoint()) : -1;
				if (h == null ? hover != null : !h.equals(hover)) {
					hover = h;
					repaint();
				}
				setCursor(Cursor.getPredefinedCursor(h != null ? Cursor.HAND_CURSOR
					: ring >= 0 ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger()) {
					menu(e);
					return;
				}
				dragFrom = e.getPoint();
				if (!SwingUtilities.isLeftMouseButton(e)) return;
				// A body is picked up; an orbit's line is taken hold of; anywhere else pans,
				// as does the body the view follows, which the view moves with.
				Placed body = hit(e.getPoint());
				if (body != null && body.body().movable() && !body.node().did().equals(tracking)) {
					dragging = body.node().did();
					grabX = body.x() - e.getX();
					grabY = body.y() - e.getY();
					return;
				}
				resizing = orbitNear(e.getPoint());
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (dragFrom == null) return;
				glide.stop();
				if (dragging != null) {
					dragAt = world(e.getX() + grabX, e.getY() + grabY);
					if (!dragging.equals(selected)) select(dragging);
				} else if (resizing >= 0) {
					Point2D.Double w = world(e.getX(), e.getY());
					settings = settings.withOrbitRadius(resizing, Math.hypot(w.x, w.y));
				} else {
					panX += e.getX() - dragFrom.x;
					panY += e.getY() - dragFrom.y;
					if (tracking != null) {
						// Still following, from wherever it is now held.
						trackDX += e.getX() - dragFrom.x;
						trackDY += e.getY() - dragFrom.y;
					}
					dragFrom = e.getPoint();
					onView.run(); // the header names the venue in view
				}
				setCursor(Cursor.getPredefinedCursor(resizing >= 0 ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR));
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) {
					dragging = null;
					resizing = -1;
					dragAt = null;
					dragFrom = null;
					menu(e);
					return;
				}
				if (dragging != null && dragAt != null) drop(dragging, dragAt);
				else if (resizing >= 0) changed();
				dragging = null;
				resizing = -1;
				dragAt = null;
				dragFrom = null;
				setCursor(Cursor.getDefaultCursor());
				repaint();
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					Placed p = hit(e.getPoint());
					if (p != null && p.body() == Body.STAR) visit(p.node().did());
					else home();
					return;
				}
				select(didOf(hit(e.getPoint())));
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
	// Data and settings
	// ------------------------------------------------------------------

	/**
	 * Draws {@code graph} as {@code settings} say. The first call also opens on
	 * the stored view; later ones keep the view, the selection and anything
	 * being carried, so a fresh snapshot from the source never jolts the map.
	 */
	void setData(PeerGraph graph, MapSettings settings, boolean firstTime) {
		this.graph = graph;
		this.settings = settings;
		Node h = graph.home();
		List<Node> ps = new ArrayList<>(), ss = new ArrayList<>();
		Map<String, List<Node>> us = new LinkedHashMap<>(), ms = new LinkedHashMap<>();
		for (Node n : graph.nodes()) {
			if (n == h) continue;
			Node owner = graph.owner(n);
			if (n.is(Node.VENUE) || owner == null) {
				ss.add(n); // a venue, or something whose venue nobody reports: a star of its own
			} else if (owner == h) {
				ps.add(n);
			} else if (owner.is(Node.VENUE)) {
				us.computeIfAbsent(owner.did(), k -> new ArrayList<>()).add(n);
			} else {
				ms.computeIfAbsent(owner.did(), k -> new ArrayList<>()).add(n);
			}
		}
		home = h;
		planets = ps;
		systems = ss;
		users = us;
		moons = ms;
		spots = spread(ss, settings);
		if (selected != null && graph.get(selected) == null) select(null);
		if (firstTime) {
			zoom = toZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, settings.view().zoom()));
			panX = panY = toPanX = toPanY = 0;
			setPaused(settings.view().paused());
			tracking = settings.view().track();
			onView.run();
		}
		repaint();
	}

	/** Smallest distance kept between two distant venues nobody has placed, in world units. */
	private static final double SPACING = 3.2;

	/**
	 * Distant venues, kept apart: each starts where its DID or its placement puts
	 * it, then those nobody placed are pushed off one another, always the same
	 * way. A view concern only; the settings keep just what was placed.
	 */
	private static Map<String, Point2D.Double> spread(List<Node> systems, MapSettings settings) {
		Map<String, Point2D.Double> at = new LinkedHashMap<>();
		List<Node> order = new ArrayList<>(systems);
		order.sort(Comparator.comparing(Node::did));
		for (Node n : order) {
			MapSettings.At a = settings.venue(n);
			at.put(n.did(), new Point2D.Double(a.x(), a.z()));
		}
		for (int round = 0; round < 80; round++) {
			boolean moved = false;
			for (int i = 0; i < order.size(); i++) {
				for (int j = i + 1; j < order.size(); j++) {
					Node a = order.get(i), b = order.get(j);
					boolean fixedA = settings.placements().containsKey(a.did()), fixedB = settings.placements().containsKey(b.did());
					if (fixedA && fixedB) continue;
					Point2D.Double pa = at.get(a.did()), pb = at.get(b.did());
					double dx = pb.x - pa.x, dz = pb.y - pa.y, d = Math.hypot(dx, dz);
					if (d >= SPACING) continue;
					if (d < 1e-6) {
						dx = 1;
						dz = 0;
						d = 1;
					}
					double push = (SPACING - d) / (fixedA || fixedB ? 1 : 2);
					double ux = dx / d, uz = dz / d;
					if (!fixedA) pa.setLocation(pa.x - ux * push, pa.y - uz * push);
					if (!fixedB) pb.setLocation(pb.x + ux * push, pb.y + uz * push);
					moved = true;
				}
			}
			if (!moved) break;
		}
		return at;
	}

	MapSettings settings() {
		return settings;
	}

	/** What {@code did} is drawn as, or null for a moon or a node not drawn. */
	Body bodyOf(String did) {
		Node n = graph.get(did);
		if (n == null) return null;
		if (n == home) return Body.SUN;
		if (systems.contains(n)) return Body.STAR;
		if (planets.contains(n)) return Body.PLANET;
		Node owner = graph.owner(n);
		if (owner != null && users.getOrDefault(owner.did(), List.of()).contains(n)) return Body.FAR_PLANET;
		Body planet = (owner == null) ? null : bodyOf(owner.did());
		return (planet == Body.PLANET || planet == Body.FAR_PLANET) ? Body.MOON : null;
	}

	/** Whether {@code did} is a venue: your own, or one far out. */
	boolean isStar(String did) {
		Body b = bodyOf(did);
		return b != null && b.star();
	}

	/** Your venue, or null before a source reports it. */
	Node homeVenue() {
		return home;
	}

	/** A venue's users: its planets. */
	List<Node> usersOf(String did) {
		if (home != null && home.did().equals(did)) return planets;
		return users.getOrDefault(did, List.of());
	}

	List<Node> moonsOf(String did) {
		return moons.getOrDefault(did, List.of());
	}

	private void changed() {
		onSettings.accept(settings);
		onView.run();
	}

	/** Every body back where its DID puts it, and the default orbits. */
	void resetLayout() {
		settings = settings.reset();
		changed();
		repaint();
	}

	boolean rearranged() {
		return settings.rearranged();
	}

	private void drop(String did, Point2D.Double at) {
		Body b = bodyOf(did);
		if (b == null || !b.movable()) return;
		if (b == Body.STAR) {
			settings = settings.withPlacement(did, new MapSettings.At(at.x, at.y));
		} else if (b == Body.MOON) {
			double[] planet = planetAt.get(graph.get(did).belongsTo());
			if (planet == null) return;
			Point2D.Double sp = screen(at.x, at.y);
			double[] ka = aroundPlanet(planet, sp.x, sp.y);
			double phase = Math.IEEEremainder(ka[1] - t * 2 * Math.PI / moonPeriod(ka[0]), 2 * Math.PI);
			settings = settings.withPlacement(did, new MapSettings.Moon(ka[0], phase));
		} else {
			double r = clampRadius(Math.hypot(at.x, at.y));
			// The angle at time zero, wrapped into one turn so the stored value stays readable.
			double phase = Math.IEEEremainder(Math.atan2(at.y, at.x) - t * 2 * Math.PI / period(r), 2 * Math.PI);
			settings = settings.withPlacement(did, new MapSettings.OnOrbit(r, phase));
		}
		changed();
	}

	private static double clampRadius(double r) {
		return Math.max(MapSettings.MIN_RADIUS, Math.min(MapSettings.MAX_RADIUS, r));
	}

	/** Seconds per turn for a moon {@code k} planet radii out: further out is slower, by Kepler's law. */
	private static double moonPeriod(double k) {
		return 7 * Math.pow(k / MapSettings.FIRST_MOON, 1.5);
	}

	/** A point on screen as a moon's place round {@code planet}: {distance in its radii, kept in range; angle}. */
	private static double[] aroundPlanet(double[] planet, double sx, double sy) {
		double dx = sx - planet[0], dz = (sy - planet[1]) / TILT;
		double k = Math.hypot(dx, dz) / Math.max(planet[2], 1e-9);
		return new double[] { Math.max(MapSettings.MIN_MOON, Math.min(MapSettings.MAX_MOON, k)), Math.atan2(dz, dx) };
	}

	private static double period(double radius) {
		return KEPLER * Math.pow(radius / KEPLER_RADIUS, 1.5);
	}

	/** A node's colour, the same on every launch: chosen by its DID. */
	static Color hue(String did) {
		return PALETTE[(int) (MapSettings.hash(did, 4) * PALETTE.length)];
	}

	/** Who you are acting as, the operator or the named user: its planet and name are yellow. */
	void setActing(String did) {
		acting = did;
		repaint();
	}

	String acting() {
		return acting;
	}

	/** Whoever you are acting as is yellow; your main assistant wears the brand colour; anything else is coloured by its DID. */
	Color colourOf(Node n) {
		if (n.did().equals(acting)) return YOU;
		if (Node.MAIN.equals(n.relation())) return Theme.accent();
		return hue(n.did());
	}

	static Color lamp(String status) {
		if (Node.CONNECTED.equals(status)) return CONNECTED;
		if (Node.IDLE.equals(status)) return IDLE;
		return OFFLINE;
	}

	/** A venue's operator wears rings. */
	static boolean ringed(Node n) {
		return Node.OPERATOR.equals(n.relation()) || n.did().endsWith("#operator");
	}

	// ------------------------------------------------------------------
	// View
	// ------------------------------------------------------------------

	void select(String did) {
		selected = did;
		onSelect.accept(did);
		repaint();
	}

	void setPaused(boolean paused) {
		if (paused == this.paused) {
			syncTimer();
			return;
		}
		this.paused = paused;
		if (!paused) pausedFor = clock() - t; // carry on from where it stopped
		syncTimer();
	}

	/** Pauses or unpauses as the person asks, and remembers it. */
	void togglePaused() {
		setPaused(!paused);
		settings = settings.withView(new MapSettings.View(zoom, paused, tracking));
		onSettings.accept(settings);
	}

	boolean isPaused() {
		return paused;
	}

	double zoom() {
		return zoom;
	}

	/** Scales the view by {@code factor} about a point on screen, so what is under it stays put. */
	void zoomAbout(double x, double y, double factor) {
		if (tracking != null) {
			// Following something: zoom about where it is held, so it stays put.
			x = getWidth() / 2.0 + trackDX;
			y = getHeight() / 2.0 + trackDY;
		}
		glide.stop();
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
		double cx = getWidth() / 2.0 + panX, cy = getHeight() / 2.0 + panY;
		panX += (x - cx) * (1 - z / zoom);
		panY += (y - cy) * (1 - z / zoom);
		zoom = z;
		viewChanged();
		repaint();
	}

	/** Zooms in or out by a step about the centre, as the header's buttons do. */
	void zoomStep(boolean in) {
		double f = in ? 1.6 : 1 / 1.6;
		if (tracking != null) {
			zoomAbout(0, 0, f); // about the body followed
			return;
		}
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * f));
		glideTo(z, panX * z / zoom, panY * z / zoom);
	}

	/** Back to your own system, filling the map. */
	void home() {
		tracking = null;
		glideTo(1, 0, 0);
	}

	/** Out to the whole universe of venues. */
	void universe() {
		tracking = null;
		glideTo(1 / UNIVERSE, 0, 0);
	}

	/** Over to another venue, near enough to see its users and their agents; your own is {@link #home()} (the view). */
	void visit(String did) {
		tracking = null;
		Node sys = graph.get(did);
		if (sys == null || !systems.contains(sys)) {
			home();
			return;
		}
		Point2D.Double w = worldOfSystem(sys);
		double base = scale() / zoom;
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 0.32 * Math.min(getWidth(), getHeight()) / (radiusOf(sys) * base)));
		glideTo(z, -w.x * base * z, -w.y * base * z * TILT);
	}

	/**
	 * Follows {@code did}, keeping it centred as it moves, or stops following
	 * with null; remembered with the view. Panning while following keeps
	 * following, from where it was panned to.
	 */
	void track(String did) {
		tracking = did;
		trackDX = trackDY = 0;
		if (did != null) glide.stop();
		viewChanged();
		repaint();
	}

	String tracking() {
		return tracking;
	}

	/** Pans a little towards the body followed, each frame, so it stays where it is held and the view never jumps. */
	private void follow() {
		if (tracking == null || tracking.equals(dragging)) return;
		double[] at = null;
		for (Placed p : placed) {
			if (p.node().did().equals(tracking)) at = new double[] { p.x(), p.y() };
		}
		if (at == null) {
			// A moon too small to draw: follow its planet.
			Node n = graph.get(tracking);
			at = (n == null) ? null : planetAt.get(n.belongsTo());
		}
		if (at == null) return;
		double dx = getWidth() / 2.0 + trackDX - at[0], dy = getHeight() / 2.0 + trackDY - at[1];
		if (Math.abs(dx) < 0.3 && Math.abs(dy) < 0.3) return;
		panX += dx * 0.2;
		panY += dy * 0.2;
		repaint();
	}

	/** Called on a right-click with the DID of the body under it (chosen first), or null for the sky. */
	void setOnMenu(BiConsumer<String, Point> onMenu) {
		this.onMenu = onMenu;
	}

	private void menu(MouseEvent e) {
		String did = didOf(hit(e.getPoint()));
		if (did != null && !did.equals(selected)) select(did);
		onMenu.accept(did, e.getPoint());
	}

	/** Whether {@code did} has been moved from where its DID puts it. */
	boolean placed(String did) {
		return settings.placements().containsKey(did);
	}

	/** One body back where its DID puts it. */
	void unplace(String did) {
		settings = settings.withoutPlacement(did);
		changed();
		repaint();
	}

	private void viewChanged() {
		settings = settings.withView(new MapSettings.View(zoom, paused, tracking));
		onSettings.accept(settings);
		onView.run();
	}

	private void glideTo(double z, double px, double py) {
		toZoom = z;
		toPanX = px;
		toPanY = py;
		if (!isShowing()) {
			zoom = z;
			panX = px;
			panY = py;
			viewChanged();
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
		boolean done = Math.abs(lt - lz) < 0.002 && Math.abs(toPanX - panX) < 0.5 && Math.abs(toPanY - panY) < 0.5;
		if (done) {
			zoom = toZoom;
			panX = toPanX;
			panY = toPanY;
			glide.stop();
			viewChanged();
		} else {
			onView.run();
		}
		repaint();
	}

	/** What the view is showing, for the header. */
	String viewName() {
		// Near enough to a venue to see its planets: that venue.
		Point2D.Double c = world(getWidth() / 2.0, getHeight() / 2.0);
		if (zoom >= 0.3) {
			for (Node sys : systems) {
				Point2D.Double w = worldOfSystem(sys);
				if (sys.name() != null && Math.hypot(c.x - w.x, c.y - w.y) < radiusOf(sys) * 1.5) return sys.name();
			}
		}
		if (zoom >= 0.55 && Math.hypot(c.x, c.y) < 1.5) return "Your system";
		if (zoom >= 0.12) return "Neighbourhood";
		return "Universe";
	}

	private void syncTimer() {
		if (isShowing() && !paused) timer.start();
		else timer.stop();
	}

	private static double clock() {
		return System.currentTimeMillis() / 1000.0;
	}

	private void tick() {
		t = clock() - pausedFor;
		repaint();
	}

	@Override
	public String getToolTipText(MouseEvent e) {
		Placed p = hit(e.getPoint());
		if (p == null) {
			int ring = orbitNear(e.getPoint());
			return (ring >= 0) ? settings.orbits().get(ring).name() + " · drag to resize" : null;
		}
		Node n = p.node();
		if (p.body() == Body.SUN) return "Home · " + n.label() + " · " + plural(planets.size(), "user");
		if (p.star()) {
			return (n.name() != null) ? n.name() + " · " + statusLabel(n) : "A venue you don't know";
		}
		int m = moonsOf(n.did()).size();
		return n.label() + " · " + statusLabel(n) + (m == 0 ? "" : " · " + plural(m, "agent"));
	}

	static String statusLabel(Node n) {
		String s = n.status();
		if (Node.CONNECTED.equals(s)) return "Connected";
		if (Node.IDLE.equals(s)) return "Idle";
		if (Node.OFFLINE.equals(s)) return "Offline";
		if (Node.NOT_CONNECTED.equals(s) || s == null) return "Not connected";
		return s.substring(0, 1).toUpperCase() + s.substring(1).replace('-', ' ');
	}

	private Placed hit(Point at) {
		for (int i = placed.size() - 1; i >= 0; i--) { // front-most first
			Placed p = placed.get(i);
			double slop = Math.max(p.r() * 1.25, UIScale.scale(9));
			if (at.distance(p.x(), p.y()) <= slop) return p;
		}
		return null;
	}

	private static String didOf(Placed p) {
		return (p == null) ? null : p.node().did();
	}

	/** The orbit whose line is under the pointer, or -1. */
	private int orbitNear(Point at) {
		double s = scale();
		if (s * MapSettings.ORBIT_GAP < UIScale.scale(ORBIT_GRIP) * 2.5) return -1; // too far out to tell them apart
		Point2D.Double w = world(at.x, at.y);
		double rho = Math.hypot(w.x, w.y);
		List<MapSettings.Orbit> os = settings.orbits();
		for (int i = 0; i < os.size(); i++) {
			// Distance on screen across the line, allowing for the tilt.
			double across = Math.abs(rho - os.get(i).radius()) * s * Math.max(TILT, Math.abs(w.x) / Math.max(rho, 1e-9));
			if (across <= UIScale.scale(ORBIT_GRIP)) return i;
		}
		return -1;
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

	/** The point on the tilted plane under a screen position. */
	private Point2D.Double world(double x, double y) {
		double s = scale();
		return new Point2D.Double((x - getWidth() / 2.0 - panX) / s, (y - getHeight() / 2.0 - panY) / (s * TILT));
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
			planetAt.clear();
			paintSky(g, getWidth(), getHeight());

			double s = scale();
			Point2D.Double you = screen(0, 0);
			// Far out, the links to the venues you know come into view.
			float links = 1 - fadeIn(s, 90, 220);
			if (links > 0) for (Node sys : systems) paintSystemLink(g, you, sys, links);
			for (Node sys : systems) paintSystem(g, sys, s);
			paintYourSystem(g, you, s);
			// Labels last, most wanted first, so a lesser one never covers a greater.
			labelled.clear();
			List<Placed> byWant = new ArrayList<>(placed);
			byWant.sort(Comparator.comparingInt(this::want).reversed());
			for (Placed p : byWant) paintLabel(g, p, s);
			follow();
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
		float orbitAlpha = 0.25f + 0.75f * fadeIn(s, 30, 140);
		float names = fadeIn(s, 170, 240);
		List<MapSettings.Orbit> os = settings.orbits();
		for (int i = 0; i < os.size(); i++) paintOrbit(g, cx, cy, s * os.get(i).radius(), i, orbitAlpha, names);

		// While a planet is carried, the orbit it would take if let go.
		if (dragging != null && dragAt != null && bodyOf(dragging) == Body.PLANET) {
			double ro = clampRadius(Math.hypot(dragAt.x, dragAt.y)) * s;
			float dash = UIScale.scale(5f);
			g.setStroke(new BasicStroke(UIScale.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[] { dash, dash }, (float) (2 * dash - (t * 12) % (2 * dash))));
			g.setColor(fade(Theme.accent(), 0.7f));
			g.draw(new Ellipse2D.Double(cx - ro, cy - ro * TILT, 2 * ro, 2 * ro * TILT));
		}

		double unit = Math.max(UIScale.scale(1.6f), s * PLANET);
		List<Object[]> at = new ArrayList<>();
		for (Node p : planets) {
			double wx, wz;
			if (p.did().equals(dragging) && dragAt != null) {
				// Carried: under the pointer, kept within how far out a planet may go.
				double d = Math.hypot(dragAt.x, dragAt.y), k = clampRadius(d) / Math.max(d, 1e-9);
				wx = dragAt.x * k;
				wz = dragAt.y * k;
			} else {
				MapSettings.OnOrbit o = settings.planet(p);
				double a = o.phase() + t * 2 * Math.PI / period(o.radius());
				wx = Math.cos(a) * o.radius();
				wz = Math.sin(a) * o.radius();
			}
			double depth = Math.max(-1, Math.min(1, wz / Math.max(Math.hypot(wx, wz), 1e-9))); // -1 far, +1 near
			Point2D.Double xy = screen(wx, wz);
			double r = unit * size(p) * (0.82 + 0.2 * (depth + 1) / 2);
			at.add(new Object[] { p, xy.x, xy.y, r, depth });
		}
		at.sort(Comparator.comparingDouble(o -> (double) o[4]));

		for (Object[] o : at) paintLink(g, cx, cy, (Node) o[0], (double) o[1], (double) o[2], s);
		boolean sunDrawn = false;
		for (Object[] o : at) {
			if (!sunDrawn && (double) o[4] >= 0) {
				paintSun(g, cx, cy, s);
				sunDrawn = true;
			}
			Node p = (Node) o[0];
			double x = (double) o[1], y = (double) o[2], r = (double) o[3];
			paintPlanet(g, p, x, y, r, cx, cy);
			placed.add(new Placed(p, Body.PLANET, x, y, r));
		}
		if (!sunDrawn) paintSun(g, cx, cy, s);
	}

	/** A planet's size: a little bigger for each of its agents. */
	private double size(Node p) {
		return 1.0 + 0.06 * Math.min(4, moonsOf(p.did()).size());
	}

	private void paintOrbit(Graphics2D g, double cx, double cy, double r, int ring, float alpha, float names) {
		boolean lit = ring == resizing || orbitOfDid(hover) == ring || orbitOfDid(selected) == ring;
		boolean last = ring == settings.orbits().size() - 1;
		float dash = UIScale.scale(3f);
		g.setStroke(new BasicStroke(UIScale.scale(ring == resizing ? 1.8f : lit ? 1.4f : 1f), BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND, 1f, last ? new float[] { dash, dash * 2 } : null, (float) (t * 4 % (dash * 3))));
		g.setColor(new Color(1f, 1f, 1f, alpha * (ring == resizing ? 0.5f : lit ? 0.32f : 0.11f)));
		g.draw(new Ellipse2D.Double(cx - r, cy - r * TILT, 2 * r, 2 * r * TILT));
		float shown = (ring == resizing) ? 1 : names;
		if (shown <= 0) return;
		// The orbit's name, centred below its near edge, where orbits are furthest apart.
		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(9.5f)));
		String name = settings.orbits().get(ring).name().toUpperCase();
		float spacing = UIScale.scale(1.2f);
		FontMetrics fm = g.getFontMetrics();
		float width = fm.stringWidth(name) + spacing * (name.length() - 1);
		g.setColor(new Color(1f, 1f, 1f, shown * (lit ? 0.7f : 0.32f)));
		drawTracked(g, name, (float) (cx - width / 2), (float) (cy + r * TILT + UIScale.scale(13)), spacing);
	}

	/** The orbit a planet is on or nearest, carried or not; -1 for anything else. */
	private int orbitOfDid(String did) {
		Node n = graph.get(did);
		if (n == null || !planets.contains(n)) return -1;
		double r = (did.equals(dragging) && dragAt != null) ? Math.hypot(dragAt.x, dragAt.y) : settings.planet(n).radius();
		int best = 0;
		List<MapSettings.Orbit> os = settings.orbits();
		for (int i = 1; i < os.size(); i++) {
			if (Math.abs(os.get(i).radius() - r) < Math.abs(os.get(best).radius() - r)) best = i;
		}
		return best;
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

		if (home != null && home.did().equals(selected)) ring(g, cx, cy, r * (1.45 + 0.06 * Math.sin(t * 3)));

		// Your venue's name; far out, where you are among everything else.
		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(12f)));
		String name = marker > 0.5 ? "You are here" : "Home";
		centred(g, name, cx, cy + Math.max(r, marker > 0 ? UIScale.scale(14f) : 0) + UIScale.scale(16),
			new Color(1f, 1f, 1f, 0.92f));
		if (home != null) placed.add(new Placed(home, Body.SUN, cx, cy, Math.max(r, UIScale.scale(8f))));
	}

	/** A faint line from you to a connected peer, with packets travelling it. */
	private void paintLink(Graphics2D g, double cx, double cy, Node p, double x, double y, double s) {
		boolean live = Node.CONNECTED.equals(p.status());
		if (!live && !Node.IDLE.equals(p.status())) return;
		float a = fadeIn(s, 60, 140);
		if (a <= 0) return;
		boolean focus = p.did().equals(hover) || p.did().equals(selected);
		Color hue = colourOf(p);
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.3f : 0.9f)));
		g.setColor(fade(hue, a * (focus ? 0.45f : live ? 0.16f : 0.08f)));
		g.draw(new Line2D.Double(cx, cy, x, y));
		if (live) packets(g, cx, cy, x, y, hue, 0.2 + 0.15 * settings.planet(p).radius(), MapSettings.hash(p.did(), 5), a);
	}

	private void packets(Graphics2D g, double x0, double y0, double x1, double y1, Color hue, double speed,
			double offset, float alpha) {
		double len = Math.hypot(x1 - x0, y1 - y0);
		if (len < 1) return;
		double ux = (x1 - x0) / len, uy = (y1 - y0) / len;
		double s = UIScale.scale(2.6f);
		for (int k = 0; k < 2; k++) {
			double f = ((t * speed + offset + k * 0.5) % 1.0 + 1) % 1.0;
			if (k == 1) f = 1 - f; // one each way
			double px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
			double dx = (k == 0) ? ux : -ux, dy = (k == 0) ? uy : -uy; // pointing the way it travels
			float a = (float) Math.sin(f * Math.PI);
			Path2D.Double tri = new Path2D.Double();
			tri.moveTo(px + dx * s * 1.5, py + dy * s * 1.5);
			tri.lineTo(px - dx * s - dy * s, py - dy * s + dx * s);
			tri.lineTo(px - dx * s + dy * s, py - dy * s - dx * s);
			tri.closePath();
			g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.5f), 0.9f * a * alpha));
			g.fill(tri);
		}
	}

	private void paintPlanet(Graphics2D g, Node p, double x, double y, double r, double cx, double cy) {
		planetAt.put(p.did(), new double[] { x, y, r });
		boolean distant = !Node.CONNECTED.equals(p.status()) && !Node.IDLE.equals(p.status());
		boolean ghost = Node.NOT_CONNECTED.equals(p.status());
		Color hue = distant ? Theme.blend(colourOf(p), new Color(0x8A93A6), 0.6f) : colourOf(p);
		Composite was = g.getComposite();
		if (ghost) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		boolean focus = p.did().equals(hover) || p.did().equals(selected);
		boolean chosen = p.did().equals(selected);

		// Too small to model: a lit dot that still glows when pointed at.
		if (r < UIScale.scale(3.5f)) {
			double gr = r * (focus ? 4 : 2.6);
			g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) gr, new float[] { 0f, 1f },
				new Color[] { fade(hue, focus ? 0.7f : 0.4f), fade(hue, 0f) }));
			g.fill(new Ellipse2D.Double(x - gr, y - gr, 2 * gr, 2 * gr));
			g.setColor(Theme.blend(hue, Color.WHITE, 0.3f));
			g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
			g.setComposite(was);
			if (chosen) ring(g, x, y, Math.max(r * 2.2, UIScale.scale(7f)));
			return;
		}

		double glow = r * (focus ? 2.6 : 1.9);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) glow, new float[] { 0f, 1f },
			new Color[] { fade(hue, focus ? 0.45f : 0.22f), fade(hue, 0f) }));
		g.fill(new Ellipse2D.Double(x - glow, y - glow, 2 * glow, 2 * glow));

		// Moons only once there is room for them; behind the planet first.
		float moonsShown = fadeIn(r, UIScale.scale(5f), UIScale.scale(8f));
		List<double[]> ms = moonsShown > 0 ? moonPositions(p, x, y, r) : List.of();
		List<Node> its = moonsOf(p.did());
		if (moonsShown > 0 && dragging != null && dragAt != null && its.contains(graph.get(dragging))) {
			// While one of its moons is carried, the orbit it would take if let go.
			Point2D.Double sp = screen(dragAt.x, dragAt.y);
			double ro = aroundPlanet(new double[] { x, y, r }, sp.x, sp.y)[0] * r;
			float dash = UIScale.scale(4f);
			g.setStroke(new BasicStroke(UIScale.scale(1.2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[] { dash, dash }, (float) (2 * dash - (t * 10) % (2 * dash))));
			g.setColor(fade(Theme.accent(), 0.7f));
			g.draw(new Ellipse2D.Double(x - ro, y - ro * TILT, 2 * ro, 2 * ro * TILT));
		}
		for (double[] m : ms) if (m[3] < 0) paintMoon(g, its.get((int) m[4]), m, moonsShown);

		Ellipse2D body = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
		boolean rings = ringed(p);
		if (rings) paintRings(g, x, y, r, hue, true);

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

		if (rings) paintRings(g, x, y, r, hue, false);
		for (double[] m : ms) if (m[3] >= 0) paintMoon(g, its.get((int) m[4]), m, moonsShown);
		g.setComposite(was);

		if (chosen) ring(g, x, y, r * (1.55 + 0.08 * Math.sin(t * 3)));

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
	private List<double[]> moonPositions(Node p, double x, double y, double r) {
		List<double[]> out = new ArrayList<>();
		List<Node> its = moonsOf(p.did());
		for (int i = 0; i < its.size(); i++) {
			double k, a;
			if (its.get(i).did().equals(dragging) && dragAt != null) {
				// Carried: under the pointer, on the orbit round its planet it would take.
				Point2D.Double sp = screen(dragAt.x, dragAt.y);
				double[] ka = aroundPlanet(new double[] { x, y, r }, sp.x, sp.y);
				k = ka[0];
				a = ka[1];
			} else {
				MapSettings.Moon m = settings.moon(its.get(i), i);
				k = m.radius();
				a = m.phase() + t * 2 * Math.PI / moonPeriod(k);
			}
			double orbit = r * k;
			double depth = Math.sin(a);
			double size = Node.MAIN.equals(its.get(i).relation()) ? 1.5 : 1; // your main assistant stands out
			out.add(new double[] { x + Math.cos(a) * orbit, y + depth * orbit * TILT,
				Math.max(UIScale.scale(2.4f), r * 0.3) * size, depth, i });
		}
		return out;
	}

	/** A moon, coloured as its agent and dimmed as it rests; named once there is room, and pointed at like a planet. */
	private void paintMoon(Graphics2D g, Node moon, double[] m, float alpha) {
		Color own = colourOf(moon);
		Color c = Node.CONNECTED.equals(moon.status()) ? Theme.blend(own, Color.WHITE, 0.15f)
			: Node.IDLE.equals(moon.status()) ? Theme.blend(own, new Color(0x9AA1B0), 0.35f)
			: Theme.blend(own, new Color(0x6B7280), 0.75f);
		double r = m[2];
		boolean focus = moon.did().equals(hover) || moon.did().equals(selected);
		if (focus || Node.MAIN.equals(moon.relation())) {
			double gr = r * (focus ? 3.2 : 2.4);
			g.setPaint(new RadialGradientPaint(new Point2D.Double(m[0], m[1]), (float) gr, new float[] { 0f, 1f },
				new Color[] { fade(own, 0.45f * alpha), fade(own, 0f) }));
			g.fill(new Ellipse2D.Double(m[0] - gr, m[1] - gr, 2 * gr, 2 * gr));
		}
		g.setPaint(new RadialGradientPaint(new Point2D.Double(m[0] - r * 0.3, m[1] - r * 0.3), (float) (r * 1.4),
			new float[] { 0f, 1f }, new Color[] { fade(c, alpha), fade(Theme.blend(c, Color.BLACK, 0.6f), alpha) }));
		g.fill(new Ellipse2D.Double(m[0] - r, m[1] - r, 2 * r, 2 * r));
		if (moon.did().equals(selected)) ring(g, m[0], m[1], Math.max(r * 2, UIScale.scale(6f)));
		if (alpha > 0.5f) placed.add(new Placed(moon, Body.MOON, m[0], m[1], r));
	}

	// ------------------------------------------------------------------
	// Other systems
	// ------------------------------------------------------------------

	/** Where a distant venue is on screen: under the pointer while carried, else where it was left. */
	private Point2D.Double screenOfSystem(Node sys) {
		Point2D.Double w = worldOfSystem(sys);
		return screen(w.x, w.y);
	}

	private Point2D.Double worldOfSystem(Node sys) {
		if (sys.did().equals(dragging) && dragAt != null) return dragAt;
		if (settings.placements().get(sys.did()) instanceof MapSettings.At a) return new Point2D.Double(a.x(), a.z());
		Point2D.Double at = spots.get(sys.did());
		if (at != null) return at;
		MapSettings.At a = settings.venue(sys);
		return new Point2D.Double(a.x(), a.z());
	}

	/** A number a venue reports about itself, or -1. */
	private static int reported(Node sys, String fact) {
		for (Node.Fact f : sys.facts()) {
			if (f.label().equals(fact)) {
				try {
					return Integer.parseInt(f.value().trim());
				} catch (NumberFormatException e) {
					return -1;
				}
			}
		}
		return -1;
	}

	/** How many users a venue has: as many as it reports, and at least those known. */
	int usersCount(Node sys) {
		return Math.max(reported(sys, "Users"), usersOf(sys.did()).size());
	}

	/** How many agents a venue has: as many as it reports, and at least those known. */
	int agentsOf(Node sys) {
		int known = 0;
		for (Node u : usersOf(sys.did())) known += moonsOf(u.did()).size();
		return Math.max(reported(sys, "Agents"), known);
	}

	private void paintSystemLink(Graphics2D g, Point2D.Double you, Node sys, float alpha) {
		if (!Node.LINKED.equals(sys.relation())) return;
		Point2D.Double c = screenOfSystem(sys);
		boolean focus = sys.did().equals(hover) || sys.did().equals(selected);
		boolean live = Node.CONNECTED.equals(sys.status());
		Color hue = hue(sys.did());
		float dash = UIScale.scale(4f);
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.4f : 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
			live ? null : new float[] { dash, dash }, 0));
		g.setColor(fade(hue, alpha * (focus ? 0.6f : 0.28f)));
		g.draw(new Line2D.Double(you.x, you.y, c.x, c.y));
		if (live) packets(g, you.x, you.y, c.x, c.y, hue, 0.12, MapSettings.hash(sys.did(), 5), alpha);
	}

	/** The most planets a distant venue is drawn with. */
	private static final int MOST_PLANETS = 10;

	/** A distant venue's size in world units: bigger for more users. */
	private double radiusOf(Node sys) {
		return 0.38 + 0.07 * Math.min(MOST_PLANETS, usersCount(sys));
	}

	private void paintSystem(Graphics2D g, Node sys, double s) {
		Point2D.Double c = screenOfSystem(sys);
		double rad = radiusOf(sys) * s; // the system's size on screen
		double margin = Math.max(rad, UIScale.scale(20f)) * 2;
		if (c.x < -margin || c.y < -margin || c.x > getWidth() + margin || c.y > getHeight() + margin) {
			return; // off screen: nothing to draw or hit
		}
		boolean focus = sys.did().equals(hover) || sys.did().equals(selected);
		boolean known = sys.name() != null;
		float dim = known ? 1f : 0.6f;
		Color base = hue(sys.did());
		Color sun = Node.OFFLINE.equals(sys.status()) ? Theme.blend(base, new Color(0x8A93A6), 0.6f) : base;

		// Orbits and planets once the system is big enough to have any: the
		// users known, as planets with their moons, then dots for the rest it reports.
		float detail = fadeIn(rad, UIScale.scale(16f), UIScale.scale(40f));
		List<Node> met = usersOf(sys.did());
		int count = Math.min(MOST_PLANETS, Math.max(met.isEmpty() ? 2 : met.size(), usersCount(sys)));
		List<double[]> front = new ArrayList<>();
		if (detail > 0) {
			Composite was = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, detail));
			for (int k = 0; k < count; k++) {
				double ro = rad * (0.28 + 0.72 * (k + 1) / count);
				g.setStroke(new BasicStroke(UIScale.scale(0.8f)));
				g.setColor(new Color(1f, 1f, 1f, 0.07f * dim));
				g.draw(new Ellipse2D.Double(c.x - ro, c.y - ro * TILT, 2 * ro, 2 * ro * TILT));
				Node p = (k < met.size()) ? met.get(k) : null;
				double a = t * 2 * Math.PI / (40 + k * 23) + 2 * Math.PI
					* ((p != null) ? MapSettings.hash(p.did(), 1) : MapSettings.hash(sys.did(), 7) + k * 0.27);
				double depth = Math.sin(a);
				double[] at = { c.x + Math.cos(a) * ro, c.y + depth * ro * TILT, k, depth };
				if (depth < 0) paintFarPlanet(g, sys, p, at, rad, c, dim);
				else front.add(at);
			}
			g.setComposite(was);
		}

		// The star itself, between the far planets and the near ones.
		double r = Math.max(UIScale.scale(2.6f), rad * 0.12);
		double corona = r * (focus ? 6 : 4.2);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(c.x, c.y), (float) corona,
			new float[] { 0f, 0.25f, 1f },
			new Color[] { fade(Color.WHITE, 0.5f * dim), fade(sun, 0.3f * dim), fade(sun, 0f) }));
		g.fill(new Ellipse2D.Double(c.x - corona, c.y - corona, 2 * corona, 2 * corona));
		g.setColor(fade(Theme.blend(sun, Color.WHITE, 0.4f), dim));
		g.fill(new Ellipse2D.Double(c.x - r, c.y - r, 2 * r, 2 * r));
		if (sys.did().equals(selected)) ring(g, c.x, c.y, Math.max(r * 2.4, UIScale.scale(9f)));
		placed.add(new Placed(sys, Body.STAR, c.x, c.y, Math.max(r * 1.6, rad * 0.12)));

		if (!front.isEmpty()) {
			Composite was = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, detail));
			for (double[] at : front) {
				int k = (int) at[2];
				paintFarPlanet(g, sys, (k < met.size()) ? met.get(k) : null, at, rad, c, dim);
			}
			g.setComposite(was);
		}
	}

	/**
	 * One planet of a distant venue at {x, y, slot, depth}: a known user as a
	 * planet with its moons, which can be pointed at once it is big enough;
	 * otherwise a dot for a user the venue reports but nobody has introduced.
	 */
	private void paintFarPlanet(Graphics2D g, Node sys, Node p, double[] at, double rad, Point2D.Double star, float dim) {
		double x = at[0], y = at[1];
		if (p == null) {
			double pr = Math.max(UIScale.scale(1.4f), Math.min(UIScale.scale(4f), rad * 0.022));
			g.setColor(fade(PALETTE[(int) (MapSettings.hash(sys.did(), 8 + (int) at[2]) * PALETTE.length)], 0.6f * dim));
			g.fill(new Ellipse2D.Double(x - pr, y - pr, 2 * pr, 2 * pr));
			return;
		}
		double r = Math.max(UIScale.scale(1.8f), rad * 0.075 * size(p) * (0.82 + 0.2 * (at[3] + 1) / 2));
		paintPlanet(g, p, x, y, r, star.x, star.y);
		if (r >= UIScale.scale(3f)) placed.add(new Placed(p, Body.FAR_PLANET, x, y, r));
	}

	// ------------------------------------------------------------------
	// Labels
	// ------------------------------------------------------------------

	/**
	 * Names, drawn last so nothing covers them: a planet's once it is big
	 * enough to read beside, a known venue's once there is room among its
	 * neighbours, and whatever is pointed at or chosen, whatever the zoom.
	 */
	private void paintLabel(Graphics2D g, Placed pl, double s) {
		if (pl.body() == Body.SUN) return; // named as it is drawn
		Node n = pl.node();
		boolean focus = n.did().equals(hover) || n.did().equals(selected);
		String name, sub = null;
		float alpha;
		double y;
		boolean moon = pl.body() == Body.MOON;
		if (moon) {
			// Small and quiet, once the moon is big enough to be worth naming.
			alpha = focus ? 1 : 0.8f * fadeIn(pl.r(), UIScale.scale(3.5f), UIScale.scale(5.5f));
			name = n.label();
			if (focus) sub = statusLabel(n);
			y = pl.y() + pl.r() + UIScale.scale(10);
		} else if (!pl.star()) {
			alpha = focus ? 1 : fadeIn(pl.r(), UIScale.scale(5f), UIScale.scale(8f));
			name = n.label();
			if (focus) sub = statusLabel(n);
			boolean hasMoons = !moonsOf(n.did()).isEmpty();
			y = pl.y() + pl.r() + UIScale.scale(15) + (pl.r() >= UIScale.scale(5f) && hasMoons ? pl.r() * 0.5 : 0);
			if (Node.NOT_CONNECTED.equals(n.status()) && !focus) alpha *= 0.6f;
		} else {
			double rad = radiusOf(n) * s;
			boolean known = n.name() != null;
			alpha = focus ? 1 : known ? fadeIn(s, 12, 26) : 0;
			name = known ? n.name() : "Unknown venue";
			if (focus && known) sub = plural(usersCount(n), "user") + " · " + plural(agentsOf(n), "agent");
			y = pl.y() + Math.max(UIScale.scale(10f), Math.min(rad * TILT, UIScale.scale(60f))) + UIScale.scale(14);
		}
		if (alpha <= 0.02f) return;
		float pt = moon ? (focus ? 10.5f : 9.5f) : (focus ? 12f : 11f);
		g.setFont(getFont().deriveFont(focus ? Font.BOLD : Font.PLAIN, UIScale.scale(pt)));
		// Room for it? A lesser label that would overlap one already drawn waits until pointed at.
		FontMetrics fm = g.getFontMetrics();
		double w = fm.stringWidth(name) + UIScale.scale(6f), h = fm.getHeight() + (sub != null ? UIScale.scale(13f) : 0);
		Rectangle2D box = new Rectangle2D.Double(pl.x() - w / 2, y - fm.getAscent(), w, h);
		if (!focus) for (Rectangle2D other : labelled) if (other.intersects(box)) return;
		labelled.add(box);
		// The name of whoever you are acting as in yellow; everyone else's in white.
		boolean you = n.did().equals(acting);
		centred(g, name, pl.x(), y, fade(you ? YOU : Color.WHITE, Math.min(1f, 0.85f * alpha + (focus ? 0.13f : 0))));
		if (sub != null) {
			g.setFont(getFont().deriveFont(UIScale.scale(10f)));
			centred(g, sub, pl.x(), y + UIScale.scale(13), new Color(1f, 1f, 1f, 0.55f));
		}
	}

	/** How much a label is wanted: what is pointed at or chosen, then planets, their moons, venues you are linked to, then the rest. */
	private int want(Placed p) {
		String did = p.node().did();
		if (did.equals(hover) || did.equals(selected)) return 5;
		if (p.body() == Body.PLANET || p.body() == Body.FAR_PLANET) return 4;
		if (p.body() == Body.MOON) return 3;
		if (Node.LINKED.equals(p.node().relation())) return 2;
		return (p.node().name() != null) ? 1 : 0;
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

	static String plural(int n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}
}
