package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.junit.jupiter.api.Test;

/**
 * A recording reads its source on a thread of its own: stopping keeps
 * everything heard up to then and frees the source, and a recording that
 * reaches its limit stops listening by itself and says so. A stand-in source
 * plays the microphone, so no audio device is needed.
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
	void aRecordingAtItsLimitStopsListeningByItself() throws Exception {
		CountDownLatch limit = new CountDownLatch(1);
		Microphone.Recording recording = new Microphone.Recording(source(new byte[40_000], 8_000), 8_000, 1,
			() -> { }, () -> { }, limit::countDown);

		limit.await();
		assertEquals(1.0, recording.seconds(), "one second at 8 kHz");
		assertEquals(16_000, recording.stop().pcm().length, "and nothing past it");
	}
}
