package brightside.voice;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Venue;

/**
 * Speech to text for the chat, through Covia's speech adapter on the embedded
 * venue, as the owner. The engine is the venue's ({@code venue.adapters.speech}
 * in {@code config.json}): Brightside's default transcribes on this computer, and
 * its model is fetched only once the owner agrees, through {@link #prepare}.
 * Every call is a read — the speech operations are read-only — so no recording
 * or transcript is left in a job record, and the recording travels inline,
 * stored nowhere.
 */
public final class Voice {

	static final String OP_ENGINES = "v/ops/speech/engines";
	static final String OP_PREPARE = "v/ops/speech/prepare";
	static final String OP_TRANSCRIBE = "v/ops/speech/transcribe";
	/** The speech adapter's name for its in-process engine. */
	static final String IN_PROCESS = "local";

	/**
	 * The speech engine as the venue reports it: its {@code name}, whether audio
	 * stays on this computer, and for the in-process engine its model's
	 * {@code state} ({@code ready}, {@code downloaded}, {@code downloading},
	 * {@code not downloaded}) with the model's size and how much of it is here;
	 * {@code reason} says why it is not ready, when it knows.
	 */
	public record Engine(String name, boolean local, String state, long bytes, long downloaded, boolean keyed,
			String reason) {

		/** The model is not on this computer and nothing is fetching it: fetching it is the owner's call. */
		public boolean needsDownload() {
			return IN_PROCESS.equals(name) && "not downloaded".equals(state);
		}

		/** The model is on its way, at {@link #downloaded} of {@link #bytes}. */
		public boolean downloading() {
			return IN_PROCESS.equals(name) && "downloading".equals(state);
		}

		/** A provider elsewhere that no key of the owner's (or the venue's) reaches. */
		public boolean needsKey() {
			return !local && !keyed;
		}
	}

	private final Venue venue;

	/** @param venue an in-process client bound to the owner */
	public Voice(Venue venue) {
		this.venue = venue;
	}

	/** The engine as it stands. */
	public CompletableFuture<Engine> engine() {
		return venue.run(OP_ENGINES, Maps.empty()).thenApply(Voice::engine);
	}

	/**
	 * Gets the engine ready: for the in-process engine, fetches its model and
	 * libraries if they are not here yet, which can take minutes while
	 * {@link #engine()} reports the progress. Completes when it is ready.
	 */
	public CompletableFuture<Engine> prepare() {
		return venue.run(OP_PREPARE, Maps.empty()).thenApply(Voice::engine);
	}

	/** What was said in {@code clip}, or an empty string for silence. */
	public CompletableFuture<String> transcribe(Microphone.Clip clip) {
		return venue.run(OP_TRANSCRIBE, Maps.of(
			"bytes", Base64.getEncoder().encodeToString(clip.pcm()),
			"mediaType", "audio/L16",
			"sampleRate", clip.sampleRate()))
			.thenApply(out -> {
				AString text = RT.ensureString(RT.getIn(out, "text"));
				return (text == null) ? "" : text.toString().strip();
			});
	}

	/** The first engine of a {@code speech:engines} result. */
	static Engine engine(ACell out) {
		AVector<?> engines = RT.ensureVector(RT.getIn(out, "engines"));
		if (engines == null || engines.count() == 0) throw new IllegalStateException("The venue reports no speech engine");
		ACell e = (ACell) engines.get(0);
		return new Engine(str(RT.getIn(e, "name")), flag(RT.getIn(e, "local")), str(RT.getIn(e, "state")),
			number(RT.getIn(e, "model", "bytes")), number(RT.getIn(e, "model", "downloaded")), flag(RT.getIn(e, "keyed")),
			str(RT.getIn(e, "reason")));
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s == null) ? null : s.toString();
	}

	private static boolean flag(ACell cell) {
		return CVMBool.TRUE.equals(cell);
	}

	private static long number(ACell cell) {
		return (cell instanceof CVMLong n) ? n.longValue() : 0;
	}
}
