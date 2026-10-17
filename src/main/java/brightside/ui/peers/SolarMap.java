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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.formdev.flatlaf.util.UIScale;

import brightside.peers.MapLayout;
import brightside.peers.MapLayout.Body;
import brightside.peers.MapSettings;
import brightside.peers.Node;
import brightside.peers.PeerGraph;
import brightside.ui.components.Theme;

/**
 * The Peers map: peer data ({@link PeerGraph}) laid out by {@link MapLayout}
 * as you see fit ({@link MapSettings}), the two joined only by DID. One tree,
 * drawn the same way at every depth: a body with no owner is a star, glowing;
 * everything else orbits its owner, lit by its star. Venues are stars, their
 * users planets, the users' agents moons, and whatever is attached below
 * orbits in the same way. How each depth looks is configuration
 * ({@link Look}); the structure has no levels.
 *
 * <p>Everything sits on one tilted plane in world units, so a single zoom
 * runs from a moon to the whole universe. A body's children come into view
 * as it grows on screen, and names as there is room; whatever is under the
 * pointer is always named.
 *
 * <p>Hover to see, click to choose, drag a body to move it (round its owner,
 * or anywhere for a star), drag a named ring's line to resize it, wheel to
 * zoom about the pointer, drag the sky to pan, double-click a body to visit
 * it, or the sky to come home. Each of these that changes how the map is
 * drawn hands the new settings to {@code onSettings}; none touches the peer
 * data. The map's clock is the wall clock, so placements hold across launches.
 */
@SuppressWarnings("serial")
final class SolarMap extends JComponent {

	/** The vertical squash of the plane: how far it is tilted towards you. */
	private static final double TILT = 0.58;
	/** Zoom limits: from the whole universe and some, to a moon. */
	static final double MIN_ZOOM = 0.035, MAX_ZOOM = 3.2;
	/** How near a ring's line the pointer must be to take hold of it, in pixels. */
	private static final float ORBIT_GRIP = 6f;
	private static final Color SPACE_CENTRE = new Color(0x161C33);
	private static final Color SPACE_EDGE = new Color(0x06080E);
	static final Color CONNECTED = new Color(0x4ADE80);
	static final Color IDLE = new Color(0xFBBF24);
	static final Color OFFLINE = new Color(0x6B7280);
	/** Whoever you are acting as. */
	static final Color YOU = new Color(0xFACC15);

	/**
	 * How bodies at a depth look: the smallest they are drawn, in pixels;
	 * whether they glow round themselves; label sizes, and how big on screen
	 * a body must be for its name to show; and, for their children, whether
	 * each child's orbit path and a line to it are drawn.
	 */
	private record Look(float minRadius, boolean glow, float labelPt, float labelFrom, float labelTo,
			boolean childPaths, boolean childLines) {
	}

	/** By depth: stars, planets, then moons and anything below. Names on stars show by their system's reach. */
	private static final Look[] LOOKS = {
		new Look(2.6f, true, 11f, 10f, 20f, true, true),
		new Look(1.6f, true, 11f, 5f, 8f, false, false),
		new Look(1.2f, false, 9.5f, 3.5f, 5.5f, false, false) };

	private static Look look(Body b) {
		return LOOKS[Math.min(b.depth, LOOKS.length - 1)];
	}

	private final Consumer<String> onSelect;
	private final Runnable onView;
	private final Consumer<MapSettings> onSettings;
	private final Timer timer;
	private final Timer glide;
	private final float[][] stars;
	private final List<Placed> placed = new ArrayList<>();
	/** Labels already drawn this frame, so a lesser one never lands on top of them. */
	private final List<Rectangle2D> labelled = new ArrayList<>();
	/** How much of each body is in view this frame: 0 hidden, 1 fully shown. */
	private final Map<Body, Float> shown = new IdentityHashMap<>();

	private PeerGraph graph = PeerGraph.EMPTY;
	private MapSettings settings = MapSettings.DEFAULT;
	private MapLayout layout = new MapLayout(PeerGraph.EMPTY, MapSettings.DEFAULT);

