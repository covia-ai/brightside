package brightside.peers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Your venue as peer data: the sun, two planets (its admin and you) and their
 * agents as moons, and how it stands beside the sample venues.
 */
class HomePeersTest {

	private static final String VENUE = "did:web:home.example";
	private static final String YOU = VENUE + ":u:robin";

	private static List<Node> home(List<HomePeers.Agent> yours) {
		return HomePeers.of(VENUE, "Robin's Brightside", "Operator", List.of(new HomePeers.Agent("odin", "Odin", "SLEEPING")),
			YOU, "Robin", yours);
	}

	@Test
	void yourVenueHasItsAdminAndYouAsPlanets() {
		PeerGraph g = new PeerGraph(home(List.of(new HomePeers.Agent("chat", "Assistant", "RUNNING"),
			new HomePeers.Agent("old", "Old", "SUSPENDED"))));
		Node sun = g.home();
		assertEquals(VENUE, sun.did());
		List<Node> planets = g.partsOf(VENUE);
		assertEquals(List.of(VENUE + "#operator", YOU), planets.stream().map(Node::did).toList());
		assertEquals(List.of(Node.OPERATOR, Node.SELF), planets.stream().map(Node::relation).toList());
		assertEquals(0, MapSettings.DEFAULT.orbitOf(planets.get(0)), "the admin on the inner orbit");
		assertEquals(1, MapSettings.DEFAULT.orbitOf(planets.get(1)), "you on the users' orbit");
		assertTrue(planets.get(0).facts().contains(Node.Fact.of("DID", VENUE)), "the admin's card gives its real DID");

		// Moons: each agent belongs to the planet of the principal that owns it, keyed apart.
		assertEquals(List.of("Odin"), g.partsOf(VENUE + "#operator").stream().map(Node::label).toList());
		List<Node> yours = g.partsOf(YOU);
		assertEquals(List.of(YOU + "/g/chat", YOU + "/g/old"), yours.stream().map(Node::did).toList());
		assertEquals(List.of(Node.CONNECTED, Node.OFFLINE), yours.stream().map(Node::status).toList());
		assertEquals(Node.IDLE, g.partsOf(VENUE + "#operator").getFirst().status(), "asleep is idle");
	}

	@Test
	void beforeTheUserExistsThereIsJustTheAdmin() {
		List<Node> n = HomePeers.of(VENUE, "V", "Operator", List.of(), null, "Robin", List.of());
		assertEquals(1, new PeerGraph(n).partsOf(VENUE).size());
	}

	@Test
	void yourVenueStandsAmongTheSampleVenues() {
		PeerSource s = PeerSource.combined(new SamplePeerSource(), new LiveSource(() -> home(List.of())));
		s.refresh();
		PeerGraph g = s.graph();
		assertEquals(VENUE, g.home().did(), "one sun, and it is yours");
		assertEquals(1, g.nodes().stream().filter(n -> n.is(Node.VENUE) && Node.SELF.equals(n.relation())).count());
		assertTrue(g.get("did:example:alice-venue") != null, "the samples are still there");
		assertTrue(s.sample(), "still a preview while any source is a sample");
	}

	@Test
	void aLiveSourceReadsOnRefreshAndKeepsItsLastAnswer() {
		AtomicReference<List<Node>> next = new AtomicReference<>(home(List.of()));
		List<String> reads = new ArrayList<>();
		LiveSource s = new LiveSource(() -> {
			reads.add("read");
			List<Node> n = next.get();
			if (n == null) throw new IllegalStateException("venue not answering");
			return n;
		});
		assertSame(PeerGraph.EMPTY, s.graph(), "nothing until it is asked");
		assertFalse(s.sample());
		s.refresh();
		assertEquals(4, s.graph().size());
		s.graph();
		assertEquals(1, reads.size(), "the graph answers from the last read");
		next.set(null);
		s.refresh();
		assertEquals(4, s.graph().size(), "a failed read leaves the last answer");
	}
}
