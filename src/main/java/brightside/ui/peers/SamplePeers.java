package brightside.ui.peers;

import java.awt.Color;
import java.util.List;

/**
 * Made-up peers for the Peers preview, until venues meet venues. The rings
 * follow NETWORK.md's ladder outwards: your own devices, people you trust,
 * services and agents you have hired, and peers known but not connected.
 * Nothing here is read from, or sent to, any network.
 */
final class SamplePeers {

	enum Kind {
		DEVICE("Your device"), PERSON("Person"), SERVICE("Service"), DIRECTORY("Known peer");

		final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	enum Status {
		ONLINE("Connected"), IDLE("Idle"), OFFLINE("Offline"), KNOWN("Not connected");

		final String label;

		Status(String label) {
			this.label = label;
		}
	}

	/** An agent belonging to a peer: a moon of its planet. */
	record Moon(String name, Status status) {
	}

	/**
	 * One peer. {@code ring} is its orbit, 1 innermost; {@code size} is its
	 * radius relative to the others; {@code phase} where on the orbit it starts,
	 * in degrees; {@code period} seconds per orbit; {@code ringed} gives it
	 * rings. The rest is what the detail card shows.
	 */
	record Peer(String name, Kind kind, int ring, Status status, double size, Color hue, double phase,
			double period, boolean ringed, String handle, String reach, String since, List<String> canDo,
			List<String> shared, List<Moon> moons) {
	}

	/** What each ring holds, innermost first. */
	static final List<String> RINGS = List.of("Your devices", "People you trust", "Services you use",
		"Known, not connected");

	static final List<Peer> ALL = List.of(
		new Peer("Laptop", Kind.DEVICE, 1, Status.ONLINE, 0.85, new Color(0x5EC8F2), 40, 80, false,
			"this is you too", "Same network · 3 ms", "Linked 2 Sep",
			List.of("Everything: it is you"), List.of("Memory", "Conversations", "Skills"), List.of()),
		new Peer("Phone", Kind.DEVICE, 1, Status.IDLE, 0.7, new Color(0x7FE0C4), 220, 80, false,
			"this is you too", "Via relay · 48 ms", "Linked 19 Sep",
			List.of("Everything: it is you"), List.of("Memory", "Conversations"), List.of()),
		new Peer("Alice", Kind.PERSON, 2, Status.ONLINE, 1.15, new Color(0xF2A65E), 110, 140, false,
			"alice.brightside", "Direct · 22 ms", "Met 12 Aug, introduced by Sam",
			List.of("Read the Household space", "Ask your assistant about free time", "Message you freely"),
			List.of("Household", "Holiday 2027"),
			List.of(new Moon("Alice's assistant", Status.ONLINE), new Moon("Recipes", Status.IDLE))),
		new Peer("Sam", Kind.PERSON, 2, Status.IDLE, 0.95, new Color(0xE58BB4), 250, 140, false,
			"sam.brightside", "Direct · 61 ms", "Met 3 Jul",
			List.of("Read the Climbing space", "Message you freely"), List.of("Climbing"),
			List.of(new Moon("Sam's assistant", Status.IDLE))),
		new Peer("Priya", Kind.PERSON, 2, Status.OFFLINE, 0.9, new Color(0xB79CF2), 345, 140, false,
			"priya.brightside", "Last seen 4 days ago", "Met 28 Sep",
			List.of("Message you, once you reply"), List.of(), List.of(new Moon("Priya's assistant", Status.OFFLINE))),
		new Peer("Transcriber", Kind.SERVICE, 3, Status.ONLINE, 1.35, new Color(0xE8C66A), 20, 220, true,
			"transcribe.agents", "Direct · 35 ms", "Hired 1 Oct · £0.02 a minute",
			List.of("Take audio you send it", "Charge up to £2 a week"), List.of(),
			List.of(new Moon("Whisper large", Status.ONLINE), new Moon("Speaker split", Status.ONLINE),
				new Moon("Summary", Status.IDLE))),
		new Peer("Convex peer", Kind.SERVICE, 3, Status.ONLINE, 1.05, new Color(0x6FA8F7), 170, 220, false,
			"peer.convex.live", "Direct · 90 ms", "Since setup",
			List.of("Answer queries", "Carry your signed transactions"), List.of(), List.of()),
		new Peer("Research guild", Kind.DIRECTORY, 4, Status.KNOWN, 1.0, new Color(0x9AA6B8), 75, 320, true,
			"research.guild", "Not connected", "Vouched for by Alice",
			List.of("Nothing yet"), List.of(), List.of()),
		new Peer("Contract reviewer", Kind.DIRECTORY, 4, Status.KNOWN, 0.8, new Color(0xA8B48A), 285, 320, false,
			"review.legal", "Not connected", "Found in the directory",
			List.of("Nothing yet"), List.of(), List.of()));