	/** Seconds on the map's clock: the wall clock, less any time spent paused. */
	private double t = clock();
	private double pausedFor;
	private boolean paused;
	private double zoom = 1, panX, panY;
	private double toZoom = 1, toPanX, toPanY;
	private String hover, selected;
	/** The planet of whoever you are acting as, or null. */
	private String acting;
	/** The body the view follows, kept where it is held as it moves, or null. */
	private String tracking;
	/** Where on screen the body followed is held, from the centre: moved by panning while following. */
	private double trackDX, trackDY;
	/** Hears a right-click: the DID of the body under it, or null for the sky, and where. */
	private BiConsumer<String, Point> onMenu = (did, at) -> {
	};
	private Point dragFrom;
	/** What is being carried: a body's DID, or a named ring round a body; where it is; and where on it the pointer took hold. */
	private String dragging;
	private RingAt resizing;
	private Point2D.Double dragAt;
	private double grabX, grabY;

	/** Something drawn this frame, for hit-testing: a body, its centre and radius on screen. */
	private record Placed(Body body, double x, double y, double r) {
		Node node() {
			return body.node;
		}
	}

	/** A named ring round a body: which ring, and round what. */
	private record RingAt(Body around, int ring) {
	}

	/**
	 * @param onSelect   hears the DID of the body chosen, or null
	 * @param onView     told whenever the view changes, so the header can name it
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
				RingAt ring = (h == null) ? ringNear(e.getPoint()) : null;
				if (h == null ? hover != null : !h.equals(hover)) {
					hover = h;
					repaint();
				}
				setCursor(Cursor.getPredefinedCursor(h != null ? Cursor.HAND_CURSOR
					: ring != null ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger()) {
					menu(e);
					return;
				}
				dragFrom = e.getPoint();
				if (!SwingUtilities.isLeftMouseButton(e)) return;
				// A body is picked up; a ring's line is taken hold of; anywhere else pans,
				// as do the body the view follows and what it orbits, which the view moves with.
				Placed p = hit(e.getPoint());
				if (p != null && p.node() != null && !holdsTracked(p.body().key)) {
					dragging = p.body().key;
					grabX = p.x() - e.getX();
					grabY = p.y() - e.getY();
					return;
				}
				resizing = (p == null) ? ringNear(e.getPoint()) : null;
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (dragFrom == null) return;
				glide.stop();
				if (dragging != null) {
					dragAt = world(e.getX() + grabX, e.getY() + grabY);
					if (!dragging.equals(selected)) select(dragging);
				} else if (resizing != null) {
					Body b = resizing.around();
					Point2D.Double w = world(e.getX(), e.getY());
					double k = Math.hypot(w.x - b.x(), w.y - b.z()) / b.radius();
					settings = settings.withRingRadius(resizing.ring(), k);
					relayout();
				} else {
					panX += e.getX() - dragFrom.x;
					panY += e.getY() - dragFrom.y;
					if (tracking != null) {
						// Still following, from wherever it is now held.
						trackDX += e.getX() - dragFrom.x;
						trackDY += e.getY() - dragFrom.y;
					}
					dragFrom = e.getPoint();
					onView.run(); // the header names what is in view
				}
				setCursor(Cursor.getPredefinedCursor(resizing != null ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR));
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) {
					endDrag();
					menu(e);
					return;
				}
				if (dragging != null && dragAt != null) drop(dragging, dragAt);
				else if (resizing != null) changed();
				endDrag();
				setCursor(Cursor.getDefaultCursor());
				repaint();
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					Placed p = hit(e.getPoint());
					if (p != null && p.node() != null) visit(p.body().key);
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

	private void endDrag() {
		dragging = null;
		resizing = null;
		dragAt = null;
		dragFrom = null;
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
		relayout();
		if (selected != null && graph.get(selected) == null) select(null);
		if (firstTime) {
			zoom = toZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, settings.view().zoom()));
			panX = panY = toPanX = toPanY = 0;
			setPaused(settings.view().paused());
			tracking = settings.view().track();
			trackDX = trackDY = 0;
			onView.run();
		}
		repaint();
	}

	private void relayout() {
		layout = new MapLayout(graph, settings);
		layout.at(t, null, 0, 0);
	}

	MapSettings settings() {
		return settings;
	}

	/** The body for a DID, or null if it is not drawn. */
	Body body(String did) {
		Body b = layout.get(did);
		return (b == null || b.node == null) ? null : b;
	}

