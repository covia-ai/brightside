package brightside.peers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.JSON;

/**
 * The mechanism behind the Peers map's settings: which orbit a user of your
 * venue sits on,
 * where an unplaced node lands, how changes keep orbits sane, and that the
 * stored form round-trips and survives anything unexpected.
 */
class MapSettingsTest {

	private static Node node(String did, String relation) {
		return new Node(did, did, Node.PERSON, relation, Node.CONNECTED, null, List.of());
	}

	@Test
	void relationsChooseTheOrbit() {
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(0, s.orbitOf(node("did:example:a", Node.OPERATOR)), "the admin, innermost");
		assertEquals(1, s.orbitOf(node("did:example:b", Node.SELF)));
		assertEquals(1, s.orbitOf(node("did:example:c", Node.MEMBER)), "you and anyone else share the users' orbit");
		assertEquals(-1, s.orbitOf(node("did:example:f", "something-new")), "an unknown relation is not forced onto an orbit");
		assertEquals(-1, s.orbitOf(node("did:example:g", null)));
		assertEquals(s.orbits().getLast().radius(), s.planet(node("did:example:f", "something-new")).radius(),
			"and is drawn on the outermost");
	}

	@Test
	void anUnplacedNodeLandsInTheSamePlaceEveryTime() {
		Node a = node("did:example:alice", Node.SELF);
		Node v = node("did:example:venue-3", Node.UNKNOWN);
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(s.planet(a), s.planet(a));
		assertEquals(s.planet(a), MapSettings.DEFAULT.planet(new Node(a.did(), "Renamed", Node.PERSON, Node.SELF,
			Node.OFFLINE, null, List.of())), "placement depends on the DID alone, not on what the source says");
		assertEquals(0.78, s.planet(a).radius(), "on its relation's orbit");
		MapSettings.At at = s.venue(v);
		double distance = Math.hypot(at.x(), at.z());
		assertTrue(distance >= MapSettings.NEAR && distance <= MapSettings.FAR, "far out, within the universe");

		// Different DIDs spread out rather than piling up.
		Set<Double> angles = new HashSet<>();
		double lo = Double.MAX_VALUE, hi = 0;
		for (int i = 0; i < 50; i++) {
			double p = s.planet(node("did:example:n" + i, Node.MEMBER)).phase();
			angles.add(p);
			lo = Math.min(lo, p);
			hi = Math.max(hi, p);
		}
		assertEquals(50, angles.size(), "no two land on the same angle");
		assertTrue(lo < 1 && hi > 2 * Math.PI - 1, "they cover the whole circle: " + lo + ".." + hi);
		for (int i = 0; i < 200; i++) {
			double h = MapSettings.hash("did:example:n" + i, 7);
			assertTrue(h >= 0 && h < 1);
		}
	}

	@Test
	void aPlacementWinsUntilReset() {
		Node a = node("did:example:alice", Node.SELF);
		Node v = node("did:example:far", Node.LINKED);
		MapSettings s = MapSettings.DEFAULT
			.withPlacement(a.did(), new MapSettings.OnOrbit(1.3, 0.5))
			.withPlacement(v.did(), new MapSettings.At(-7, 2));
		assertEquals(new MapSettings.OnOrbit(1.3, 0.5), s.planet(a));
		assertEquals(new MapSettings.At(-7, 2), s.venue(v));
		assertTrue(s.rearranged());
		MapSettings back = s.withoutPlacements();
		assertEquals(MapSettings.DEFAULT.planet(a), back.planet(a));
		assertFalse(back.rearranged());
	}

