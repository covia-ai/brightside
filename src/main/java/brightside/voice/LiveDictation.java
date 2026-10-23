package brightside.voice;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The words while the owner is still speaking. The recording's audio goes to the
 * engine's live dictation as it gathers, about half a second at a time and one
 * request at a time, so more gathers while one is under way and the engine is
 * never asked twice at once. Each answer — the words settled at the last pause,
 * then those still being spoken — goes to {@code onHeard}, in order. Once the
 * recording has stopped, {@link #finish()} sends what is left and completes with
 * the settled words, which are the dictation.
 */
public final class LiveDictation {

	/** How the audio reaches the engine: {@link Voice#hear}, bound to one dictation. */
	public interface Ear {
		CompletableFuture<Voice.Heard> hear(byte[] pcm, boolean end);
	}

	/** About how much audio each request carries. */
	static final double SEND_SECONDS = 0.5;

	private final Ear ear;
	private final int rate;
	private final int threshold;
	private final Consumer<Voice.Heard> onHeard;
	private final CompletableFuture<String> done = new CompletableFuture<>();
	private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
	/** Everything it was given, should the whole of it need transcribing after all. */
	private final ByteArrayOutputStream heard = new ByteArrayOutputStream();
	private boolean sending;
	private boolean ending;
	private boolean endSent;
	private boolean discarded;
	private Throwable failure;

	/** @param sampleRate the rate of the raw PCM {@link #hear} is given */
	public LiveDictation(Ear ear, int sampleRate, Consumer<Voice.Heard> onHeard) {
		this.ear = ear;
		this.rate = sampleRate;
		this.threshold = (int) (sampleRate * 2 * SEND_SECONDS);
		this.onHeard = onHeard;
	}

	/** More of the recording, as it is heard. */
	public void hear(byte[] pcm) {
		synchronized (this) {
			if (ending) return;
			pending.write(pcm, 0, pcm.length);
			heard.write(pcm, 0, pcm.length);
		}
		pump();
	}

	/** How much of the recording it has been given. */
	public synchronized double seconds() {
		return heard.size() / (2.0 * rate);
	}

	/** All of the recording it was given, to be transcribed whole should the live words fail. */
	public synchronized Microphone.Clip clip() {
		return new Microphone.Clip(heard.toByteArray(), rate);
	}

	/**
	 * The recording is over: what is left goes with the end, and the result is the
	 * settled words. It fails if any request failed, since some of the words were
	 * then never heard; the end still goes, so the engine lets the dictation go.
	 */
	public CompletableFuture<String> finish() {
		synchronized (this) {
			ending = true;
		}
		pump();
		return done;
	}

	/** Ends the dictation, keeping nothing and telling nobody of what it heard. */
	public void discard() {
		synchronized (this) {
			ending = true;
			discarded = true;
			pending.reset();
			heard.reset();
		}
		pump();
	}

	/** Sends what has gathered, when nothing else is on its way. */
	private void pump() {
		byte[] chunk;
		boolean end;
		synchronized (this) {
			if (sending || endSent) return;
			end = ending;
			if (!end && (failure != null || pending.size() < threshold)) return;
			// After a failure only the end goes, empty: the words are lost either way.
			chunk = (failure != null) ? new byte[0] : pending.toByteArray();
			pending.reset();
			sending = true;
			endSent = end;
		}
		ear.hear(chunk, end).whenComplete((heard, f) -> {
			boolean tell;
			Throwable failed;
			synchronized (this) {
				sending = false;
				if (f != null && failure == null) failure = f;
				failed = failure;
				tell = failed == null && !discarded;
			}
			if (tell) onHeard.accept(heard);
			if (!end) {
				pump();
			} else if (failed != null) {
				done.completeExceptionally(failed);
			} else {
				done.complete(heard.settled());
			}
		});
	}
}