	/** Your venue, or null before a source reports it. */
	Node homeVenue() {
		Body h = layout.home();
		return (h == null) ? null : h.node;
	}

	/** The named bodies orbiting {@code did}: a venue's users, a user's agents. */
	List<Node> childrenOf(String did) {
		return layout.childrenOf(did);
	}

	/** How many bodies orbit {@code did}: as many as it reports, and at least those known. */
	int childCount(String did) {
		return layout.childCount(did);
	}

	/** How many named bodies orbit anything that orbits {@code did}, all the way down. */
	int descendants(String did) {
		Body b = body(did);
		return (b == null) ? 0 : count(b) - 1;
	}

	private static int count(Body b) {
		int n = (b.node != null) ? 1 : 0;
		for (Body c : b.children) n += count(c);
		return n;
	}

	private void changed() {
		onSettings.accept(settings);
		onView.run();
	}

	/** Every body back where its DID puts it, and the default rings. */
	void resetLayout() {
		settings = settings.reset();
		relayout();
		changed();
		repaint();
	}

	boolean rearranged() {
		return settings.rearranged();
	}

	private void drop(String key, Point2D.Double at) {
		Body b = body(key);
		if (b == null) return;
		layout.at(t, key, at.x, at.y);
		settings = settings.withPlacement(key, layout.dropAt(b, at.x, at.y, t));
		relayout();
		changed();
	}

	/** A node's colour, the same on every launch: chosen by its DID. */
	static Color hue(String did) {
		return Colours.of(did);
	}

	/** Who you are acting as, the operator or the named user: its planet and name are yellow. */
	void setActing(String did) {
		acting = did;
		repaint();
	}

	String acting() {
		return acting;
	}

	/**
	 * Whoever you are acting as is yellow; your venue and your main assistant
	 * wear the brand colour; anything else is coloured by its DID.
	 */
	Color colourOf(Node n) {
		if (n.did().equals(acting)) return YOU;
		if (Node.MAIN.equals(n.relation())) return Theme.accent();
		if (n.is(Node.VENUE) && Node.SELF.equals(n.relation())) return Theme.accent();
		return hue(n.did());
	}

	/** Whether a node is offline or not connected, and so greyed out. */
	static boolean disconnected(Node n) {
		return !Node.CONNECTED.equals(n.status()) && !Node.IDLE.equals(n.status());
	}

	/** A colour greyed out, keeping a hint of itself. */
	static Color greyed(Color c) {
		return Theme.blend(c, new Color(0x7C8494), 0.82f);
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

	/** What to call a node: your venue is Home; anything else by its name, or as unknown. */
	static String labelOf(Node n) {
		if (n.is(Node.VENUE) && Node.SELF.equals(n.relation())) return "Home";
		if (n.name() != null && !n.name().isBlank()) return n.name();
		return n.is(Node.VENUE) ? "Unknown venue" : "Unknown";
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

	/** Back to your own venue, filling the map. */
	void home() {
		Body h = layout.home();
		if (h != null) {
			visit(h.key);
		} else {
			tracking = null;
			glideTo(1, 0, 0);
		}
	}

	/** Out to the whole universe: every star in view. */
	void universe() {
		tracking = null;
		double reach = 1;
		for (Body r : layout.roots()) reach = Math.max(reach, Math.hypot(r.x(), r.z()) + r.extent());
		glideTo(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1 / reach)), 0, 0);
	}

