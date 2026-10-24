package brightside.voice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Plays a reply aloud through the computer's default output, one reply at a
 * time: a new one, or {@link #stop()}, ends the one playing. A reply is said in
 * parts, each made while the one before plays, so the first words come as soon
 * as they are ready. Everything runs on a thread of its own; nothing on the
 * Swing event thread waits for audio.
 *
 * <p>While it is {@linkplain #speaking() speaking}, and for a moment after for
 * what the room still carries, the microphone hears silence: a reply read aloud
 * is never dictated back as the owner's words ({@link Microphone}).
 */
public final class Speaker {

	/** Where audio goes: the default output in use, a stand-in in tests. */
	interface Output {
		Line open(AudioFormat format) throws LineUnavailableException;
	}

	/** One open output: written to, drained to the end, or cut off. */
	interface Line {
		void write(byte[] pcm, int offset, int length);
		void drain();
		/** Stops at once, dropping what is not yet played, and frees a write waiting on it; safe from another thread. */
		void cut();
		void close();
	}

	/** How long the microphone stays hushed after the last word, for the echo of it. */
	static final long TAIL_MILLIS = 400;
	/** Written a few hundredths of a second at a time, so a stop is quick. */
	private static final int CHUNK = 4096;

	private final Output output;
	private final long tailNanos;
	private final ExecutorService player = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "brightside-speaker");
		t.setDaemon(true);
		return t;
	});
	/** Bumped by every reply and every stop: a part of an older one plays no further. */
	private long reply;
	private volatile boolean playing;
	private volatile long quietFrom;
	private volatile Line line;

	/** Plays through the default output. */
	public Speaker() {
		this(Speaker::defaultLine, TAIL_MILLIS);
	}

	Speaker(Output output, long tailMillis) {
		this.output = output;
		this.tailNanos = tailMillis * 1_000_000;
	}

	/**
	 * Says {@code parts} one after another, each made by {@code make} as WAV,
	 * after stopping whatever is playing. Completes when the last part has played
	 * or the reply was stopped; exceptionally if a part could not be made or played.
	 */
	public CompletableFuture<Void> say(List<String> parts, Function<String, CompletableFuture<byte[]>> make) {
		long mine;
		synchronized (this) {
			mine = ++reply;
			playing = !parts.isEmpty();
		}
		cut();
		CompletableFuture<Void> done = new CompletableFuture<>();
		if (parts.isEmpty()) {
			done.complete(null);
			return done;
		}
		// The first part is asked for now; each later one while the one before plays.
		CompletableFuture<byte[]> first = make.apply(parts.get(0));
		player.execute(() -> {
			Exception failure = null;
			try {
				CompletableFuture<byte[]> next = first;
				for (int i = 0; i < parts.size() && current(mine); i++) {
					byte[] wav = next.join();
					next = (i + 1 < parts.size()) ? make.apply(parts.get(i + 1)) : null;
					play(wav, mine);
				}
			} catch (RuntimeException | IOException | UnsupportedAudioFileException | LineUnavailableException e) {
				failure = e;
			}
			// Quiet before anyone waiting hears it is over.
			finished(mine);
			if (failure == null) done.complete(null);
			else done.completeExceptionally(failure);
		});
		return done;
	}

	/** Stops the reply playing, if any; the microphone hears again after the tail. */
	public void stop() {
		synchronized (this) {
			reply++;
			if (playing) quietFrom = System.nanoTime();
			playing = false;
		}
		cut();
	}

	/** Whether a reply is playing, or finished so lately that the room may still carry it. */
	public boolean speaking() {
		return playing || System.nanoTime() - quietFrom < tailNanos;
	}

	private synchronized boolean current(long mine) {
		return reply == mine;
	}

	/** A reply played to its end; one stopped, or replaced by another, is no longer this one's to end. */
	private synchronized void finished(long mine) {
		if (reply != mine) return;
		quietFrom = System.nanoTime();
		playing = false;
	}

	private void cut() {
		Line l = line;
		if (l != null) l.cut();
	}

	private void play(byte[] wav, long mine) throws IOException, UnsupportedAudioFileException, LineUnavailableException {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
			Line l = output.open(in.getFormat());
			line = l;
			try {
				byte[] buffer = new byte[CHUNK];
				int n;
				while (current(mine) && (n = in.read(buffer)) > 0) l.write(buffer, 0, n);
				if (current(mine)) l.drain();
			} finally {
				line = null;
				l.close();
			}
		}
	}

	private static Line defaultLine(AudioFormat format) throws LineUnavailableException {
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);
		line.start();
		return new Line() {
			@Override public void write(byte[] pcm, int offset, int length) { line.write(pcm, offset, length); }
			@Override public void drain() { line.drain(); }
			@Override public void cut() { line.stop(); line.flush(); line.close(); }
			@Override public void close() { line.close(); }
		};
	}
}
