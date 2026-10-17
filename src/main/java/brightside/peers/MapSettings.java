package brightside.peers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * How this installation draws peer data: the orbits of your own venue (their
 * names, the relations each holds, their radii), where bodies were dragged, keyed by DID,
 * and the view to open on. Independent of the peer data itself: it names no
 * node's facts, a placement for a DID no source reports is ignored, and
 * resetting it touches nothing real. Immutable; each change makes a new value.
 *
 * <p>World units put the outermost default orbit at about 1. A body with no
 * placement is placed from a hash of its DID, so the same node lands in the
 * same place on every launch without anything being stored. Angles are
 * radians at time zero of the map's clock, which is the wall clock, so a
 * planet keeps moving while Brightside is closed.
 */
public final class MapSettings {

	public static final int VERSION = 2;
	/** How close in and far out an orbit or a planet may be, in world units. */
	public static final double MIN_RADIUS = 0.2, MAX_RADIUS = 1.8;
	/** The smallest gap kept between neighbouring orbits. */
	public static final double ORBIT_GAP = 0.08;
	/** How near and far a distant venue is placed by default. */
	static final double NEAR = 5, FAR = 16;
	/** How close in and far out a moon may be, in its planet's radii; and the spacing of unplaced moons. */
	public static final double MIN_MOON = 1.6, MAX_MOON = 8, FIRST_MOON = 2.3, MOON_SPACING = 0.9;

	/** An orbit: what it is called, which relations sit on it, and its radius. */
	public record Orbit(String name, List<String> relations, double radius) {
		public Orbit {
			relations = List.copyOf(relations);
		}

		Orbit withRadius(double r) {
			return new Orbit(name, relations, r);
		}
	}

	/** Where a dragged body was left. */
	public sealed interface Placement permits OnOrbit, At, Moon {
	}

	/** A planet: its distance from you and its angle at time zero. */
	public record OnOrbit(double radius, double phase) implements Placement {
	}

	/** A distant venue: its position on the plane. */
	public record At(double x, double z) implements Placement {
	}

	/** A moon: its distance from its planet, in the planet's radii, and its angle at time zero. */
	public record Moon(double radius, double phase) implements Placement {
	}

	/** The view to open on: the zoom, whether the map is held still, and the DID it follows, or null. */
	public record View(double zoom, boolean paused, String track) {
		public View(double zoom, boolean paused) {
			this(zoom, paused, null);
		}

		public View tracking(String did) {
			return new View(zoom, paused, did);
		}
	}

	public static final MapSettings DEFAULT = new MapSettings(List.of(
		new Orbit("Admin", List.of(Node.OPERATOR), 0.42),
		new Orbit("Users", List.of(Node.SELF, Node.MEMBER), 0.78)), Map.of(), new View(1, false));

	private final List<Orbit> orbits;
	private final Map<String, Placement> placements;
	private final View view;

	private MapSettings(List<Orbit> orbits, Map<String, Placement> placements, View view) {
		this.orbits = List.copyOf(orbits);
		this.placements = Map.copyOf(placements);
		this.view = view;
	}

	public List<Orbit> orbits() {
		return orbits;
	}

	public Map<String, Placement> placements() {
		return placements;
	}

	public View view() {
		return view;
	}

	/**
	 * The orbit a user of your venue sits on, by its relation, or -1 for a
	 * relation no orbit holds, which is drawn on the outermost.
	 */
	public int orbitOf(Node node) {
		if (node.relation() == null) return -1;
		for (int i = 0; i < orbits.size(); i++) {
			if (orbits.get(i).relations().contains(node.relation())) return i;
		}
		return -1;
	}

	/** Where a planet is: as placed, else on its orbit at the angle its DID gives it. */
	public OnOrbit planet(Node node) {
		if (placements.get(node.did()) instanceof OnOrbit o) return o;
		int i = orbitOf(node);
		return new OnOrbit(i >= 0 ? orbits.get(i).radius() : orbits.getLast().radius(), 2 * Math.PI * hash(node.did(), 1));
	}

