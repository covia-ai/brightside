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
 * The mechanism behind the Peers map's settings: which ring a body sits on,
 * how changes keep rings sane, and that the stored form round-trips and
 * survives anything unexpected, an older version's included.
 */
class MapSettingsTest {

	private static Node node(String did, String relation) {
		return new Node(did, did, Node.PERSON, relation, Node.CONNECTED, null, List.of());
	}

	@Test
	void relationsChooseTheRing() {
		MapSettings s = MapSettings.DEFAULT;
		assertEquals(0, s.ringOf(node("did:example:a", Node.OPERATOR)), "the admin, innermost");
		assertEquals(1, s.ringOf(node("did:example:b", Node.SELF)));
		assertEquals(1, s.ringOf(node("did:example:c", Node.MEMBER)), "you and anyone else share the users' ring");
		assertEquals(-1, s.ringOf(node("did:example:f", "something-new")), "an unknown relation is not forced onto a ring");
		assertEquals(-1, s.ringOf(node("did:example:g", null)));
		assertEquals(-1, s.ringOf(null));
	}

	@Test
	void hashesAreStableAndSpread() {
		Set<Double> seen = new HashSet<>();
		double lo = 1, hi = 0;
		for (int i = 0; i < 200; i++) {
			double h = MapSettings.hash("did:example:n" + i, 7);
			assertTrue(h >= 0 && h < 1);
			assertEquals(h, MapSettings.hash("did:example:n" + i, 7), "the same every time");
			seen.add(h);
			lo = Math.min(lo, h);
			hi = Math.max(hi, h);
		}
		assertEquals(200, seen.size(), "no two alike");
		assertTrue(lo < 0.1 && hi > 0.9, "they cover the range: " + lo + ".." + hi);
	}

	@Test
	void aPlacementWinsUntilReset() {
		MapSettings s = MapSettings.DEFAULT
			.withPlacement("did:example:alice", new MapSettings.Orbit(6, 0.5))
			.withPlacement("did:example:far", new MapSettings.Free(-7, 2));
		assertEquals(new MapSettings.Orbit(6, 0.5), s.placements().get("did:example:alice"));
		assertTrue(s.rearranged());
		assertFalse(s.withoutPlacements().rearranged());
		MapSettings back = s.withoutPlacement("did:example:alice");
		assertNull(back.placements().get("did:example:alice"));
		assertNotNull(back.placements().get("did:example:far"), "the rest stay where they were left");
		assertSame(back, back.withoutPlacement("did:example:alice"), "putting back what was never moved changes nothing");
	}

	@Test
	void ringsKeepTheirOrderWhenResized() {
		MapSettings s = MapSettings.DEFAULT.withRingRadius(0, 50);
		assertEquals(8.9 - MapSettings.RING_GAP, s.rings().get(0).radius(), 1e-9, "stops short of the next ring out");
		s = MapSettings.DEFAULT.withRingRadius(1, 0);
		assertEquals(4.8 + MapSettings.RING_GAP, s.rings().get(1).radius(), 1e-9, "and of the next ring in");
		s = MapSettings.DEFAULT.withRingRadius(1, 99);
		assertEquals(MapSettings.MAX_K, s.rings().get(1).radius(), 1e-9, "and the outermost of the edge");
		s = MapSettings.DEFAULT.withRingRadius(1, 12);
		assertTrue(s.rearranged());
		assertEquals(MapSettings.DEFAULT.rings(), s.reset().rings());
	}

	@Test
	void theStoredFormRoundTripsAndHoldsNoPeerData() {
		MapSettings s = MapSettings.DEFAULT
			.withRingRadius(1, 10)
			.withPlacement("did:example:alice", new MapSettings.Orbit(6.5, -2.25))
			.withPlacement("did:example:far", new MapSettings.Free(3.5, -9))
			.withView(new MapSettings.View(0.25, true).tracking("did:example:alice"));
		ACell cell = s.toCell();
		assertEquals(s, MapSettings.fromCell(cell));
		assertEquals(s, MapSettings.fromCell(JSON.parse(JSON.toString(cell))), "through text and back, as it might be exported");
		assertFalse(JSON.toString(cell).contains("Alice"), "a node's name never reaches the settings");
	}

