package brightside.voice;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
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
 * stored nowhere. The in-process engine also hears as the owner speaks
 * ({@link #listen}, {@link #hear}; {@link LiveDictation} drives them).
 */
public final class Voice {

	static final String OP_ENGINES = "v/ops/speech/engines";
	static final String OP_PREPARE = "v/ops/speech/prepare";
	static final String OP_TRANSCRIBE = "v/ops/speech/transcribe";
	static final String OP_LISTEN = "v/ops/speech/listen";
	static final String OP_HEAR = "v/ops/speech/hear";
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

		/** Whether it hears as the owner speaks ({@link #listen}): the in-process engine does. */
		public boolean live() {
			return IN_PROCESS.equals(name);
		}
	}

	/** What a live dictation has heard: the words up to its last pause, which stay, then those still being spoken. */
	public record Heard(String settled, String partial) {
		public String text() {
			return settled.isEmpty() ? partial : partial.isEmpty() ? settled : settled + " " + partial;
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

	/** Opens a live dictation of raw PCM at {@code sampleRate}; completes with its id, for {@link #hear}. */
	public CompletableFuture<String> listen(int sampleRate) {
		return venue.run(OP_LISTEN, Maps.of("sampleRate", sampleRate))
			.thenApply(out -> RT.ensureString(RT.getIn(out, "id")).toString());
	}

	/** More of a live dictation's audio, and what it has heard so far; {@code end} settles the rest and closes it. */
	public CompletableFuture<Heard> hear(String id, byte[] pcm, boolean end) {
		AMap<AString, ACell> input = Maps.of("id", id, "end", CVMBool.create(end));
		if (pcm.length > 0) input = input.assoc(Strings.create("bytes"), Strings.create(Base64.getEncoder().encodeToString(pcm)));
		return venue.run(OP_HEAR, input).thenApply(out -> new Heard(text(RT.getIn(out, "settled")), text(RT.getIn(out, "partial"))));
	}

	private static String text(ACell cell) {
		String s = str(cell);
		return (s == null) ? "" : s.strip();
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
