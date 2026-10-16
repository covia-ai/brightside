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
 * The mechanism behind the Peers map's settings: which orbit a node sits on,
 * where an unplaced node lands, how changes keep orbits sane, and that the
 * stored form round-trips and survives anything unexpected.
 */
class MapSettingsTest {

	private static Node node(String did, String relation) {
		return new Node(did, did, Node.PERSON, relation, Node.CONNECTED, null, List.of());
	}

	@Test
	void relationsChooseTheOrbitAndTheRestAreDrawnFarOut() {
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(0, s.orbitOf(node("did:example:a", Node.SELF)));
		assertEquals(1, s.orbitOf(node("did:example:b", Node.TRUSTED)));
		assertEquals(2, s.orbitOf(node("did:example:c", Node.USES)));
		assertEquals(3, s.orbitOf(node("did:example:d", Node.KNOWN)));
		assertEquals(-1, s.orbitOf(node("did:example:e", Node.LINKED)));
		assertEquals(-1, s.orbitOf(node("did:example:f", "something-new")), "an unknown relation is not forced onto an orbit");
		assertEquals(-1, s.orbitOf(node("did:example:g", null)));
	}

	@Test
	void anUnplacedNodeLandsInTheSamePlaceEveryTime() {
		Node a = node("did:example:alice", Node.TRUSTED);
		Node v = node("did:example:venue-3", Node.UNKNOWN);
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(s.planet(a), s.planet(a));
		assertEquals(s.planet(a), MapSettings.DEFAULT.planet(new Node(a.did(), "Renamed", Node.PERSON, Node.TRUSTED,
			Node.OFFLINE, null, List.of())), "placement depends on the DID alone, not on what the source says");
		assertEquals(0.54, s.planet(a).radius(), "on its relation's orbit");
		MapSettings.At at = s.venue(v);
		double distance = Math.hypot(at.x(), at.z());
		assertTrue(distance >= MapSettings.NEAR && distance <= MapSettings.FAR, "far out, within the universe");

		// Different DIDs spread out rather than piling up.
		Set<Double> angles = new HashSet<>();
		double lo = Double.MAX_VALUE, hi = 0;
		for (int i = 0; i < 50; i++) {
			double p = s.planet(node("did:example:n" + i, Node.TRUSTED)).phase();
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
		Node a = node("did:example:alice", Node.TRUSTED);
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
		MapSettings s = MapSettings.DEFAULT.withOrbitRadius(1, 5.0);
		assertEquals(0.74 - MapSettings.ORBIT_GAP, s.orbits().get(1).radius(), 1e-9, "stops short of the next orbit out");
		s = MapSettings.DEFAULT.withOrbitRadius(1, 0.0);
		assertEquals(0.34 + MapSettings.ORBIT_GAP, s.orbits().get(1).radius(), 1e-9, "and of the next orbit in");
		s = MapSettings.DEFAULT.withOrbitRadius(3, 1.4);
		assertEquals(1.4, s.orbits().get(3).radius(), 1e-9);
		assertTrue(s.rearranged());
		assertEquals(1.4, s.planet(node("did:example:k", Node.KNOWN)).radius(), 1e-9, "unplaced planets follow their orbit");
		assertEquals(MapSettings.DEFAULT.orbits(), s.reset().orbits());
	}

	@Test
	void theStoredFormRoundTripsAndHoldsNoPeerData() {
		MapSettings s = MapSettings.DEFAULT
			.withOrbitRadius(2, 0.8)
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
		PeerGraph g = new SamplePeerSource().graph();
		Set<String> dids = new HashSet<>();
		for (Node n : g.nodes()) {
			assertTrue(dids.add(n.did()), "unique DIDs");
			assertTrue(n.did().startsWith("did:example:"), "samples use the example DID method: " + n.did());
			if (n.belongsTo() != null) assertNotNull(g.owner(n), n.did() + " belongs to a node in the graph");
			if (n.is(Node.AGENT)) {
				assertEquals(-1, MapSettings.DEFAULT.orbitOf(n), "agents are moons, not planets");
				assertTrue(MapSettings.DEFAULT.orbitOf(g.owner(n)) >= 0, "every sample agent belongs to a planet");
			}
		}
		assertEquals(3, g.partsOf("did:example:transcriber").size());
		assertTrue(new SamplePeerSource().sample());
	}
}
