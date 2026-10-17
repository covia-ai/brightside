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
 * How this installation draws peer data: named rings that bodies sit on by
 * their relation, where bodies were dragged, keyed by DID, and the view to
 * open on. Independent of the peer data itself: it names no node's facts, a
 * placement for a DID no source reports is ignored, and resetting it touches
 * nothing real. Immutable; each change makes a new value.
 *
 * <p>The same rules hold at every level ({@link MapLayout}): a body with no
 * known owner is free on the plane, placed in world units; every other body
 * orbits its owner, at a distance measured in its owner's radii. Rings are in
 * the same units, so a ring means the same thing round a star or a planet.
 * Angles are radians at time zero of the map's clock, which is the wall
 * clock, so bodies keep moving while Brightside is closed.
 */
public final class MapSettings {

	public static final int VERSION = 3;
	/** How close in and far out an orbit may be, in its parent's radii. */
	public static final double MIN_K = 1.6, MAX_K = 40;
	/** The smallest gap kept between neighbouring rings, in their parent's radii. */
	public static final double RING_GAP = 0.9;

	/** A named ring: which relations sit on it, and its distance from the body it circles, in that body's radii. */
	public record Ring(String name, List<String> relations, double radius) {
		public Ring {
			relations = List.copyOf(relations);
		}

		Ring withRadius(double r) {
			return new Ring(name, relations, r);
		}
	}

	/** Where a dragged body was left. */
	public sealed interface Placement permits Free, Orbit {
	}

	/** A body with no owner: its position on the plane, in world units. */
	public record Free(double x, double z) implements Placement {
	}

