package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/**
 * The microphone's audio reaches one message's dictation after another: what is
 * said while one message is going waits for the next, which hears it first, and
 * nothing reaches the message already sent.
 */
class RelayTest {

	@Test
	void audioBetweenMessagesWaitsForTheNext() {
		Relay relay = new Relay();
		ByteArrayOutputStream first = new ByteArrayOutputStream();
		ByteArrayOutputStream second = new ByteArrayOutputStream();

		relay.to(chunk -> first.write(chunk, 0, chunk.length));
		relay.accept(new byte[] { 1, 2 });
		relay.hold();
		relay.accept(new byte[] { 3 });
		relay.accept(new byte[] { 4, 5 });
		relay.to(chunk -> second.write(chunk, 0, chunk.length));
		relay.accept(new byte[] { 6 });

		assertArrayEquals(new byte[] { 1, 2 }, first.toByteArray(), "the message that went has only its own");
		assertArrayEquals(new byte[] { 3, 4, 5, 6 }, second.toByteArray(), "the next hears what waited, then the rest");
	}

	@Test
	void droppedAudioGoesNowhere() {
		Relay relay = new Relay();
		ByteArrayOutputStream next = new ByteArrayOutputStream();
		relay.hold();
		relay.accept(new byte[] { 1 });
		relay.drop();
		relay.accept(new byte[] { 2 });
		relay.to(chunk -> next.write(chunk, 0, chunk.length));
		assertEquals(0, next.size(), "nothing held or heard after the microphone closed");
	}
}