	/** Where a distant venue is: as placed, else at the bearing and distance its DID gives it. */
	public At venue(Node node) {
		if (placements.get(node.did()) instanceof At a) return a;
		double angle = 2 * Math.PI * hash(node.did(), 2);
		double distance = NEAR + (FAR - NEAR) * hash(node.did(), 3);
		return new At(Math.cos(angle) * distance, Math.sin(angle) * distance);
	}

	/** Where a moon is: as placed, else the {@code index}th out from its planet, at the angle its DID gives it. */
	public Moon moon(Node node, int index) {
		if (placements.get(node.did()) instanceof Moon m) return m;
		return new Moon(FIRST_MOON + MOON_SPACING * index, 2 * Math.PI * hash(node.did(), 6));
	}

	/**
	 * A stable number in [0, 1) for a DID: the same on every launch and every
	 * machine, since {@link String#hashCode()} is specified, then mixed so
	 * neighbouring DIDs land far apart.
	 */
	public static double hash(String did, int salt) {
		long h = did.hashCode() * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
		h ^= (h >>> 33);
		h *= 0xFF51AFD7ED558CCDL;
		h ^= (h >>> 33);
		h *= 0xC4CEB9FE1A85EC53L;
		h ^= (h >>> 33);
		return (h >>> 11) * 0x1.0p-53;
	}

	// ------------------------------------------------------------------
	// Changes
	// ------------------------------------------------------------------

	/** Orbit {@code i} at {@code radius}, kept between its neighbours. */
	public MapSettings withOrbitRadius(int i, double radius) {
		double lo = (i > 0) ? orbits.get(i - 1).radius() + ORBIT_GAP : MIN_RADIUS;
		double hi = (i < orbits.size() - 1) ? orbits.get(i + 1).radius() - ORBIT_GAP : MAX_RADIUS;
		List<Orbit> next = new ArrayList<>(orbits);
		next.set(i, orbits.get(i).withRadius(Math.max(lo, Math.min(hi, radius))));
		return new MapSettings(next, placements, view);
	}

	public MapSettings withPlacement(String did, Placement p) {
		Map<String, Placement> next = new LinkedHashMap<>(placements);
		next.put(did, p);
		return new MapSettings(orbits, next, view);
	}

	public MapSettings withView(View v) {
		return new MapSettings(orbits, placements, v);
	}

	/** One body back where its DID puts it. */
	public MapSettings withoutPlacement(String did) {
		if (!placements.containsKey(did)) return this;
		Map<String, Placement> next = new LinkedHashMap<>(placements);
		next.remove(did);
		return new MapSettings(orbits, next, view);
	}

	/** Every body back where its DID puts it; orbits and the view are kept. */
	public MapSettings withoutPlacements() {
		return new MapSettings(orbits, Map.of(), view);
	}

	/** The default orbits and no placements; the view is kept. */
	public MapSettings reset() {
		return new MapSettings(DEFAULT.orbits, Map.of(), view);
	}

	/** Whether anything differs from the defaults but the view. */
	public boolean rearranged() {
		return !placements.isEmpty() || !orbits.equals(DEFAULT.orbits);
	}

	/** Drops placements, and the body followed, for DIDs {@code graph} no longer has. */
	public MapSettings prune(PeerGraph graph) {
		Map<String, Placement> next = new LinkedHashMap<>();
		for (Map.Entry<String, Placement> e : placements.entrySet()) {
			if (graph.get(e.getKey()) != null) next.put(e.getKey(), e.getValue());
		}
		View v = (view.track() != null && graph.get(view.track()) == null) ? view.tracking(null) : view;
		return (next.size() == placements.size() && v == view) ? this : new MapSettings(orbits, next, v);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MapSettings m && orbits.equals(m.orbits) && placements.equals(m.placements)
			&& view.equals(m.view);
	}

	@Override
	public int hashCode() {
		return orbits.hashCode() * 31 + placements.hashCode();
	}

	// ------------------------------------------------------------------
	// As a lattice value
	// ------------------------------------------------------------------

