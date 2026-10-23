package brightside.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * The computer's default microphone, recorded as 16-bit little-endian mono PCM —
 * what the speech adapter takes as {@code audio/L16} — on a thread of its own, so
 * nothing on the Swing event thread waits for audio. It records at 16 kHz, the
 * rate speech models hear at, when the microphone offers it, and otherwise at the
 * first common rate it does, which the venue resamples. A recording ends when it
 * is stopped, discarded or {@link #MAX_SECONDS} long; nothing is written anywhere.
 */
public final class Microphone {

	/** What a recording heard: raw PCM at {@code sampleRate}. */
	public record Clip(byte[] pcm, int sampleRate) {
		public double seconds() {
			return pcm.length / (2.0 * sampleRate);
		}
	}

	/** The longest dictation: three minutes, inside what one transcription takes at any of {@link #RATES}. */
	public static final int MAX_SECONDS = 180;
	/** The rates asked for, in order: speech's own first, then what microphones commonly offer. */
	static final float[] RATES = { 16_000f, 48_000f, 44_100f, 32_000f, 22_050f };
	public static final String NO_MICROPHONE = "No microphone is available";

	private Microphone() {
	}

	/**
	 * Starts recording from the default microphone. {@code onLimit} runs, on the
	 * recording's own thread, when it reaches {@link #MAX_SECONDS} and stops
	 * listening by itself; {@link Recording#stop()} then returns what it heard.
	 */
	public static Recording record(Runnable onLimit) throws LineUnavailableException {
		for (float rate : RATES) {
			AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
			try {
				if (!AudioSystem.isLineSupported(info)) continue;
				TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
				line.open(format);
				line.start();
				return new Recording(new AudioInputStream(line), (int) rate, MAX_SECONDS, line::stop, line::close, onLimit);
			} catch (IllegalArgumentException e) {
				// No line in this format after all: try the next rate.
			}
		}
		throw new LineUnavailableException(NO_MICROPHONE);
	}

	/** One recording in progress. */
	public static final class Recording {

		private final AudioInputStream in;
		private final int rate;
		private final int limit;
		private final Runnable halt;
		private final Runnable release;
		private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		private final Thread reader;
		private volatile boolean ending;
		private volatile int recorded;
		private volatile IOException failure;

		/**
		 * @param halt    stops the source, so a read waiting on it returns
		 * @param release frees the source once reading is over
		 */
		Recording(AudioInputStream in, int rate, int maxSeconds, Runnable halt, Runnable release, Runnable onLimit) {
			this.in = in;
			this.rate = rate;
			this.limit = rate * 2 * maxSeconds;
			this.halt = halt;
			this.release = release;
			this.reader = Thread.ofPlatform().daemon().name("brightside-microphone").start(() -> read(onLimit));
		}

		private void read(Runnable onLimit) {
			// A tenth of a second at a time. A halted source hands over what it
			// still holds and then has nothing, so the last words are kept.
			byte[] buffer = new byte[rate / 10 * 2];
			try {
				while (true) {
					int n = in.read(buffer, 0, Math.min(buffer.length, limit - pcm.size()));
					if (n < 0 || (n == 0 && ending)) break;
					pcm.write(buffer, 0, n);
					recorded = pcm.size();
					if (pcm.size() >= limit) {
						halt.run();
						onLimit.run();
						break;
					}
				}
			} catch (IOException e) {
				failure = e;
			}
		}

		/** How much has been heard so far. */
		public double seconds() {
			return recorded / (2.0 * rate);
		}

		/** Stops listening and returns what was heard. */
		public Clip stop() throws IOException {
			end();
			if (failure != null) throw failure;
			return new Clip(pcm.toByteArray(), rate);
		}

		/** Stops listening and keeps nothing. */
		public void discard() {
			end();
			pcm.reset();
		}

		private void end() {
			ending = true;
			halt.run();
			boolean interrupted = false;
			while (reader.isAlive()) {
				try {
					reader.join();
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
			release.run();
			if (interrupted) Thread.currentThread().interrupt();
		}
	}
}