	@Test
	void anythingUnexpectedFallsBackRatherThanFailing() {
		assertSame(MapSettings.DEFAULT, MapSettings.fromCell(null));
		assertSame(MapSettings.DEFAULT, MapSettings.fromCell(Strings.create("not settings")));
		MapSettings odd = MapSettings.fromCell(Maps.of(
			"version", MapSettings.VERSION,
			"rings", Vectors.of(Maps.of("name", "Out of order", "radius", 9), Maps.of("name", "In", "radius", 3)),
			"placements", Maps.of("did:example:a", Maps.of("around", "far"), "did:example:b", Maps.of("x", 1, "z", 2),
				"did:example:c", Maps.of("around", 999, "phase", 0)),
			"view", Maps.of("zoom", -3)));
		assertEquals(MapSettings.DEFAULT.rings(), odd.rings(), "rings out of order are replaced by the defaults");
		assertNull(odd.placements().get("did:example:a"), "a malformed placement is dropped");
		assertEquals(new MapSettings.Free(1, 2), odd.placements().get("did:example:b"), "whole numbers are fine");
		assertEquals(new MapSettings.Orbit(MapSettings.MAX_K, 0), odd.placements().get("did:example:c"), "kept in reach");
		assertEquals(1, odd.view().zoom(), "a nonsense zoom is the default");
	}

	@Test
	void anOlderVersionKeepsWhatStillMeansTheSame() {
		// Version 2 placed planets in world units and kept its own orbits: those go; free positions, orbits
		// round an owner, the view and what it tracked carry over.
		MapSettings old = MapSettings.fromCell(Maps.of(
			"version", 2,
			"orbits", Vectors.of(Maps.of("name", "Admin", "relations", Vectors.of("operator"), "radius", 0.42)),
			"placements", Maps.of(
				"did:example:planet", Maps.of("radius", 0.6, "phase", 1),
				"did:example:moon", Maps.of("around", 3.5, "phase", 2),
				"did:example:far", Maps.of("x", 4, "z", -6)),
			"view", Maps.of("zoom", 0.5, "track", "did:example:moon")));
		assertEquals(MapSettings.DEFAULT.rings(), old.rings());
		assertNull(old.placements().get("did:example:planet"));
		assertEquals(new MapSettings.Orbit(3.5, 2), old.placements().get("did:example:moon"));
		assertEquals(new MapSettings.Free(4, -6), old.placements().get("did:example:far"));
		assertEquals(0.5, old.view().zoom());
		assertEquals("did:example:moon", old.view().track());
	}

	@Test
	void placementsAndTrackingForNodesNoLongerReportedArePruned() {
		PeerGraph graph = new PeerGraph(List.of(node("did:example:kept", Node.TRUSTED)));
		MapSettings s = MapSettings.DEFAULT
			.withPlacement("did:example:kept", new MapSettings.Orbit(3, 1))
			.withPlacement("did:example:gone", new MapSettings.Orbit(3, 1))
			.withView(new MapSettings.View(1, false).tracking("did:example:kept"));
		MapSettings pruned = s.prune(graph);
		assertNotNull(pruned.placements().get("did:example:kept"));
		assertNull(pruned.placements().get("did:example:gone"));
		assertEquals("did:example:kept", pruned.view().track(), "still there: still followed");
		assertSame(pruned, pruned.prune(graph), "nothing to prune leaves it as it is");
		assertNotEquals(s, pruned);
		assertNull(s.prune(PeerGraph.EMPTY).view().track(), "gone: no longer followed");
	}

	@Test
	void theSampleGraphHangsTogether() {
		PeerGraph g = new SamplePeerSource(true).graph();
		Set<String> dids = new HashSet<>();
		for (Node n : g.nodes()) {
			assertTrue(dids.add(n.did()), "unique DIDs");
			assertTrue(n.did().startsWith("did:example:"), "samples use the example DID method: " + n.did());
			if (n.belongsTo() != null) assertNotNull(g.owner(n), n.did() + " belongs to a node in the graph");
			if (n.is(Node.PERSON)) assertTrue(g.owner(n).is(Node.VENUE), "users belong to a venue: " + n.did());
			if (n.is(Node.AGENT)) assertTrue(g.owner(n).is(Node.PERSON), "agents belong to a user: " + n.did());
		}
		// Your venue has two users: its admin and you.
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