	@Test
	void orbitsKeepTheirOrderWhenResized() {
		MapSettings s = MapSettings.DEFAULT.withOrbitRadius(0, 5.0);
		assertEquals(0.78 - MapSettings.ORBIT_GAP, s.orbits().get(0).radius(), 1e-9, "stops short of the next orbit out");
		s = MapSettings.DEFAULT.withOrbitRadius(1, 0.0);
		assertEquals(0.42 + MapSettings.ORBIT_GAP, s.orbits().get(1).radius(), 1e-9, "and of the next orbit in");
		s = MapSettings.DEFAULT.withOrbitRadius(1, 9);
		assertEquals(MapSettings.MAX_RADIUS, s.orbits().get(1).radius(), 1e-9, "and the outermost of the edge");
		s = MapSettings.DEFAULT.withOrbitRadius(1, 1.4);
		assertEquals(1.4, s.orbits().get(1).radius(), 1e-9);
		assertTrue(s.rearranged());
		assertEquals(1.4, s.planet(node("did:example:k", Node.MEMBER)).radius(), 1e-9, "unplaced planets follow their orbit");
		assertEquals(MapSettings.DEFAULT.orbits(), s.reset().orbits());
	}

	@Test
	void theStoredFormRoundTripsAndHoldsNoPeerData() {
		MapSettings s = MapSettings.DEFAULT
			.withOrbitRadius(1, 0.9)
			.withPlacement("did:example:alice", new MapSettings.OnOrbit(1.1, -2.25))
			.withPlacement("did:example:far", new MapSettings.At(3.5, -9))
			.withView(new MapSettings.View(0.25, true));
		ACell cell = s.toCell();
		assertEquals(s, MapSettings.fromCell(cell));
		// Through text and back, as it might be exported.
		assertEquals(s, MapSettings.fromCell(JSON.parse(JSON.toString(cell))));
		String text = JSON.toString(cell);
		assertFalse(text.contains("Alice"), "a node's name never reaches the settings: " + text);
	}

	@Test
	void anythingUnexpectedFallsBackRatherThanFailing() {
		assertSame(MapSettings.DEFAULT, MapSettings.fromCell(null));
		assertSame(MapSettings.DEFAULT, MapSettings.fromCell(Strings.create("not settings")));
		MapSettings odd = MapSettings.fromCell(Maps.of(
			"version", 99,
			"orbits", Vectors.of(Maps.of("name", "Out of order", "radius", 0.9), Maps.of("name", "In", "radius", 0.3)),
			"placements", Maps.of("did:example:a", Maps.of("radius", "far"), "did:example:b", Maps.of("x", 1, "z", 2)),
			"view", Maps.of("zoom", -3)));
		assertEquals(MapSettings.DEFAULT.orbits(), odd.orbits(), "orbits out of order are replaced by the defaults");
		assertNull(odd.placements().get("did:example:a"), "a malformed placement is dropped");
		assertEquals(new MapSettings.At(1, 2), odd.placements().get("did:example:b"), "whole numbers are fine");
		assertEquals(1, odd.view().zoom(), "a nonsense zoom is the default");
	}

	@Test
	void aMoonKeepsItsOwnOrbitRoundItsPlanet() {
		Node agent = new Node("did:example:you/g/chat", "Chat", Node.AGENT, null, Node.CONNECTED, "did:example:you", List.of());
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(MapSettings.FIRST_MOON, s.moon(agent, 0).radius(), "unplaced, the first moon out");
		assertEquals(MapSettings.FIRST_MOON + 2 * MapSettings.MOON_SPACING, s.moon(agent, 2).radius(), 1e-9);
		assertEquals(s.moon(agent, 0).phase(), s.moon(agent, 2).phase(), "the angle comes from the DID alone");
		s = s.withPlacement(agent.did(), new MapSettings.Moon(5.5, 1.25));
		assertEquals(new MapSettings.Moon(5.5, 1.25), s.moon(agent, 0));
		assertEquals(s, MapSettings.fromCell(s.toCell()), "round-trips");
		MapSettings far = MapSettings.fromCell(Maps.of("version", MapSettings.VERSION,
			"placements", Maps.of(agent.did(), Maps.of("around", 99, "phase", 0))));
		assertEquals(MapSettings.MAX_MOON, far.moon(agent, 0).radius(), "kept within reach of its planet");
	}

	@Test
	void theBodyFollowedIsRememberedUntilItGoes() {
		MapSettings s = MapSettings.DEFAULT.withView(new MapSettings.View(1.5, false).tracking("did:example:you"));
		assertEquals("did:example:you", MapSettings.fromCell(s.toCell()).view().track(), "kept with the view");
		assertNull(MapSettings.fromCell(MapSettings.DEFAULT.toCell()).view().track(), "nothing followed by default");
		PeerGraph graph = new PeerGraph(List.of(node("did:example:you", Node.SELF)));
		assertSame(s, s.prune(graph), "still there: still followed");
		assertNull(s.prune(PeerGraph.EMPTY).view().track(), "gone: no longer followed");
	}