	/**
	 * Another venue, seen from far out: a star system of its own. {@code x} and
	 * {@code z} place it on the plane, in units where your own outermost orbit
	 * is about 1; {@code radius} is its size in the same units. {@code peer}
	 * names the planet on your map that stands for it, when there is one;
	 * {@code linked} draws a link from you. Unknown venues have no name.
	 */
	record StarSystem(String name, String about, double x, double z, double radius, Color sun, int planets,
			int agents, Status status, boolean linked, String peer) {

		boolean known() {
			return name != null;
		}
	}

	static final List<StarSystem> SYSTEMS = List.of(
		new StarSystem("Alice's Brightside", "Alice's own venue; you're on her People orbit", 4.4, -2.7, 0.62,
			new Color(0xF6B26B), 5, 4, Status.ONLINE, true, "Alice"),
		new StarSystem("Sam's Brightside", "Sam's own venue", -3.9, 3.3, 0.55, new Color(0xF29FC5), 4, 2,
			Status.IDLE, true, "Sam"),
		new StarSystem("Priya's Brightside", "Priya's own venue, last seen 4 days ago", 6.8, 4.6, 0.5,
			new Color(0xC4B0F5), 3, 1, Status.OFFLINE, true, "Priya"),
		new StarSystem("Transcribe Agents", "A service venue: transcription, speaker separation, summaries", -6.9,
			-4.4, 0.8, new Color(0xF2D27A), 7, 12, Status.ONLINE, true, "Transcriber"),
		new StarSystem("Convex Protonet", "The public Convex network: peers that agree on shared state", 0.8, 9.6,
			1.15, new Color(0x7FB2FF), 11, 0, Status.ONLINE, true, "Convex peer"),
		new StarSystem("Research guild", "Agents that research for hire; vouched for by Alice", -11.5, 1.6, 0.9,
			new Color(0xB5BFCC), 8, 9, Status.KNOWN, false, "Research guild"),
		new StarSystem("Contract reviewers", "Legal review agents, found in the directory", 11.8, -6.4, 0.7,
			new Color(0xC2CC9E), 5, 6, Status.KNOWN, false, "Contract reviewer"),
		new StarSystem(null, null, -9.2, -9.6, 0.5, new Color(0xA9C6E8), 4, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, 14.5, 3.2, 0.45, new Color(0xE8B4A9), 3, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, -15.0, -3.5, 0.55, new Color(0xD7E8A9), 5, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, 8.6, 12.4, 0.4, new Color(0xA9E8D9), 2, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, -5.4, 12.8, 0.5, new Color(0xE8D5A9), 4, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, 3.0, -11.0, 0.45, new Color(0xC9A9E8), 3, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, 16.2, -11.2, 0.5, new Color(0xA9B8E8), 4, 0, Status.KNOWN, false, null),
		new StarSystem(null, null, -14.2, 10.8, 0.4, new Color(0xE8A9C6), 2, 0, Status.KNOWN, false, null));

	private SamplePeers() {
	}
}
