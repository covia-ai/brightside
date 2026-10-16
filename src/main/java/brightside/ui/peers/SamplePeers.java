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

	private SamplePeers() {
	}
}