	@Test
	void oneBodyCanBePutBack() {
		MapSettings s = MapSettings.DEFAULT
			.withPlacement("did:example:a", new MapSettings.OnOrbit(1, 1))
			.withPlacement("did:example:b", new MapSettings.At(3, 4));
		MapSettings back = s.withoutPlacement("did:example:a");
		assertNull(back.placements().get("did:example:a"));
		assertNotNull(back.placements().get("did:example:b"), "the rest stay where they were left");
		assertSame(back, back.withoutPlacement("did:example:a"), "putting back what was never moved changes nothing");
	}

	@Test
	void orbitsFromAnotherVersionGiveWayToTheDefaults() {
		// Version 1 drew relations to you on your orbits; its orbits mean nothing now, its placements still do.
		MapSettings old = MapSettings.fromCell(Maps.of(
			"version", 1,
			"orbits", Vectors.of(Maps.of("name", "Your devices", "relations", Vectors.of("self"), "radius", 0.34),
				Maps.of("name", "People you trust", "relations", Vectors.of("trusted"), "radius", 0.54)),
			"placements", Maps.of("did:example:far", Maps.of("x", 4, "z", -6)),
			"view", Maps.of("zoom", 0.5)));
		assertEquals(MapSettings.DEFAULT.orbits(), old.orbits());
		assertEquals(new MapSettings.At(4, -6), old.placements().get("did:example:far"));
		assertEquals(0.5, old.view().zoom());
		MapSettings now = MapSettings.DEFAULT.withOrbitRadius(1, 1.2);
		assertEquals(now, MapSettings.fromCell(now.toCell()), "this version's own orbits are kept");
	}

	@Test
	void placementsForNodesNoLongerReportedArePruned() {
		PeerGraph graph = new PeerGraph(List.of(node("did:example:kept", Node.TRUSTED)));
		MapSettings s = MapSettings.DEFAULT
			.withPlacement("did:example:kept", new MapSettings.OnOrbit(1, 1))
			.withPlacement("did:example:gone", new MapSettings.OnOrbit(1, 1));
		MapSettings pruned = s.prune(graph);
		assertNotNull(pruned.placements().get("did:example:kept"));
		assertNull(pruned.placements().get("did:example:gone"));
		assertSame(pruned, pruned.prune(graph), "nothing to prune leaves it as it is");
		assertNotEquals(s, pruned);
	}

	@Test
	void theSampleGraphHangsTogether() {
		PeerGraph g = new SamplePeerSource(true).graph();
		Set<String> dids = new HashSet<>();
		for (Node n : g.nodes()) {
			assertTrue(dids.add(n.did()), "unique DIDs");
			assertTrue(n.did().startsWith("did:example:"), "samples use the example DID method: " + n.did());
			if (n.belongsTo() != null) assertNotNull(g.owner(n), n.did() + " belongs to a node in the graph");
			if (n.is(Node.PERSON)) assertTrue(g.owner(n).is(Node.VENUE), "users are planets of a venue: " + n.did());
			if (n.is(Node.AGENT)) assertTrue(g.owner(n).is(Node.PERSON), "agents are moons of a user: " + n.did());
		}
		// Your venue has two planets: its admin and you.
		Node home = g.home();
		assertNotNull(home);
		List<Node> users = g.partsOf(home.did());
		assertEquals(List.of(Node.OPERATOR, Node.SELF), users.stream().map(Node::relation).toList());
		assertEquals(home.did() + "#operator", users.getFirst().did(), "the admin is the venue itself, keyed under its DID");
		assertEquals(3, g.partsOf("did:example:transcribe-venue#operator").size());
		assertNull(new SamplePeerSource().graph().home(), "without a home, the samples stand beside a live one");
		assertTrue(new SamplePeerSource().sample());
	}
}