	/** The settings as a value to store: plain maps, vectors, strings and numbers. */
	public ACell toCell() {
		AVector<ACell> os = Vectors.empty();
		for (Orbit o : orbits) {
			AVector<ACell> rels = Vectors.empty();
			for (String r : o.relations()) rels = rels.conj(Strings.create(r));
			os = os.conj(Maps.of("name", o.name(), "relations", rels, "radius", CVMDouble.create(o.radius())));
		}
		AMap<AString, ACell> ps = Maps.empty();
		for (Map.Entry<String, Placement> e : placements.entrySet()) {
			ACell p = switch (e.getValue()) {
				case OnOrbit o -> Maps.of("radius", CVMDouble.create(o.radius()), "phase", CVMDouble.create(o.phase()));
				case At a -> Maps.of("x", CVMDouble.create(a.x()), "z", CVMDouble.create(a.z()));
				case Moon m -> Maps.of("around", CVMDouble.create(m.radius()), "phase", CVMDouble.create(m.phase()));
			};
			ps = ps.assoc(Strings.create(e.getKey()), p);
		}
		return Maps.of("version", CVMLong.create(VERSION), "orbits", os, "placements", ps,
			"view", (view.track() == null)
				? Maps.of("zoom", CVMDouble.create(view.zoom()), "paused", CVMBool.create(view.paused()))
				: Maps.of("zoom", CVMDouble.create(view.zoom()), "paused", CVMBool.create(view.paused()), "track", view.track()));
	}

	/**
	 * Reads stored settings, keeping what makes sense and defaulting the rest:
	 * nothing stored, a part that is missing or malformed, or a version this
	 * one does not know never fails a launch.
	 */
	public static MapSettings fromCell(ACell cell) {
		if (!(cell instanceof AMap<?, ?>)) return DEFAULT;
		List<Orbit> orbits = new ArrayList<>();
		// Orbits mean something only to the version that wrote them; placements and the view are by DID and zoom.
		boolean sameVersion = num(RT.getIn(cell, "version"), -1) == VERSION;
		if (sameVersion && RT.getIn(cell, "orbits") instanceof AVector<?> os) {
			double last = 0;
			for (long i = 0; i < os.count(); i++) {
				ACell o = os.get(i);
				String name = str(RT.getIn(o, "name"));
				double r = num(RT.getIn(o, "radius"), -1);
				List<String> rels = new ArrayList<>();
				if (RT.getIn(o, "relations") instanceof AVector<?> rv) {
					for (long j = 0; j < rv.count(); j++) {
						String s = str(rv.get(j));
						if (s != null) rels.add(s);
					}
				}
				if (name == null || r <= last || r > MAX_RADIUS) {
					orbits = null; // out of order or nonsense: the default orbits instead
					break;
				}
				orbits.add(new Orbit(name, rels, r));
				last = r;
			}
		}
		if (orbits == null || orbits.isEmpty()) orbits = DEFAULT.orbits;

		Map<String, Placement> placements = new LinkedHashMap<>();
		if (RT.getIn(cell, "placements") instanceof AMap<?, ?> ps) {
			for (long i = 0; i < ps.count(); i++) {
				MapEntry<?, ?> e = ps.entryAt(i);
				String did = str(e.getKey());
				ACell v = e.getValue();
				if (did == null) continue;
				double r = num(RT.getIn(v, "radius"), Double.NaN), ph = num(RT.getIn(v, "phase"), Double.NaN);
				double x = num(RT.getIn(v, "x"), Double.NaN), z = num(RT.getIn(v, "z"), Double.NaN);
				double around = num(RT.getIn(v, "around"), Double.NaN);
				if (!Double.isNaN(around) && !Double.isNaN(ph)) {
					placements.put(did, new Moon(Math.max(MIN_MOON, Math.min(MAX_MOON, around)), ph));
				} else if (!Double.isNaN(r) && !Double.isNaN(ph)) {
					placements.put(did, new OnOrbit(Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r)), ph));
				} else if (!Double.isNaN(x) && !Double.isNaN(z)) {
					placements.put(did, new At(x, z));
				}
			}
		}

		double zoom = num(RT.getIn(cell, "view", "zoom"), 1);
		boolean paused = CVMBool.TRUE.equals(RT.getIn(cell, "view", "paused"));
		return new MapSettings(orbits, placements, new View(zoom > 0 ? zoom : 1, paused, str(RT.getIn(cell, "view", "track"))));
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	private static double num(ACell c, double otherwise) {
		if (c instanceof CVMDouble d) return d.doubleValue();
		if (c instanceof CVMLong l) return l.longValue();
		return otherwise;
	}
}