	/** A body round its owner: its distance in the owner's radii, and its angle at time zero. */
	public record Orbit(double radius, double phase) implements Placement {
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
		new Ring("Admin", List.of(Node.OPERATOR), 4.8),
		new Ring("Users", List.of(Node.SELF, Node.MEMBER), 8.9)), Map.of(), new View(1, false));

	private final List<Ring> rings;
	private final Map<String, Placement> placements;
	private final View view;

	private MapSettings(List<Ring> rings, Map<String, Placement> placements, View view) {
		this.rings = List.copyOf(rings);
		this.placements = Map.copyOf(placements);
		this.view = view;
	}

	public List<Ring> rings() {
		return rings;
	}

	public Map<String, Placement> placements() {
		return placements;
	}

	public View view() {
		return view;
	}

	/** The ring {@code node} sits on by its relation, or -1 if no ring holds it. */
	public int ringOf(Node node) {
		if (node == null || node.relation() == null) return -1;
		for (int i = 0; i < rings.size(); i++) {
			if (rings.get(i).relations().contains(node.relation())) return i;
		}
		return -1;
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

	/** Ring {@code i} at {@code radius}, kept between its neighbours. */
	public MapSettings withRingRadius(int i, double radius) {
		double lo = (i > 0) ? rings.get(i - 1).radius() + RING_GAP : MIN_K;
		double hi = (i < rings.size() - 1) ? rings.get(i + 1).radius() - RING_GAP : MAX_K;
		List<Ring> next = new ArrayList<>(rings);
		next.set(i, rings.get(i).withRadius(Math.max(lo, Math.min(hi, radius))));
		return new MapSettings(next, placements, view);
	}

	public MapSettings withPlacement(String did, Placement p) {
		Map<String, Placement> next = new LinkedHashMap<>(placements);
		next.put(did, p);
		return new MapSettings(rings, next, view);
	}

	public MapSettings withView(View v) {
		return new MapSettings(rings, placements, v);
	}

	/** One body back where its DID puts it. */
	public MapSettings withoutPlacement(String did) {
		if (!placements.containsKey(did)) return this;
		Map<String, Placement> next = new LinkedHashMap<>(placements);
		next.remove(did);
		return new MapSettings(rings, next, view);
	}

	/** Every body back where its DID puts it; rings and the view are kept. */
	public MapSettings withoutPlacements() {
		return new MapSettings(rings, Map.of(), view);
	}

	/** The default rings and no placements; the view is kept. */
	public MapSettings reset() {
		return new MapSettings(DEFAULT.rings, Map.of(), view);
	}

	/** Whether anything differs from the defaults but the view. */
	public boolean rearranged() {
		return !placements.isEmpty() || !rings.equals(DEFAULT.rings);
	}

	/** Drops placements, and the body followed, for DIDs {@code graph} no longer has. */
	public MapSettings prune(PeerGraph graph) {
		Map<String, Placement> next = new LinkedHashMap<>();
		for (Map.Entry<String, Placement> e : placements.entrySet()) {
			if (graph.get(e.getKey()) != null) next.put(e.getKey(), e.getValue());
		}
		View v = (view.track() != null && graph.get(view.track()) == null) ? view.tracking(null) : view;
		return (next.size() == placements.size() && v == view) ? this : new MapSettings(rings, next, v);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MapSettings m && rings.equals(m.rings) && placements.equals(m.placements)
			&& view.equals(m.view);
	}

	@Override
	public int hashCode() {
		return rings.hashCode() * 31 + placements.hashCode();
	}

	// ------------------------------------------------------------------
	// As a lattice value
	// ------------------------------------------------------------------

	/** The settings as a value to store: plain maps, vectors, strings and numbers. */
	public ACell toCell() {
		AVector<ACell> rs = Vectors.empty();
		for (Ring r : rings) {
			AVector<ACell> rels = Vectors.empty();
			for (String rel : r.relations()) rels = rels.conj(Strings.create(rel));
			rs = rs.conj(Maps.of("name", r.name(), "relations", rels, "radius", CVMDouble.create(r.radius())));
		}
		AMap<AString, ACell> ps = Maps.empty();
		for (Map.Entry<String, Placement> e : placements.entrySet()) {
			ACell p = switch (e.getValue()) {
				case Free f -> Maps.of("x", CVMDouble.create(f.x()), "z", CVMDouble.create(f.z()));
				case Orbit o -> Maps.of("around", CVMDouble.create(o.radius()), "phase", CVMDouble.create(o.phase()));
			};
			ps = ps.assoc(Strings.create(e.getKey()), p);
		}
		AMap<AString, ACell> v = Maps.of("zoom", CVMDouble.create(view.zoom()), "paused", CVMBool.create(view.paused()));
		if (view.track() != null) v = v.assoc(Strings.create("track"), Strings.create(view.track()));
		return Maps.of("version", CVMLong.create(VERSION), "rings", rs, "placements", ps, "view", v);
	}

	/**
	 * Reads stored settings, keeping what makes sense and defaulting the rest:
	 * nothing stored, a part that is missing or malformed, or a version this
	 * one does not know never fails a launch. Rings mean something only to the
	 * version that wrote them; an older version's planets, placed in world
	 * units, are dropped, while free positions, orbits round an owner, and the
	 * view carry over.
	 */
	public static MapSettings fromCell(ACell cell) {
		if (!(cell instanceof AMap<?, ?>)) return DEFAULT;
		List<Ring> rings = new ArrayList<>();
		boolean sameVersion = num(RT.getIn(cell, "version"), -1) == VERSION;
		if (sameVersion && RT.getIn(cell, "rings") instanceof AVector<?> rs) {
			double last = 0;
			for (long i = 0; i < rs.count(); i++) {
				ACell r = rs.get(i);
				String name = str(RT.getIn(r, "name"));
				double k = num(RT.getIn(r, "radius"), -1);
				List<String> rels = new ArrayList<>();
				if (RT.getIn(r, "relations") instanceof AVector<?> rv) {
					for (long j = 0; j < rv.count(); j++) {
						String s = str(rv.get(j));
						if (s != null) rels.add(s);
					}
				}
				if (name == null || k <= last || k < MIN_K || k > MAX_K) {
					rings = null; // out of order or nonsense: the default rings instead
					break;
				}
				rings.add(new Ring(name, rels, k));
				last = k;
			}
		}
		if (rings == null || rings.isEmpty()) rings = DEFAULT.rings;

		Map<String, Placement> placements = new LinkedHashMap<>();
		if (RT.getIn(cell, "placements") instanceof AMap<?, ?> ps) {
			for (long i = 0; i < ps.count(); i++) {
				MapEntry<?, ?> e = ps.entryAt(i);
				String did = str(e.getKey());
				ACell v = e.getValue();
				if (did == null) continue;
				double around = num(RT.getIn(v, "around"), Double.NaN), ph = num(RT.getIn(v, "phase"), Double.NaN);
				double x = num(RT.getIn(v, "x"), Double.NaN), z = num(RT.getIn(v, "z"), Double.NaN);
				if (!Double.isNaN(around) && !Double.isNaN(ph)) {
					placements.put(did, new Orbit(Math.max(MIN_K, Math.min(MAX_K, around)), ph));
				} else if (!Double.isNaN(x) && !Double.isNaN(z)) {
					placements.put(did, new Free(x, z));
				}
			}
		}

		double zoom = num(RT.getIn(cell, "view", "zoom"), 1);
		boolean paused = CVMBool.TRUE.equals(RT.getIn(cell, "view", "paused"));
		return new MapSettings(rings, placements, new View(zoom > 0 ? zoom : 1, paused, str(RT.getIn(cell, "view", "track"))));
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
