package brightside.ui.chat;

import java.awt.Component;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import brightside.ui.components.Dialogs;
import brightside.voice.Speaker;
import brightside.voice.SpokenText;
import brightside.voice.Voice;

/**
 * Replies read aloud. Off unless the owner turns it on (Settings → General →
 * <em>Read replies aloud</em>, default off), and then each reply the chat waits
 * for is read as it lands; with it off, a reply is read only when the owner asks
 * (<em>Read aloud</em> on a reply). The voice speaks on this computer through
 * the venue's speech adapter ({@link Voice#say}, a read that keeps nothing), and
 * its model is fetched only once the owner agrees. Runs on the event thread;
 * the venue and the audio are worked off it.
 */
final class ReadAloud {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReadAloud.class);

	static final String UNAVAILABLE = "Reading aloud isn't available: no speech engine here speaks.";

	private final Speaker speaker;
	private final Component parent;
	private BooleanSupplier repliesOn = () -> false;
	private Voice voice;
	/** The owner said "Not now" to the voice's download: replies are not read until they ask again. */
	private boolean declined;

	ReadAloud(Speaker speaker, Component parent) {
		this.speaker = speaker;
		this.parent = parent;
	}

	/** Reads replies as the owner, or not at all with null; anything playing stops. */
	void bind(Voice voice) {
		speaker.stop();
		this.voice = voice;
	}

	/** Where the owner's preference lives; read each time a reply lands. */
	void repliesOnWhen(BooleanSupplier on) {
		repliesOn = on;
	}

	/** Whether the reply the chat waits for will be read aloud: the owner has replies read, and has not put off the voice. */
	boolean readsReplies() {
		return repliesOn.getAsBoolean() && !declined;
	}

	/** A reply the chat waited for has landed: read it if the owner has replies read aloud. */
	void reply(String markdown) {
		if (readsReplies()) read(markdown, false);
	}

	/** The owner asked to hear this one. */
	void readAloud(String markdown) {
		read(markdown, true);
	}

	void stop() {
		speaker.stop();
	}

	boolean speaking() {
		return speaker.speaking();
	}

	/**
	 * Gets the voice ready for replies to be read, asking before any download.
	 * Completes true when it is (or there is no venue yet to ask, so the first
	 * reply asks instead), false when the owner said not now or nothing speaks.
	 */
	CompletableFuture<Boolean> getReady() {
		Voice v = voice;
		if (v == null) return CompletableFuture.completedFuture(true);
		CompletableFuture<Boolean> ready = new CompletableFuture<>();
		v.speaking().whenComplete((speaking, failure) -> SwingUtilities.invokeLater(() -> {
			if (failure != null || speaking == null) {
				if (failure != null) log.warn("Could not ask the venue about its voice", failure);
				Dialogs.info(parent, "Read replies aloud", UNAVAILABLE);
				ready.complete(false);
				return;
			}
			if (!speaking.needsDownload()) {
				declined = false;
				ready.complete(true);
				return;
			}
			if (!confirmDownload(speaking)) {
				ready.complete(false);
				return;
			}
			declined = false;
			v.prepareSpeaking().whenComplete((ok, f) -> {
				if (f != null) log.warn("The voice couldn't be set up", f);
			});
			ready.complete(true);
		}));
		return ready;
	}

	private void read(String markdown, boolean asked) {
		Voice v = voice;
		List<String> parts = SpokenText.parts(markdown);
		if (v == null || parts.isEmpty()) return;
		v.speaking().whenComplete((speaking, failure) -> SwingUtilities.invokeLater(() -> {
			if (voice != v) return;
			if (failure != null || speaking == null) {
				if (failure != null) log.warn("Could not ask the venue about its voice", failure);
				if (asked) Dialogs.info(parent, "Read aloud", UNAVAILABLE);
				return;
			}
			if (speaking.needsDownload()) {
				if (!confirmDownload(speaking)) {
					declined = true;
					return;
				}
				declined = false;
			}
			speaker.say(parts, v::say).whenComplete((done, f) -> {
				if (f != null) log.warn("A reply couldn't be read aloud", f);
			});
		}));
	}

	private boolean confirmDownload(Voice.Speaking speaking) {
		long mb = Math.max(10, Math.round(speaking.bytes() / 1e7) * 10);
		return Dialogs.choose(SwingUtilities.getWindowAncestor(parent), "Set up reading aloud?",
			"Brightside reads replies aloud on this computer, so nothing it says leaves it.\n"
			+ "It needs a one-off download of about " + mb + " MB for the voice.",
			"Download", "Not now");
	}
}
