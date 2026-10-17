package brightside.peers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where everything is on the Peers map: one tree of bodies, the same rules at
 * every depth. A node whose owner the graph does not have is a root, free on
 * the plane; every other node orbits its owner. So venues are stars, their
 * users planets and the users' agents moons, and anything attached further
 * down orbits whatever it belongs to in the same way.
 *
 * <p>Distances round an owner are in the owner's radii, and a body's size is
 * a fraction of its owner's, so a system looks the same at any depth. Bodies
 * of equal density keep Kepler's law in those units: the period depends on
 * the distance alone, {@code period(k) = PERIOD × (k / K0)^1.5}. Unplaced
 * children sit on the ring their relation names, else are packed outwards by
 * their own extent, so neighbours never overlap. A node may report more
 * children than are known, by a fact named for them ("Users" on a venue,
 * "Agents" on a user): the rest are drawn as unnamed bodies.
 *
 * <p>How each depth looks is configuration ({@link #CHILD_SIZE}); the
 * structure has no levels.
 */
public final class MapLayout {

	/** A root's radius, in world units. */
	public static final double ROOT_RADIUS = 0.088;
	/** A child's size as a fraction of its owner's, by depth: planets, then moons and below. */
	static final double[] CHILD_SIZE = { 1, 0.45, 0.42 };
	/** Seconds per turn at {@link #K0} owner radii; further out is slower, by Kepler's law. */
	public static final double PERIOD = 12, K0 = 2.4;
	/** The gap kept between packed neighbours, in their owner's radii. */
	static final double GAP = 0.5;
	/** How deep ownership is followed; anything deeper, or in a cycle, is a root. */
	public static final int MAX_DEPTH = 8;
	/** The most unnamed bodies drawn for what a node reports beyond those known. */
	static final int MOST_UNNAMED = 10;
	/** The smallest space kept between two roots nobody placed, in world units, beyond their extents. */
	static final double ROOT_MARGIN = 1.2;
	/** How near and far an unplaced root is put by default, in world units. */
	static final double NEAR = 5, FAR = 16;

	/** One body: a node, or an unnamed one its owner reports; where it sits and how big it is. */
	public static final class Body {
		/** The node, or null for a body its owner reports but nobody has introduced. */
		public final Node node;
		/** The node's DID, or the owner's key with {@code #n} for an unnamed body. */
		public final String key;
		public final Body parent;
		public final int depth;
		public final List<Body> children = new ArrayList<>();
		/** Size and reach, in world units: its own radius, and how far it and everything round it extends. */
		double radius, extent;
		/** Round its owner: distance in the owner's radii, angle at time zero, and the named ring it is on, or -1. */
		double k, phase;
		int ring = -1;
		boolean placed;
		/** Where it is now, in world units; and, for a root, where it rests. */
		double x, z, x0, z0;

		Body(Node node, String key, Body parent) {
			this.node = node;
			this.key = key;
			this.parent = parent;
			this.depth = (parent == null) ? 0 : parent.depth + 1;
		}

		public double radius() {
			return radius;
		}

		public double extent() {
			return extent;
		}

		public double k() {
			return k;
		}

		public int ring() {
			return ring;
		}

		public double x() {
			return x;
		}

		public double z() {
			return z;
		}

		public boolean isRoot() {
			return parent == null;
		}

		/** The root this body belongs to: its star. */
		public Body star() {
			Body b = this;
			while (b.parent != null) b = b.parent;
			return b;
		}

		@Override
		public String toString() {
			return key + "@" + depth;
		}
	}

	private final MapSettings settings;
	private final List<Body> roots = new ArrayList<>();
	private final Map<String, Body> byKey = new LinkedHashMap<>();

	public MapLayout(PeerGraph graph, MapSettings settings) {
		this.settings = settings;
		Map<String, Node> parentOf = new LinkedHashMap<>();
		for (Node n : graph.nodes()) parentOf.put(n.did(), ownerOf(graph, n));
		// Roots first, then each body under its owner, in the graph's order.
		Map<String, List<Node>> kids = new LinkedHashMap<>();
		for (Node n : graph.nodes()) {
			Node p = parentOf.get(n.did());
			if (p != null) kids.computeIfAbsent(p.did(), x -> new ArrayList<>()).add(n);
		}
		for (Node n : graph.nodes()) {
			if (parentOf.get(n.did()) != null) continue;
			Body root = new Body(n, n.did(), null);
			roots.add(root);
			grow(root, kids);
		}
		for (Body r : roots) size(r);
		spread();
	}

	/** {@code n}'s owner as the map draws it: none if unknown, too deep, or in a cycle. */
	private static Node ownerOf(PeerGraph graph, Node n) {
		Node owner = graph.owner(n);
		if (owner == null) return null;
		Set<String> seen = new HashSet<>();
		seen.add(n.did());
		int depth = 0;
		for (Node a = owner; a != null; a = graph.owner(a)) {
			if (!seen.add(a.did()) || ++depth > MAX_DEPTH) return null; // a cycle, or too deep: drawn free
		}
		return owner;
	}

	private void grow(Body b, Map<String, List<Node>> kids) {
		byKey.put(b.key, b);
		List<Node> known = kids.getOrDefault(b.key, List.of());
		for (Node n : known) {
			Body c = new Body(n, n.did(), b);
			b.children.add(c);
			grow(c, kids);
		}
		int unnamed = Math.min(MOST_UNNAMED, reported(b.node) - known.size());
		for (int i = 0; i < unnamed; i++) {
			Body c = new Body(null, b.key + "#" + i, b);
			b.children.add(c);
			byKey.put(c.key, c);
		}
	}

	/** Sizes bottom-up: each body's radius from its owner's, its children's orbits, then its extent. */
	private void size(Body b) {
		if (b.parent == null) {
			b.radius = ROOT_RADIUS * (1 + 0.03 * Math.min(10, b.children.size()));
		} else {
			double share = CHILD_SIZE[Math.min(b.depth, CHILD_SIZE.length - 1)];
			double main = (b.node != null && Node.MAIN.equals(b.node.relation())) ? 1.4 : 1;
			double unnamed = (b.node == null) ? 0.6 : 1;
			b.radius = b.parent.radius * share * (1 + 0.06 * Math.min(4, b.children.size())) * main * unnamed;
		}
		for (Body c : b.children) size(c);
		orbits(b);
		double extent = b.radius * 1.2;
		for (Body c : b.children) extent = Math.max(extent, c.k * b.radius + c.extent);
		b.extent = extent;
	}

	/**
	 * Where {@code b}'s children orbit it: as placed, else on the ring their
	 * relation names, else packed outwards beyond those, each clear of the last.
	 */
	private void orbits(Body b) {
		double r = b.radius, edge = MapSettings.MIN_K * r;
		for (Body c : b.children) {
			c.phase = 2 * Math.PI * MapSettings.hash(c.key, 1);
			c.ring = settings.ringOf(c.node);
			if (settings.placements().get(c.key) instanceof MapSettings.Orbit o) {
				c.k = o.radius();
				c.phase = o.phase();
				c.placed = true;
			} else if (c.ring >= 0) {
				c.k = settings.rings().get(c.ring).radius();
			} else {
				continue;
			}
			if (c.ring >= 0) edge = Math.max(edge, c.k * r + c.extent + GAP * r);
		}
		for (Body c : b.children) {
			if (c.placed || c.ring >= 0) continue;
			c.k = (edge + c.extent) / r;
			edge = c.k * r + c.extent + GAP * r;
		}
	}

	/**
	 * Roots, kept apart: each starts where it was placed, or your own venue at
	 * the centre, or where its DID puts it; then those nobody placed are pushed
	 * off one another, always the same way. A view concern only; the settings
	 * keep just what was placed.
	 */
	private void spread() {
		List<Body> order = new ArrayList<>(roots);
		order.sort(Comparator.comparing(b -> b.key));
		Set<Body> fixed = new HashSet<>();
		for (Body b : order) {
			if (settings.placements().get(b.key) instanceof MapSettings.Free f) {
				b.x = f.x();
				b.z = f.z();
				b.placed = true;
				fixed.add(b);
			} else if (home(b)) {
				b.x = b.z = 0;
				fixed.add(b);
			} else {
				double angle = 2 * Math.PI * MapSettings.hash(b.key, 2);
				double distance = NEAR + (FAR - NEAR) * MapSettings.hash(b.key, 3);
				b.x = Math.cos(angle) * distance;
				b.z = Math.sin(angle) * distance;
			}
		}
		for (int round = 0; round < 80; round++) {
			boolean moved = false;
			for (int i = 0; i < order.size(); i++) {
				for (int j = i + 1; j < order.size(); j++) {
					Body a = order.get(i), c = order.get(j);
					boolean fa = fixed.contains(a), fc = fixed.contains(c);
					if (fa && fc) continue;
					double want = a.extent + c.extent + ROOT_MARGIN;
					double dx = c.x - a.x, dz = c.z - a.z, d = Math.hypot(dx, dz);
					if (d >= want) continue;
					if (d < 1e-6) {
						dx = 1;
						dz = 0;
						d = 1;
					}
					double push = (want - d) / (fa || fc ? 1 : 2), ux = dx / d, uz = dz / d;
					if (!fa) {
						a.x -= ux * push;
						a.z -= uz * push;
					}
					if (!fc) {
						c.x += ux * push;
						c.z += uz * push;
					}
					moved = true;
				}
			}
			if (!moved) break;
		}
		for (Body b : roots) {
			b.x0 = b.x;
			b.z0 = b.z;
		}
	}

	/** Your own venue: the root whose relation is {@link Node#SELF}. */
	static boolean home(Body b) {
		return b.node != null && b.node.is(Node.VENUE) && Node.SELF.equals(b.node.relation());
	}

	// ------------------------------------------------------------------
	// Motion
	// ------------------------------------------------------------------

	/** Seconds per turn at {@code k} owner radii: the same at every depth. */
	public static double period(double k) {
		return PERIOD * Math.pow(k / K0, 1.5);
	}

	/**
	 * Moves everything to time {@code t}, with {@code carried} held at world
	 * point ({@code cx}, {@code cz}): a root exactly there, any other body on
	 * the orbit round its owner it would take if let go. Whatever orbits a
	 * body moves with it.
	 */
	public void at(double t, String carried, double cx, double cz) {
		for (Body r : roots) {
			if (r.key.equals(carried)) {
				r.x = cx;
				r.z = cz;
			} else {
				r.x = r.x0;
				r.z = r.z0;
			}
			move(r, t, carried, cx, cz);
		}
	}

	private void move(Body b, double t, String carried, double cx, double cz) {
		for (Body c : b.children) {
			double k = c.k, a;
			if (c.key.equals(carried)) {
				double[] ka = around(b, cx, cz);
				k = ka[0];
				a = ka[1];
			} else {
				a = c.phase + t * 2 * Math.PI / period(c.k);
			}
			c.x = b.x + Math.cos(a) * k * b.radius;
			c.z = b.z + Math.sin(a) * k * b.radius;
			move(c, t, carried, cx, cz);
		}
	}

	/** A world point as a place round {@code owner}: {distance in its radii, kept in range; angle}. */
	static double[] around(Body owner, double wx, double wz) {
		double dx = wx - owner.x, dz = wz - owner.z;
		double k = Math.hypot(dx, dz) / Math.max(owner.radius, 1e-12);
		return new double[] { Math.max(MapSettings.MIN_K, Math.min(MapSettings.MAX_K, k)), Math.atan2(dz, dx) };
	}

	/**
	 * Where {@code b} would be left if dropped at world point ({@code wx},
	 * {@code wz}) at time {@code t}: free there if it is a root, else on the
	 * orbit round its owner through that point, phased so it carries on from
	 * there without a jump. Call after {@link #at} for the same moment.
	 */
	public MapSettings.Placement dropAt(Body b, double wx, double wz, double t) {
		if (b.parent == null) return new MapSettings.Free(wx, wz);
		double[] ka = around(b.parent, wx, wz);
		double phase = Math.IEEEremainder(ka[1] - t * 2 * Math.PI / period(ka[0]), 2 * Math.PI);
		return new MapSettings.Orbit(ka[0], phase);
	}

	// ------------------------------------------------------------------
	// Looking things up
	// ------------------------------------------------------------------

	public List<Body> roots() {
		return roots;
	}

	/** The body for a DID, or an unnamed body's key; null if not drawn. */
	public Body get(String key) {
		return (key == null) ? null : byKey.get(key);
	}

	/** Every body, owners before what orbits them. */
	public List<Body> all() {
		return List.copyOf(byKey.values());
	}

	/** Your own venue, or null. */
	public Body home() {
		for (Body r : roots) if (home(r)) return r;
		return null;
	}

	/** The named children of {@code key}: a venue's users, a user's agents. */
	public List<Node> childrenOf(String key) {
		Body b = get(key);
		if (b == null) return List.of();
		List<Node> out = new ArrayList<>();
		for (Body c : b.children) if (c.node != null) out.add(c.node);
		return out;
	}

	/** How many children a body has: as many as it reports, and at least those known. */
	public int childCount(String key) {
		Body b = get(key);
		return (b == null) ? 0 : b.children.size();
	}

	/** What a node's children are called: a venue's users, a user's agents, otherwise bodies. */
	public static String childNoun(Node n) {
		if (n == null) return "body";
		if (n.is(Node.VENUE)) return "user";
		if (n.is(Node.PERSON)) return "agent";
		return "body";
	}

	/** How many children a node says it has, by the fact named for them, or -1. */
	static int reported(Node n) {
		if (n == null) return -1;
		String noun = childNoun(n);
		String label = Character.toUpperCase(noun.charAt(0)) + noun.substring(1) + "s";
		for (Node.Fact f : n.facts()) {
			if (!f.label().equals(label)) continue;
			try {
				return Integer.parseInt(f.value().trim());
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return -1;
	}
}
