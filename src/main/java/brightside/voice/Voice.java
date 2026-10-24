package brightside.voice;

import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.ANumeric;
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
 * ({@link #listen}, {@link #hear}; {@link LiveDictation} drives them). It also
 * speaks replies ({@link #say}), a read too, with the voice fetched only once
 * the owner agrees ({@link #prepareSpeaking}).
 */
public final class Voice {

	static final String OP_ENGINES = "v/ops/speech/engines";
	static final String OP_PREPARE = "v/ops/speech/prepare";
	static final String OP_TRANSCRIBE = "v/ops/speech/transcribe";
	static final String OP_LISTEN = "v/ops/speech/listen";
	static final String OP_HEAR = "v/ops/speech/hear";
	static final String OP_SAY = "v/ops/speech/say";
	/** The speech adapter's name for its in-process engine. */
	static final String IN_PROCESS = "local";

	/**
	 * The speech engine as the venue reports it: its {@code name}, whether audio
	 * stays on this computer, the {@code model} it transcribes with, and for the
	 * in-process engine that model's {@code state} ({@code ready},
	 * {@code downloaded}, {@code downloading}, {@code not downloaded}) with its size
	 * and how much of it is here; {@code reason} says why it is not ready, when it
	 * knows.
	 */
	public record Engine(String name, boolean local, String model, String state, long bytes, long downloaded,
			boolean keyed, String reason) {

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

	/**
	 * The engine that speaks, as the venue reports it: whether it is on this
	 * computer, its {@code model}, and for the in-process engine that model's
	 * {@code state} ({@code ready}, {@code downloaded}, {@code downloading},
	 * {@code not downloaded}) with its size and how much of it is here.
	 */
	public record Speaking(boolean local, String model, String state, long bytes, long downloaded, String reason) {

		/** The voice is not on this computer and nothing is fetching it: fetching it is the owner's call. */
		public boolean needsDownload() {
			return local && "not downloaded".equals(state);
		}
	}

	/**
	 * What a live dictation has heard: the words up to its last pause, which stay,
	 * then those still being spoken; and the seconds the speaker has been
	 * {@code quiet} since — 0 while speaking, negative before they have said anything.
	 */
	public record Heard(String settled, String partial, double quiet) {
		public String text() {
			return settled.isEmpty() ? partial : partial.isEmpty() ? settled : settled + " " + partial;
		}
	}

	/**
	 * What sent a spoken message: the owner, with Enter or Send; "Go", said as a
	 * sentence of its own at the end; or a long pause after speaking, with nobody
	 * choosing to send at all — the one that may carry half a thought, or words
	 * never meant for the agent.
	 */
	public enum Sent {
		OWNER, GO, PAUSE;

		String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * A message the owner spoke, for the agent: its {@code text} with {@code via}
	 * provenance, as a channel's message carries where it came from. The agent
	 * learns it was heard rather than typed, by which model and whether on this
	 * computer, whether the owner read it before it went ({@code reviewed}), so
	 * it can allow for a word misheard, and what {@code sent} it. Facts about
	 * the message, never instructions in it.
	 */
	public static AMap<AString, ACell> spoken(String text, Engine engine, boolean reviewed, Sent sent) {
		AMap<AString, ACell> via = Maps.of("channel", "voice", "sent", sent.key(), "reviewed", CVMBool.create(reviewed));
		if (engine != null) {
			AMap<AString, ACell> transcribed = Maps.of("onThisComputer", CVMBool.create(engine.local()));
			if (engine.model() != null) transcribed = transcribed.assoc(Strings.create("model"), Strings.create(engine.model()));
			via = via.assoc(Strings.create("transcribed"), transcribed);
		}
		return Maps.of("text", text, "via", via);
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

	/** The engine that speaks as it stands, or null when none does. */
	public CompletableFuture<Speaking> speaking() {
		return venue.run(OP_ENGINES, Maps.empty()).thenApply(Voice::speaking);
	}

	/** Gets the voice ready: for the in-process engine, fetches it if it is not here yet. */
	public CompletableFuture<Speaking> prepareSpeaking() {
		return venue.run(OP_PREPARE, Maps.of("for", "speak")).thenApply(Voice::speaking);
	}

	/**
	 * {@code text} spoken, as WAV. A read: the words and the audio travel inline,
	 * and nothing is stored or kept in a job record.
	 */
	public CompletableFuture<byte[]> say(String text) {
		return venue.run(OP_SAY, Maps.of("text", text, "format", "wav"))
			.thenApply(out -> Base64.getDecoder().decode(RT.ensureString(RT.getIn(out, "bytes")).toString()));
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
		return venue.run(OP_HEAR, input).thenApply(out -> new Heard(text(RT.getIn(out, "settled")),
			text(RT.getIn(out, "partial")), (RT.getIn(out, "quiet") instanceof ANumeric n) ? n.doubleValue() : -1));
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
		return new Engine(str(RT.getIn(e, "name")), flag(RT.getIn(e, "local")), str(RT.getIn(e, "transcribeModel")),
			str(RT.getIn(e, "state")),
			number(RT.getIn(e, "model", "bytes")), number(RT.getIn(e, "model", "downloaded")), flag(RT.getIn(e, "keyed")),
			str(RT.getIn(e, "reason")));
	}

	/** The engine of a {@code speech:engines} result that speaks, or null when none does. */
	static Speaking speaking(ACell out) {
		AVector<?> engines = RT.ensureVector(RT.getIn(out, "engines"));
		if (engines == null) return null;
		for (long i = 0; i < engines.count(); i++) {
			ACell e = (ACell) engines.get(i);
			if (!flag(RT.getIn(e, "speaks"))) continue;
			return new Speaking(flag(RT.getIn(e, "local")), str(RT.getIn(e, "speakModel")), str(RT.getIn(e, "voiceState")),
				number(RT.getIn(e, "voiceModel", "bytes")), number(RT.getIn(e, "voiceModel", "downloaded")),
				str(RT.getIn(e, "voiceReason")));
		}
		return null;
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
