package brightside.voice;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * The microphone's audio, passed to whichever dictation is taking it — one
 * message's — and held while none is, between one message being sent and the
 * next beginning. The next takes what was held first, so a word said during the
 * hand-over is neither lost nor heard as part of the message before. Runs on the
 * recording's thread and the event thread alike.
 */
public final class Relay implements Consumer<byte[]> {

	private final ByteArrayOutputStream held = new ByteArrayOutputStream();
	private Consumer<byte[]> to;

	/** More audio, from the recording. */
	@Override
	public synchronized void accept(byte[] chunk) {
		if (to != null) to.accept(chunk);
		else held.write(chunk, 0, chunk.length);
	}

	/** Audio goes to {@code next} from now on, starting with whatever was held. */
	public synchronized void to(Consumer<byte[]> next) {
		if (held.size() > 0) next.accept(held.toByteArray());
		held.reset();
		to = next;
	}

	/** Audio is held from now on, until the next dictation takes it. */
	public synchronized void hold() {
		to = null;
	}

	/** Nothing more goes anywhere, and nothing held is kept. */
	public synchronized void drop() {
		to = chunk -> {
		};
		held.reset();
	}
}
