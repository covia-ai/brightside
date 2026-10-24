package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;

/**
 * A reply is played part after part, each made while the one before plays; a
 * stop or a newer reply cuts it off, and nothing of it plays after; while it
 * plays the speaker says so, for the microphone to hear silence. A stand-in
 * output plays the speakers, so nothing is heard.
 */
class SpeakerTest {

	/** An output that keeps what it is given; with {@code gate}, the first write waits until it opens or the line is cut. */
	static final class Kept implements Speaker.Output {
		final ByteArrayOutputStream played = new ByteArrayOutputStream();
		final List<AudioFormat> formats = new CopyOnWriteArrayList<>();
		final CountDownLatch writing = new CountDownLatch(1);
		volatile CountDownLatch gate;

		@Override
		public Speaker.Line open(AudioFormat format) {
			formats.add(format);
			return new Speaker.Line() {
				final CountDownLatch cut = new CountDownLatch(1);

				@Override
				public void write(byte[] pcm, int offset, int length) {
					writing.countDown();
					CountDownLatch g = gate;
					if (g != null) {
						try {
							while (g.getCount() > 0 && cut.getCount() > 0) g.await(1, TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						if (cut.getCount() == 0) return;
					}
					synchronized (played) {
						played.write(pcm, offset, length);
					}
				}

				@Override public void drain() { }
				@Override public void cut() { cut.countDown(); }
				@Override public void close() { }
			};
		}

		byte[] played() {
			synchronized (played) {
				return played.toByteArray();
			}
		}
	}

	static byte[] pcm(int samples, int seed) {
		byte[] pcm = new byte[samples * 2];
		for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * seed);
		return pcm;
	}

	static byte[] wav(byte[] pcm, int rate) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII))
			.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
			.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
		byte[] out = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, out, 0, 44);
		System.arraycopy(pcm, 0, out, 44, pcm.length);
		return out;
	}

	@Test
	void aReplyIsPlayedPartAfterPartAtItsOwnRate() throws Exception {
		Kept out = new Kept();
		Speaker speaker = new Speaker(out, 0);
		byte[] first = pcm(3_000, 3);
		byte[] second = pcm(5_000, 7);
		List<String> made = new CopyOnWriteArrayList<>();

		speaker.say(List.of("One.", "Two."), text -> {
			made.add(text);
			return CompletableFuture.completedFuture(wav(text.equals("One.") ? first : second, 44_100));
		}).get(10, TimeUnit.SECONDS);

		ByteArrayOutputStream both = new ByteArrayOutputStream();
		both.write(first);
		both.write(second);
		assertArrayEquals(both.toByteArray(), out.played(), "every sample of each part, in order");
		assertEquals(List.of("One.", "Two."), made);
		assertEquals(44_100f, out.formats.get(0).getSampleRate(), "at the rate the voice made it");
		assertFalse(speaker.speaking(), "finished");
		assertTrue(speaker.say(List.of(), text -> { throw new AssertionError("nothing to make"); }).isDone(),
			"a reply with nothing to read is over at once");
	}

	@Test
	void stoppingCutsTheReplyOffAndTheMicrophoneHearsAgain() throws Exception {
		Kept out = new Kept();
		out.gate = new CountDownLatch(1);
		Speaker speaker = new Speaker(out, 0);
		List<String> made = new CopyOnWriteArrayList<>();

		CompletableFuture<Void> reply = speaker.say(List.of("One.", "Two.", "Three."), text -> {
			made.add(text);
			return CompletableFuture.completedFuture(wav(pcm(1_000, 5), 24_000));
		});
		out.writing.await(10, TimeUnit.SECONDS);
		assertTrue(speaker.speaking(), "speaking while a part plays, so the microphone hears silence");

		speaker.stop();
		reply.get(10, TimeUnit.SECONDS);
		assertFalse(speaker.speaking(), "and hears again once it stops");
		assertEquals(0, out.played().length, "the part being played is cut off");
		assertEquals(List.of("One.", "Two."), made, "the next part was being made; none after it is");
	}

	@Test
	void aNewReplyTakesThePlaceOfTheOnePlaying() throws Exception {
		Kept out = new Kept();
		out.gate = new CountDownLatch(1);
		Speaker speaker = new Speaker(out, 0);
		CompletableFuture<Void> older = speaker.say(List.of("Old."),
			text -> CompletableFuture.completedFuture(wav(pcm(1_000, 3), 24_000)));
		out.writing.await(10, TimeUnit.SECONDS);

		byte[] newer = pcm(2_000, 11);
		CompletableFuture<Void> latest = speaker.say(List.of("New."), text -> CompletableFuture.completedFuture(wav(newer, 24_000)));
		older.get(10, TimeUnit.SECONDS);
		out.gate.countDown();
		latest.get(10, TimeUnit.SECONDS);
		assertArrayEquals(newer, out.played(), "only the newer reply is heard");
		assertFalse(speaker.speaking());
	}

	@Test
	void theMicrophoneStaysHushedForTheTail() throws Exception {
		Speaker speaker = new Speaker(new Kept(), 60_000);
		assertFalse(speaker.speaking(), "silent before anything is said");
		speaker.say(List.of("Hi."), text -> CompletableFuture.completedFuture(wav(pcm(100, 1), 16_000))).get(10, TimeUnit.SECONDS);
		assertTrue(speaker.speaking(), "the room may still carry the last word");
	}
}
