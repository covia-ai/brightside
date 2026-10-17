package brightside.peers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The Peers map's geometry: one tree, the same rules at every depth, so a
 * body attached anywhere orbits whatever it belongs to as a planet orbits a
 * star.
 */
class MapLayoutTest {

	private static final String P = "did:example:";

	private static Node node(String id, String kind, String relation, String belongsTo, Node.Fact... facts) {
		return new Node(P + id, id, kind, relation, Node.CONNECTED, belongsTo == null ? null : P + belongsTo, List.of(facts));
	}

	/** Four deep: a venue, a user, an agent, and something attached to the agent. */
	private static PeerGraph deep() {
		return new PeerGraph(List.of(
			node("home", Node.VENUE, Node.SELF, null),
			node("you", Node.PERSON, Node.SELF, "home"),
			node("chat", Node.AGENT, null, "you"),
			node("probe", "satellite", null, "chat"),
			node("far", Node.VENUE, Node.LINKED, null)));
	}

	private static double distance(MapLayout.Body a, MapLayout.Body b) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z());
	}

	@Test
	void everyDepthOrbitsItsOwnerByTheSameRule() {
		MapLayout l = new MapLayout(deep(), MapSettings.DEFAULT);
		for (double t : new double[] { 0, 7.5, 1234.25 }) {
			l.at(t, null, 0, 0);
			for (MapLayout.Body b : l.all()) {
				if (b.isRoot()) continue;
				assertEquals(b.k() * b.parent.radius(), distance(b, b.parent), 1e-9,
					b + " sits at k owner radii from its owner at t=" + t);
			}
		}
		MapLayout.Body probe = l.get(P + "probe");
		assertEquals(3, probe.depth, "attached below a moon, it is just another body");
		assertSame(l.get(P + "home"), probe.star());
		assertTrue(probe.radius() < l.get(P + "chat").radius(), "smaller than what it orbits");
		assertEquals(MapLayout.PERIOD, MapLayout.period(MapLayout.K0), 1e-9);
		assertEquals(MapLayout.PERIOD * Math.pow(2, 1.5), MapLayout.period(2 * MapLayout.K0), 1e-9, "Kepler: k^1.5");
	}

	@Test
	void rootsAreFreeAndYourVenueIsAtTheCentre() {
		MapLayout l = new MapLayout(deep(), MapSettings.DEFAULT);
		l.at(0, null, 0, 0);
		MapLayout.Body home = l.home();
		assertEquals(0, home.x(), 1e-12);
		assertEquals(0, home.z(), 1e-12);
		MapLayout.Body far = l.get(P + "far");
		assertTrue(far.isRoot());
		assertTrue(distance(far, home) >= far.extent() + home.extent(), "roots nobody placed are kept apart");
	}

	@Test
	void anUnknownOwnerOrACycleMakesARoot() {
		PeerGraph g = new PeerGraph(List.of(
			node("lost", Node.PERSON, null, "nobody-reports-this"),
			node("a", Node.PERSON, null, "b"),
			node("b", Node.PERSON, null, "a")));
		MapLayout l = new MapLayout(g, MapSettings.DEFAULT);
		assertTrue(l.get(P + "lost").isRoot(), "an owner the graph lacks: drawn free");
		assertTrue(l.get(P + "a").isRoot() || l.get(P + "b").isRoot(), "a cycle ends somewhere");
		l.at(10, null, 0, 0); // and moves without looping for ever
	}

	@Test
	void ringsChooseTheOrbitAndTheRestArePackedClear() {
		PeerGraph g = new PeerGraph(List.of(
			node("home", Node.VENUE, Node.SELF, null),
			node("home#operator", Node.PERSON, Node.OPERATOR, "home"),
			node("you", Node.PERSON, Node.SELF, "home"),
			node("v", Node.VENUE, Node.LINKED, null),
			node("big", Node.PERSON, Node.TRUSTED, "v"),
			node("a1", Node.AGENT, null, "big"), node("a2", Node.AGENT, null, "big"), node("a3", Node.AGENT, null, "big"),
			node("small", Node.PERSON, Node.KNOWN, "v")));
		MapLayout l = new MapLayout(g, MapSettings.DEFAULT);
		assertEquals(4.8, l.get(P + "home#operator").k(), 1e-9, "the admin on its ring");
		assertEquals(8.9, l.get(P + "you").k(), 1e-9, "you on the users' ring");
		MapLayout.Body big = l.get(P + "big"), small = l.get(P + "small"), v = l.get(P + "v");
		double gap = small.k() * v.radius() - small.extent() - (big.k() * v.radius() + big.extent());
		assertTrue(gap > 0, "neighbours on no ring are packed clear of each other: gap " + gap);
		assertTrue(big.extent() > small.extent(), "a body with moons reaches further");
	}

	@Test
	void aPlacementWinsAndDroppingRoundTrips() {
		MapLayout l = new MapLayout(deep(), MapSettings.DEFAULT);
		double t = 500;
		l.at(t, null, 0, 0);
		MapLayout.Body chat = l.get(P + "chat"), you = chat.parent;
		// Drop the moon somewhere round its planet, and the layout puts it back exactly there.
		double wx = you.x() + 5 * you.radius(), wz = you.z() - 1 * you.radius();
		MapSettings.Placement p = l.dropAt(chat, wx, wz, t);
		MapSettings s = MapSettings.DEFAULT.withPlacement(chat.key, p);
		assertEquals(s, MapSettings.fromCell(s.toCell()), "the placement round-trips");
		MapLayout again = new MapLayout(deep(), MapSettings.fromCell(s.toCell()));
		again.at(t, null, 0, 0);
		MapLayout.Body moved = again.get(P + "chat");
		assertEquals(wx, moved.x(), 1e-9);
		assertEquals(wz, moved.z(), 1e-9);

		// A root drops free.
		MapLayout.Body far = l.get(P + "far");
		assertEquals(new MapSettings.Free(3, -4), l.dropAt(far, 3, -4, t));
		MapLayout placed = new MapLayout(deep(), MapSettings.DEFAULT.withPlacement(far.key, new MapSettings.Free(3, -4)));
		placed.at(0, null, 0, 0);
		assertEquals(3, placed.get(P + "far").x(), 1e-12);
	}

	@Test
	void whileCarriedABodyStaysOnTheOrbitItWouldTake() {
		MapLayout l = new MapLayout(deep(), MapSettings.DEFAULT);
		l.at(0, null, 0, 0);
		MapLayout.Body you = l.get(P + "you");
		double wx = you.parent.x() + 3, wz = you.parent.z();
		l.at(0, you.key, wx, wz);
		assertEquals(wx, you.x(), 1e-9, "carried: under the pointer");
		MapLayout.Body chat = l.get(P + "chat");
		assertEquals(chat.k() * you.radius(), distance(chat, you), 1e-9, "and what orbits it comes along");
	}

	@Test
	void whatANodeReportsBeyondThoseKnownIsDrawnUnnamed() {
		PeerGraph g = new PeerGraph(List.of(
			node("v", Node.VENUE, Node.LINKED, null, Node.Fact.of("Users", "4")),
			node("u", Node.PERSON, Node.TRUSTED, "v")));
		MapLayout l = new MapLayout(g, MapSettings.DEFAULT);
		assertEquals(4, l.childCount(P + "v"));
		assertEquals(List.of(P + "u"), l.childrenOf(P + "v").stream().map(Node::did).toList(), "only the known are named");
		MapLayout.Body unnamed = l.get(P + "v#0");
		assertNotNull(unnamed);
		assertNull(unnamed.node);
		assertEquals("user", MapLayout.childNoun(g.get(P + "v")));
		assertEquals("agent", MapLayout.childNoun(g.get(P + "u")));
	}

	@Test
	void theSampleUniverseLaysOut() {
		MapLayout l = new MapLayout(new SamplePeerSource(true).graph(), MapSettings.DEFAULT);
		l.at(42, null, 0, 0);
		assertNotNull(l.home());
		assertEquals(2, l.childrenOf(l.home().key).size(), "Home has its admin and you");
		for (MapLayout.Body b : l.all()) {
			assertTrue(Double.isFinite(b.x()) && Double.isFinite(b.z()) && b.radius() > 0, b.toString());
		}
	}
}
