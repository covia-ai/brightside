package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;

/**
 * A recording reads its source on a thread of its own: stopping keeps
 * everything heard up to then and frees the source, a listener hears it all
 * once however late it joins and then keeps it itself, a recording that reaches
 * its limit stops listening by itself and says so, and a streamed one has no
 * limit. A stand-in source plays the microphone, so no audio device is needed.
 */
class MicrophoneTest {

	private static AudioInputStream source(byte[] pcm, int rate) {
		AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
		return new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2);
	}

	@Test
	void stoppingKeepsWhatWasHeardAndFreesTheSource() throws Exception {
		byte[] pcm = new byte[8_000];
		for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
		AtomicInteger released = new AtomicInteger();
		AtomicInteger limits = new AtomicInteger();
		Microphone.Recording recording = new Microphone.Recording(source(pcm, 16_000), 16_000, 10,
			() -> { }, released::incrementAndGet, limits::incrementAndGet);

		Microphone.Clip clip = recording.stop();
		assertArrayEquals(pcm, clip.pcm(), "every word up to the stop");
		assertEquals(16_000, clip.sampleRate());
		assertEquals(0.25, clip.seconds());
		assertEquals(1, released.get(), "the source is freed once");
		assertEquals(0, limits.get(), "a short recording is nowhere near its limit");
	}

	@Test
	void aListenerHearsEverythingOnceWhenEverItJoins() throws Exception {
		byte[] pcm = new byte[32_000];
		for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 7);
		Microphone.Recording recording = new Microphone.Recording(source(pcm, 16_000), 16_000, 10,
			() -> { }, () -> { }, () -> { });
		ByteArrayOutputStream heard = new ByteArrayOutputStream();
		recording.onAudio(chunk -> heard.write(chunk, 0, chunk.length));

		assertEquals(0, recording.stop().pcm().length, "streamed audio is the listener's to keep");
		assertArrayEquals(pcm, heard.toByteArray(), "what came before it joined, then the rest, nothing twice");
	}

	@Test
	void aStreamedRecordingHasNoLimitOfItsOwn() throws Exception {
		AtomicInteger limits = new AtomicInteger();
		// The audio arrives only once the listener is there, as a microphone's does over time.
		PipedOutputStream speaker = new PipedOutputStream();
		AudioInputStream source = new AudioInputStream(new PipedInputStream(speaker),
			new AudioFormat(8_000, 16, 1, true, false), AudioSystem.NOT_SPECIFIED);
		Microphone.Recording recording = new Microphone.Recording(source, 8_000, 1, () -> { }, () -> { }, limits::incrementAndGet);
		ByteArrayOutputStream heard = new ByteArrayOutputStream();
		recording.onAudio(chunk -> heard.write(chunk, 0, chunk.length));
		speaker.write(new byte[40_000]);
		speaker.close();

		recording.stop();
		assertEquals(40_000, heard.size(), "five seconds through a one-second recording: it ran on");
		assertEquals(0, limits.get(), "an open microphone across messages stops only when told");
	}

	@Test
	void aRecordingAtItsLimitStopsListeningByItself() throws Exception {
		CountDownLatch limit = new CountDownLatch(1);
		Microphone.Recording recording = new Microphone.Recording(source(new byte[40_000], 8_000), 8_000, 1,
			() -> { }, () -> { }, limit::countDown);

		limit.await();
		assertEquals(1.0, recording.seconds(), "one second at 8 kHz");
		assertEquals(16_000, recording.stop().pcm().length, "and nothing past it");
	}

	@Test
	void whileAReplyIsReadAloudTheMicrophoneHearsSilenceForAsLong() throws Exception {
		byte[] pcm = new byte[8_000];
		for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i | 1);
		Microphone.Recording recording = new Microphone.Recording(source(pcm, 16_000), 16_000, 10,
			() -> { }, () -> { }, () -> { }, () -> true);
		ByteArrayOutputStream heard = new ByteArrayOutputStream();
		recording.onAudio(chunk -> heard.write(chunk, 0, chunk.length));

		recording.stop();
		assertArrayEquals(new byte[pcm.length], heard.toByteArray(),
			"nothing of what was playing is heard, and the time it took still passes");
	}
}