	/** Over to a body, near enough to see what orbits it; a body with nothing round it shows its owner's system. */
	void visit(String did) {
		tracking = null;
		Body b = body(did);
		if (b == null) return;
		if (b.children.isEmpty() && b.parent != null) b = b.parent;
		double base = scale() / zoom;
		double z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1 / b.extent()));
		glideTo(z, -b.x() * base * z, -b.z() * base * z * TILT);
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

	/** Whether {@code key} is the body followed or one it orbits: dragging it would drag the view, so it pans. */
	private boolean holdsTracked(String key) {
		for (Body b = layout.get(tracking); b != null; b = b.parent) {
			if (b.key.equals(key)) return true;
		}
		return false;
	}

	String tracking() {
		return tracking;
	}

	/** Pans a little towards the body followed, each frame, so it stays where it is held and the view never jumps. */
	private void follow() {
		if (tracking == null || tracking.equals(dragging)) return;
		Body b = layout.get(tracking);
		if (b == null) return;
		Point2D.Double at = screen(b.x(), b.z());
		double dx = getWidth() / 2.0 + trackDX - at.x, dy = getHeight() / 2.0 + trackDY - at.y;
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
		relayout();
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

	/**
	 * What the view is showing, for the header: the smallest body whose
	 * system fills much of the view and holds its centre; else how far out.
	 */
	String viewName() {
		Point2D.Double c = world(getWidth() / 2.0, getHeight() / 2.0);
		double s = scale(), fill = 0.3 * Math.min(getWidth(), getHeight());
		Body best = null;
		for (Body b : layout.all()) {
			if (b.node == null || b.extent() * s < fill) continue;
			if (Math.hypot(c.x - b.x(), c.y - b.z()) > b.extent()) continue;
			if (best == null || b.extent() < best.extent()) best = b;
		}
		if (best != null) return labelOf(best.node);
		return (zoom >= 0.12) ? "Neighbourhood" : "Universe";
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
			RingAt ring = ringNear(e.getPoint());
			return (ring != null) ? settings.rings().get(ring.ring()).name() + " · drag to resize" : null;
		}
		Node n = p.node();
		if (n == null) return null;
		if (n.name() == null && n.is(Node.VENUE)) return "A venue you don't know";
		int kids = childCount(n.did());
		return labelOf(n) + " · " + statusLabel(n) + (kids == 0 ? "" : " · " + plural(kids, MapLayout.childNoun(n)));
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
			if (p.node() == null) continue;
			double slop = Math.max(p.r() * 1.25, UIScale.scale(9));
			if (at.distance(p.x(), p.y()) <= slop) return p;
		}
		return null;
	}

	private static String didOf(Placed p) {
		return (p == null || p.node() == null) ? null : p.node().did();
	}

	/** The named ring whose line is under the pointer, round whichever body it circles, or null. */
	private RingAt ringNear(Point at) {
		double s = scale();
		Point2D.Double w = world(at.x, at.y);
		RingAt best = null;
		double nearest = UIScale.scale(ORBIT_GRIP);
		for (Body b : layout.all()) {
			if (shown(b) <= 0.5f || MapSettings.RING_GAP * b.radius() * s < UIScale.scale(ORBIT_GRIP) * 2.5) continue;
			double dx = w.x - b.x(), dz = w.y - b.z(), rho = Math.hypot(dx, dz);
			for (int ring : ringsRound(b)) {
				double k = settings.rings().get(ring).radius();
				// Distance on screen across the line, allowing for the tilt.
				double across = Math.abs(rho - k * b.radius()) * s * Math.max(TILT, Math.abs(dx) / Math.max(rho, 1e-9));
				if (across <= nearest) {
					nearest = across;
					best = new RingAt(b, ring);
				}
			}
		}
		return best;
	}

	/** The named rings that bodies round {@code b} sit on, innermost first. */
	private static TreeSet<Integer> ringsRound(Body b) {
		TreeSet<Integer> rings = new TreeSet<>();
		for (Body c : b.children) if (c.ring() >= 0) rings.add(c.ring());
		return rings;
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

	private float shown(Body b) {
		return shown.getOrDefault(b, 0f);
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

			Point2D.Double carried = (dragging != null && dragAt != null) ? dragAt : null;
			layout.at(t, carried != null ? dragging : null, carried != null ? carried.x : 0, carried != null ? carried.y : 0);
			double s = scale();
			shown.clear();
			for (Body r : layout.roots()) reveal(r, 1f, s);

			// Far out, the links between your venue and the venues you know come into view.
			Body home = layout.home();
			float links = 1 - fadeIn(s, 90, 220);
			if (home != null && links > 0) {
				for (Body r : layout.roots()) if (r != home) paintRootLink(g, home, r, links);
			}
			// Each star system whole, your own last, so it is never covered.
			List<Body> roots = new ArrayList<>(layout.roots());
			roots.sort(Comparator.comparingInt(r -> r == home ? 1 : 0));
			for (Body r : roots) paintSystem(g, r, s);

			// Labels last, most wanted first, so a lesser one never covers a greater.
			labelled.clear();
			List<Placed> byWant = new ArrayList<>(placed);
			byWant.sort(Comparator.comparingDouble((Placed p) -> want(p, s)).reversed());
			for (Placed p : byWant) paintLabel(g, p, s);
			follow();
		} finally {
			g.dispose();
		}
	}

	/** How much of each body to show: a body's children come into view as its system grows on screen. */
	private void reveal(Body b, float alpha, double s) {
		shown.put(b, alpha);
		if (b.children.isEmpty()) return;
		float kids = alpha * fadeIn(b.extent() * s, UIScale.scale(24f), UIScale.scale(48f));
		for (Body c : b.children) reveal(c, kids, s);
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

	/** A dashed line from your venue to a venue linked to you, with ships travelling it when both ends are live. */
	private void paintRootLink(Graphics2D g, Body home, Body r, float alpha) {
		if (r.node == null || !Node.LINKED.equals(r.node.relation())) return;
		Point2D.Double a = screen(home.x(), home.z()), c = screen(r.x(), r.z());
		boolean focus = r.key.equals(hover) || r.key.equals(selected);
		boolean live = Node.CONNECTED.equals(r.node.status());
		Color hue = colourOf(r.node);
		float dash = UIScale.scale(4f);
		g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.4f : 1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
			live ? null : new float[] { dash, dash }, 0));
		g.setColor(fade(hue, alpha * (focus ? 0.6f : 0.28f)));
		g.draw(new Line2D.Double(a.x, a.y, c.x, c.y));
		if (live) packets(g, a.x, a.y, c.x, c.y, hue, 0.12, MapSettings.hash(r.key, 5), alpha);
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

	// ------------------------------------------------------------------
	// A star system: one tree, drawn the same way all the way down
	// ------------------------------------------------------------------

	/**
	 * One star and everything round it: first the rings and orbit paths, then
	 * the lines to what orbits, then every body far to near, so what passes
	 * behind something is hidden by it.
	 */
	private void paintSystem(Graphics2D g, Body star, double s) {
		Point2D.Double c = screen(star.x(), star.z());
		double reach = Math.max(star.extent() * s, UIScale.scale(20f)) * 1.5;
		if (c.x < -reach || c.y < -reach || c.x > getWidth() + reach || c.y > getHeight() + reach) {
			return; // off screen: nothing to draw or hit
		}
		List<Body> bodies = new ArrayList<>();
		collect(star, bodies);
		for (Body b : bodies) paintPaths(g, b, s);
		for (Body b : bodies) paintLines(g, b, s);
		bodies.sort(Comparator.comparingDouble(Body::z));
		for (Body b : bodies) {
			float a = shown(b);
			if (a <= 0.02f) continue;
			Point2D.Double p = screen(b.x(), b.z());
			double r = radiusOnScreen(b, s);
			Composite was = g.getComposite();
			if (a < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			if (b.isRoot()) paintStar(g, b, p.x, p.y, r, s);
			else paintBody(g, b, p.x, p.y, r, star);
			g.setComposite(was);
			if (a > 0.5f) placed.add(new Placed(b, p.x, p.y, r));
		}
	}

	private void collect(Body b, List<Body> out) {
		if (shown(b) <= 0.02f) return;
		out.add(b);
		for (Body c : b.children) collect(c, out);
	}

	/** A body's radius on screen: never smaller than its depth allows, and a little larger nearer you. */
	private double radiusOnScreen(Body b, double s) {
		double r = b.radius() * s;
		if (!b.isRoot()) {
			double d = (b.z() - b.parent.z()) / Math.max(b.k() * b.parent.radius(), 1e-12); // -1 far, +1 near
			r *= 0.82 + 0.1 * (Math.max(-1, Math.min(1, d)) + 1);
		}
		return Math.max(UIScale.scale(look(b).minRadius()), r);
	}

	/** Round {@code b}: its named rings, each child's orbit path where its depth draws them, and where a carried child would go. */
	private void paintPaths(Graphics2D g, Body b, double s) {
		if (b.children.isEmpty()) return;
		float a = shown(b) * fadeIn(b.extent() * s, UIScale.scale(24f), UIScale.scale(48f));
		if (a <= 0.02f) return;
		Point2D.Double c = screen(b.x(), b.z());
		TreeSet<Integer> rings = ringsRound(b);
		int last = rings.isEmpty() ? -1 : rings.last();
		for (int ring : rings) paintRing(g, b, c, ring, ring == last, s, a);
		if (look(b).childPaths()) {
			for (Body child : b.children) {
				if (child.ring() >= 0) continue;
				double ro = child.k() * b.radius() * s;
				g.setStroke(new BasicStroke(UIScale.scale(0.8f)));
				g.setColor(new Color(1f, 1f, 1f, 0.07f * a));
				g.draw(new Ellipse2D.Double(c.x - ro, c.y - ro * TILT, 2 * ro, 2 * ro * TILT));
			}
		}
		// While one of its children is carried, the orbit it would take if let go.
		Body carried = (dragging == null || dragAt == null) ? null : layout.get(dragging);
		if (carried != null && carried.parent == b) {
			double ro = Math.hypot(carried.x() - b.x(), carried.z() - b.z()) * s;
			float dash = UIScale.scale(4.5f);
			g.setStroke(new BasicStroke(UIScale.scale(1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[] { dash, dash }, (float) (2 * dash - (t * 11) % (2 * dash))));
			g.setColor(fade(Theme.accent(), 0.7f));
			g.draw(new Ellipse2D.Double(c.x - ro, c.y - ro * TILT, 2 * ro, 2 * ro * TILT));
		}
	}

	/** A named ring round {@code b}, lit while one of its bodies is pointed at or the ring is held; named when there is room. */
	private void paintRing(Graphics2D g, Body b, Point2D.Double c, int ring, boolean last, double s, float alpha) {
		double r = settings.rings().get(ring).radius() * b.radius() * s;
		boolean held = resizing != null && resizing.around() == b && resizing.ring() == ring;
		boolean lit = held || onRing(hover, b, ring) || onRing(selected, b, ring);
		float a = alpha * (0.25f + 0.75f * fadeIn(r, UIScale.scale(20f), UIScale.scale(80f)));
		float dash = UIScale.scale(3f);
		g.setStroke(new BasicStroke(UIScale.scale(held ? 1.8f : lit ? 1.4f : 1f), BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND, 1f, last ? new float[] { dash, dash * 2 } : null, (float) (t * 4 % (dash * 3))));
		g.setColor(new Color(1f, 1f, 1f, a * (held ? 0.5f : lit ? 0.32f : 0.11f)));
		g.draw(new Ellipse2D.Double(c.x - r, c.y - r * TILT, 2 * r, 2 * r * TILT));
		float names = held ? 1 : alpha * fadeIn(r, UIScale.scale(120f), UIScale.scale(180f));
		if (names <= 0) return;
		// The ring's name, centred below its near edge, where rings are furthest apart.
		g.setFont(getFont().deriveFont(Font.BOLD, UIScale.scale(9.5f)));
		String name = settings.rings().get(ring).name().toUpperCase();
		float spacing = UIScale.scale(1.2f);
		FontMetrics fm = g.getFontMetrics();
		float width = fm.stringWidth(name) + spacing * (name.length() - 1);
		g.setColor(new Color(1f, 1f, 1f, names * (lit ? 0.7f : 0.32f)));
		drawTracked(g, name, (float) (c.x - width / 2), (float) (c.y + r * TILT + UIScale.scale(13)), spacing);
	}

	private boolean onRing(String key, Body around, int ring) {
		Body b = layout.get(key);
		return b != null && b.parent == around && b.ring() == ring;
	}

	/** Faint lines from {@code b} to what orbits it, where its depth draws them: the links only, since nothing flies into a sun. */
	private void paintLines(Graphics2D g, Body b, double s) {
		if (!look(b).childLines()) return;
		Point2D.Double c = screen(b.x(), b.z());
		for (Body child : b.children) {
			Node n = child.node;
			if (n == null || shown(child) <= 0.02f) continue;
			boolean live = Node.CONNECTED.equals(n.status());
			if (!live && !Node.IDLE.equals(n.status())) continue;
			float a = shown(child) * fadeIn(child.k() * b.radius() * s, UIScale.scale(30f), UIScale.scale(70f));
			if (a <= 0) continue;
			boolean focus = child.key.equals(hover) || child.key.equals(selected);
			Point2D.Double p = screen(child.x(), child.z());
			g.setStroke(new BasicStroke(UIScale.scale(focus ? 1.3f : 0.9f)));
			g.setColor(fade(colourOf(n), a * (focus ? 0.45f : live ? 0.16f : 0.08f)));
			g.draw(new Line2D.Double(c.x, c.y, p.x, p.y));
		}
	}

	/** A star: it shines. Your own pulses, and far out a marker says where you are. */
	private void paintStar(Graphics2D g, Body b, double x, double y, double r, double s) {
		Node n = b.node;
		boolean focus = b.key.equals(hover) || b.key.equals(selected);
		boolean known = n != null && n.name() != null;
		float dim = known ? 1f : 0.6f;
		Color base = (n == null) ? hue(b.key) : colourOf(n);
		Color hue = (n != null && disconnected(n)) ? greyed(base) : base;
		boolean yours = n != null && labelOf(n).equals("Home");
		double pulse = yours ? 1 + 0.04 * Math.sin(t * 1.3) : 1;
		double corona = r * (focus ? 5.2 : 4.2) * pulse;
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) corona,
			new float[] { 0f, 0.22f, 0.5f, 1f },
			new Color[] { fade(Color.WHITE, 0.5f * dim), fade(hue, 0.33f * dim), fade(hue, 0.08f * dim), fade(hue, 0f) }));
		g.fill(new Ellipse2D.Double(x - corona, y - corona, 2 * corona, 2 * corona));
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x - r * 0.25, y - r * 0.3), (float) (r * 1.3),
			new float[] { 0f, 0.45f, 1f },
			new Color[] { fade(Color.WHITE, dim), fade(Theme.blend(hue, Color.WHITE, 0.35f), dim), fade(hue, dim) }));
		g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));

		if (yours) {
			float marker = 1 - fadeIn(s, 60, 160);
			if (marker > 0) {
				double mr = UIScale.scale(14f) * (1 + 0.12 * Math.sin(t * 2.2));
				g.setStroke(new BasicStroke(UIScale.scale(1.4f)));
				g.setColor(fade(Theme.accent(), 0.75f * marker));
				g.draw(new Ellipse2D.Double(x - mr, y - mr, 2 * mr, 2 * mr));
			}
		}
		if (b.key.equals(selected)) ring(g, x, y, Math.max(r * 1.5, UIScale.scale(9f)) * (1 + 0.04 * Math.sin(t * 3)));
	}

	/**
	 * A body round its owner, lit from its star: a shaded sphere, or a dot when
	 * too small to model, or a dim dot for one its owner reports but nobody has
	 * introduced. What is offline or not connected is greyed out.
	 */
	private void paintBody(Graphics2D g, Body b, double x, double y, double r, Body star) {
		Node n = b.node;
		if (n == null) {
			double pr = Math.min(r, UIScale.scale(4f));
			g.setColor(fade(Colours.of(b.key), 0.6f));
			g.fill(new Ellipse2D.Double(x - pr, y - pr, 2 * pr, 2 * pr));
			return;
		}
		Color own = colourOf(n);
		Color hue = disconnected(n) ? greyed(own) : Node.IDLE.equals(n.status()) ? Theme.blend(own, new Color(0x9AA1B0), 0.25f)
			: own;
		Composite was = g.getComposite();
		if (Node.NOT_CONNECTED.equals(n.status())) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f * alphaOf(was)));
		}
		boolean focus = b.key.equals(hover) || b.key.equals(selected);
		boolean chosen = b.key.equals(selected);

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

		if (look(b).glow() || focus || Node.MAIN.equals(n.relation())) {
			double glow = r * (focus ? 2.8 : 2.0);
			g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) glow, new float[] { 0f, 1f },
				new Color[] { fade(hue, focus ? 0.45f : 0.22f), fade(hue, 0f) }));
			g.fill(new Ellipse2D.Double(x - glow, y - glow, 2 * glow, 2 * glow));
		}

		boolean rings = ringed(n);
		if (rings) paintRings(g, x, y, r, hue, true);

		// Lit from its star: the bright side faces it.
		Point2D.Double sun = screen(star.x(), star.z());
		double dx = sun.x - x, dy = sun.y - y, d = Math.max(1, Math.hypot(dx, dy));
		Point2D focusPt = new Point2D.Double(x + dx / d * r * 0.45, y + dy / d * r * 0.45);
		Ellipse2D shape = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 1.25), focusPt,
			new float[] { 0f, 0.5f, 1f },
			new Color[] { Theme.blend(hue, Color.WHITE, 0.45f), hue, Theme.blend(hue, Color.BLACK, 0.72f) },
			MultipleGradientPaint.CycleMethod.NO_CYCLE));
		g.fill(shape);
		g.setStroke(new BasicStroke(UIScale.scale(1f)));
		g.setColor(fade(Theme.blend(hue, Color.WHITE, 0.6f), 0.35f));
		g.draw(shape);
		if (rings) paintRings(g, x, y, r, hue, false);
		g.setComposite(was);

		if (chosen) ring(g, x, y, r * (1.55 + 0.08 * Math.sin(t * 3)));
	}

	private static float alphaOf(Composite c) {
		return (c instanceof AlphaComposite a) ? a.getAlpha() : 1f;
	}

	private void ring(Graphics2D g, double x, double y, double r) {
		g.setStroke(new BasicStroke(UIScale.scale(1.6f)));
		g.setColor(fade(Color.WHITE, 0.75f));
		g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
	}

	/** A tilted ring system: the far half behind the body, the near half in front. */
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

	// ------------------------------------------------------------------
	// Labels
	// ------------------------------------------------------------------

	/**
	 * Names, drawn last so nothing covers them: each once its body is big
	 * enough on screen to be worth naming, as its depth says; your venue
	 * always; and whatever is pointed at or chosen, whatever the zoom.
	 */
	private void paintLabel(Graphics2D g, Placed pl, double s) {
		Node n = pl.node();
		if (n == null) return;
		Body b = pl.body();
		Look look = look(b);
		boolean focus = b.key.equals(hover) || b.key.equals(selected);
		boolean yours = b.isRoot() && labelOf(n).equals("Home");
		String name = labelOf(n), sub = null;
		float alpha;
		if (focus) {
			alpha = 1;
		} else if (yours) {
			alpha = 0.92f;
		} else if (n.name() == null) {
			alpha = 0; // nobody has introduced it: named only when pointed at
		} else {
			// Stars by their system's reach, everything else by its own size.
			double size = b.isRoot() ? b.extent() * s : pl.r();
			alpha = fadeIn(size, UIScale.scale(look.labelFrom()), UIScale.scale(look.labelTo()));
			if (Node.NOT_CONNECTED.equals(n.status())) alpha *= 0.6f;
		}
		if (alpha <= 0.02f) return;
		if (yours && 1 - fadeIn(s, 60, 160) > 0.5f) name = "You are here";
		if (focus) {
			int kids = childCount(n.did());
			sub = statusLabel(n) + (kids == 0 ? "" : " · " + plural(kids, MapLayout.childNoun(n)));
		}
		double y = pl.y() + Math.max(pl.r(), b.isRoot() ? UIScale.scale(10f) : 0) + UIScale.scale(b.depth >= 2 ? 10 : 15);
		float pt = look.labelPt() + (focus || yours ? 1 : 0);
		g.setFont(getFont().deriveFont(focus || yours ? Font.BOLD : Font.PLAIN, UIScale.scale(pt)));
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

	/** How much a label is wanted: what is pointed at or chosen, then your venue, then the bodies whose systems reach furthest on screen. */
	private double want(Placed p, double s) {
		Body b = p.body();
		if (b.key.equals(hover) || b.key.equals(selected)) return Double.MAX_VALUE;
		if (b.node != null && b.isRoot() && labelOf(b.node).equals("Home")) return Double.MAX_VALUE / 2;
		double reach = b.extent() * s;
		if (b.node != null && Node.LINKED.equals(b.node.relation())) reach *= 1.5;
		return reach;
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
		return n + " " + (n == 1 ? unit : unit.equals("body") ? "bodies" : unit + "s");
	}
}
