package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

import brightside.ui.components.Buttons;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Documents;
import brightside.ui.components.Loading;
import brightside.ui.components.Lucide;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;
import brightside.voice.Dictation;
import brightside.voice.LiveDictation;
import brightside.voice.Microphone;
import brightside.voice.Relay;
import brightside.voice.Speaker;
import brightside.voice.Voice;
import convex.core.data.ACell;
import convex.core.data.Strings;

/**
 * Voice typing in the composer: a microphone beside Send, and one quiet line
 * above the composer while it is at work. Press to speak, press again to stop;
 * what was said goes into the draft at the caret for the owner to read, correct
 * and send. Ending with "go" as a sentence of its own sends the draft as Enter
 * would ({@link Dictation}), as do a long pause after speaking and Enter itself
 * while dictating; nothing else does. A draft holding spoken words goes to the
 * agent as a {@linkplain Voice#spoken spoken message}, so it knows it was heard.
 * Escape discards the recording or the transcription under way.
 *
 * <p>While the microphone is on, the draft is its: the place the words go — the
 * selection when it began — is held, and the engine that hears as the owner
 * speaks ({@link LiveDictation}) writes there as they are said; when it stops,
 * the settled words take their place. An engine that hears only whole
 * recordings writes them there once, at the end.
 *
 * <p>With the engine that hears as the owner speaks, sending does not close the
 * microphone: only the owner does, with the microphone or Escape. When "go", a
 * long pause or Enter sends a message, its dictation (a {@link Take}) settles and
 * goes, and the next message's begins on the same recording, the audio meanwhile
 * held by a {@link Relay} so the two never run together. A minute without a word
 * closes the microphone by itself, saying so, so it never runs on unnoticed.
 *
 * <p>The first press while the speech model is not on this computer asks before
 * downloading it, then shows the download until voice typing is ready. The
 * microphone and the venue are worked off the event thread ({@link Microphone},
 * {@link Voice}); everything in this class runs on it.
 */
final class VoiceInput {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceInput.class);

	private enum Phase { IDLE, CHECKING, PREPARING, RECORDING, TRANSCRIBING }

	/** How long a note stays on the line by itself. */
	private static final int NOTE_MS = 6000;
	private static final int TICK_MS = 250;
	private static final int PROGRESS_MS = 1000;
	private static final Executor OFF_EDT = Executors.newThreadPerTaskExecutor(
		Thread.ofVirtual().name("brightside-voice-", 0).factory());

	private final JTextComponent input;
	private final Runnable send;
	/** Replies read aloud: the microphone hears silence while one plays. */
	private final Speaker speaker;
	private final JButton button = Buttons.icon(Lucide.icon("mic", 18, Theme::muted), null);
	private final JPanel line = new JPanel(new BorderLayout());
	private final Timer ticker = new Timer(TICK_MS, e -> tick());
	private final Timer progress = new Timer(PROGRESS_MS, e -> pollProgress());
	private final Timer noteExpiry = new Timer(NOTE_MS, e -> showLine(null));

	private Voice voice;
	/** The engine as last asked, for what a spoken message says of how it was heard. */
	private Voice.Engine engine;
	/** The draft holds words that were spoken, until it is next emptied. */
	private boolean dictated;
	private boolean enabled;
	private Phase phase = Phase.IDLE;
	/** Bumped whenever an attempt is abandoned, so whatever it finishes later is dropped. */
	private long attempt;
	private Microphone.Recording recording;
	/** The audio of a recording heard as it is spoken, to one message's dictation after another. */
	private Relay relay;
	/** The message being dictated now, or null between messages. */
	private Take take;
	/** A message is settling and going while the microphone stays open for the next. */
	private boolean handingOver;
	/** The owner stopped during a hand-over: once that message is sent, listen no more. */
	private boolean stopAfterHandOver;
	/** Enter came while the words were still being written: send once they are. */
	private boolean sendWhenDone;
	private StatusLabel listening;
	private Loading preparing;
	/** The held place in the draft — where it starts, how long it is, what it held before — or -1 when none is. */
	private int anchor = -1;
	private int shown;
	private String original = "";

	/**
	 * @param send    sends the draft as it stands, even while the microphone is open
	 * @param speaker what reads replies aloud, which the microphone must not hear
	 */
	VoiceInput(JTextComponent input, Runnable send, Speaker speaker) {
		this.input = input;
		this.send = send;
		this.speaker = speaker;
		// An emptied draft — sent, cleared — holds nothing spoken any more.
		Documents.onChange(input, () -> {
			if (input.getDocument().getLength() == 0) dictated = false;
		});
		// Never the focus owner: the draft keeps the caret the words go to.
		button.setFocusable(false);
		button.addActionListener(e -> press());
		line.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 0));
		line.setVisible(false);
		noteExpiry.setRepeats(false);
		update();
	}

	/** The microphone, for the composer's row of actions. */
	JButton button() {
		return button;
	}

	/** The line that says what voice typing is doing, for above the composer; hidden while it does nothing. */
	JComponent statusLine() {
		return line;
	}

	/** Voice typing as the owner of {@code voice}, or none at all with null; anything under way is dropped. */
	void bind(Voice voice) {
		abandon();
		showLine(null);
		this.voice = voice;
		update();
	}

	void setEnabled(boolean on) {
		enabled = on;
		if (!on && phase != Phase.IDLE) {
			abandon();
			showLine(null);
		}
		update();
	}

	/** Whether a recording or a transcription is under way, which Escape would discard. */
	boolean busy() {
		return phase == Phase.RECORDING || phase == Phase.TRANSCRIBING;
	}

	/** Escape: drop the recording or the transcription under way, keeping nothing. */
	void discard() {
		if (!busy()) return;
		abandon();
		showLine(null);
	}

	/**
	 * Enter while dictating: send once the words are in. With the engine that hears
	 * as the owner speaks, the microphone stays open for the next message, as after
	 * "go"; otherwise it stops.
	 */
	void sendWhenDone() {
		if (phase == Phase.RECORDING && relay != null) handOver();
		else if (phase == Phase.RECORDING) finish(true);
		else if (phase == Phase.TRANSCRIBING) sendWhenDone = true;
	}

	/**
	 * What to send for {@code text}: a {@linkplain Voice#spoken spoken} message when
	 * the draft holds dictated words, else just the text. {@code reviewed}: the owner
	 * sent it themselves once the words were in, rather than by voice or mid-dictation.
	 */
	ACell message(String text, boolean reviewed) {
		return dictated ? Voice.spoken(text, engine, reviewed) : Strings.create(text);
	}

	private void press() {
		switch (phase) {
			case IDLE -> {
				// The owner means to speak: a reply being read aloud gives way.
				speaker.stop();
				check();
			}
			case RECORDING -> finish(false);
			default -> {
				// Getting ready or writing it down: the button is not pressable then.
			}
		}
	}

	/** Asks the engine how it stands, then listens, or first gets it ready. */
	private void check() {
		Voice v = voice;
		if (v == null) return;
		long mine = ++attempt;
		phase = Phase.CHECKING;
		showLine(null);
		update();
		v.engine().whenComplete((engine, failure) -> onEdt(mine, () -> {
			if (failure != null) {
				fail("Voice typing isn't available", failure);
			} else if (engine.needsKey()) {
				idle();
				note("Voice typing needs an OpenAI key — add one in Settings → Model", "key-round", Tone.WARNING);
			} else if (engine.downloading()) {
				prepare(v);
			} else if (engine.needsDownload()) {
				idle();
				if (confirmDownload(engine) && phase == Phase.IDLE && voice == v) prepare(v);
			} else {
				this.engine = engine;
				listen(mine, engine.live());
			}
		}));
	}

	private boolean confirmDownload(Voice.Engine engine) {
		long mb = Math.max(10, Math.round(engine.bytes() / 1e7) * 10);
		return Dialogs.choose(SwingUtilities.getWindowAncestor(button), "Set up voice typing?",
			"Voice typing works on this computer, so what you say never leaves it.\n"
			+ "It needs a one-off download of about " + mb + " MB for the speech model.",
			"Download", "Not now");
	}

	/** Fetches the engine's model, showing how far it has got, until voice typing is ready. */
	private void prepare(Voice v) {
		long mine = ++attempt;
		phase = Phase.PREPARING;
		preparing = new Loading("Getting voice typing ready…");
		showLine(preparing);
		update();
		progress.start();
		v.prepare().whenComplete((engine, failure) -> onEdt(mine, () -> {
			progress.stop();
			if (failure != null) {
				fail("Voice typing couldn't be set up", failure);
				return;
			}
			idle();
			note("Voice typing is ready — press the microphone and speak", "circle-check", Tone.SUCCESS);
		}));
	}

	private void pollProgress() {
		Voice v = voice;
		long mine = attempt;
		if (v == null || phase != Phase.PREPARING) return;
		v.engine().thenAccept(engine -> onEdt(mine, () -> {
			if (phase != Phase.PREPARING || preparing == null || engine.bytes() <= 0 || engine.downloaded() <= 0) return;
			long percent = Math.min(99, engine.downloaded() * 100 / engine.bytes());
			preparing.setText("Getting voice typing ready… " + percent + "%");
		}));
	}

	/** Opens the microphone and starts listening; with {@code heardLive}, the words show as they are said. */
	private void listen(long mine, boolean heardLive) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return Microphone.record(() -> onEdt(mine, () -> finish(false)), speaker::speaking);
			} catch (LineUnavailableException e) {
				throw new CompletionException(e);
			}
		}, OFF_EDT).whenComplete((opened, failure) -> SwingUtilities.invokeLater(() -> {
			if (attempt != mine) {
				if (opened != null) OFF_EDT.execute(opened::discard);
				return;
			}
			if (failure != null) {
				Throwable cause = ChatPanel.unwrap(failure);
				fail(Microphone.NO_MICROPHONE.equals(cause.getMessage()) ? Microphone.NO_MICROPHONE
					: "The microphone couldn't be opened", cause);
				return;
			}
			recording = opened;
			phase = Phase.RECORDING;
			hold();
			if (heardLive) hearLive(mine, opened);
			listening = new StatusLabel(listeningText(0), "mic", Tone.ERROR);
			Styles.classes(listening, Styles.SMALL);
			showLine(listening);
			ticker.start();
			update();
		}));
	}

	/** Opens the engine's live dictation and gives it the recording as it comes; its words go to the held place. */
	private void hearLive(long mine, Microphone.Recording r) {
		relay = new Relay();
		take = new Take(mine, r);
		relay.to(take.dictation::hear);
		r.onAudio(relay);
	}

	/** One message's dictation, on a recording that may outlast it. */
	private final class Take {
		final LiveDictation dictation;
		final long started = System.nanoTime();

		Take(long mine, Microphone.Recording r) {
			Voice v = voice;
			CompletableFuture<String> id = v.listen(r.sampleRate());
			dictation = new LiveDictation((pcm, end) -> id.thenCompose(i -> v.hear(i, pcm, end)), r.sampleRate(),
				heard -> onEdt(mine, () -> heard(this, heard)));
		}

		double seconds() {
			return (System.nanoTime() - started) / 1e9;
		}
	}

	/** What a message's dictation has heard: shown, and acted on while the microphone is open. */
	private void heard(Take t, Voice.Heard heard) {
		if (t != take || !busy()) return;
		show(heard.text());
		if (phase != Phase.RECORDING) return;
		if (Dictation.saidGo(heard) || Dictation.pausedToSend(heard)) {
			handOver();
		} else if (Dictation.idle(heard, t.seconds())) {
			abandon();
			note("Stopped listening after a minute without a word", "circle-pause", Tone.MUTED);
		} else if (t.dictation.seconds() >= Microphone.MAX_SECONDS) {
			finish(false);
		}
	}

	/**
	 * Sends the message being dictated once its words settle, and listens on for the
	 * next: the microphone stays open, its audio held meanwhile, so nothing said in
	 * between is lost or added to the message that went.
	 */
	private void handOver() {
		Take t = take;
		Microphone.Recording r = recording;
		Voice v = voice;
		long mine = attempt;
		if (t == null || handingOver) return;
		handingOver = true;
		relay.hold();
		take = null;
		tick();
		t.dictation.finish().exceptionallyCompose(f -> {
			log.info("Live dictation failed, so the message is transcribed whole: {}", ChatPanel.describe(ChatPanel.unwrap(f)));
			return v.transcribe(t.dictation.clip());
		}).whenComplete((text, failure) -> onEdt(mine, () -> {
			handingOver = false;
			if (failure != null) {
				abandon();
				fail("That couldn't be transcribed", failure);
				return;
			}
			release(Dictation.of(text).text());
			send.run();
			if (stopAfterHandOver || phase != Phase.RECORDING) {
				stopAfterHandOver = false;
				idle();
				showLine(null);
				return;
			}
			take = new Take(mine, r);
			hold();
			relay.to(take.dictation::hear);
			tick();
		}));
	}

	private void tick() {
		if (listening == null) return;
		if (handingOver) {
			listening.setText("Sending… still listening");
			return;
		}
		Take t = take;
		Microphone.Recording r = recording;
		if (t != null) listening.setText(listeningText(t.seconds()));
		else if (r != null) listening.setText(listeningText(r.seconds()));
	}

	private static String listeningText(double seconds) {
		int s = (int) seconds;
		return String.format("Listening… %d:%02d", s / 60, s % 60);
	}

	/**
	 * Stops listening and writes down what was said: the live dictation's settled
	 * words, or, without one or should it fail, the whole recording transcribed.
	 * With {@code thenSend}, the draft is sent once they are in, as Enter would.
	 */
	private void finish(boolean thenSend) {
		if (phase != Phase.RECORDING) return;
		Microphone.Recording r = recording;
		Take t = take;
		Voice v = voice;
		long mine = attempt;
		recording = null;
		take = null;
		if (relay != null) relay.drop();
		relay = null;
		ticker.stop();
		phase = Phase.TRANSCRIBING;
		update();
		if (handingOver) {
			// Stopped while a message was going: it still goes, and then nothing more is heard.
			stopAfterHandOver = true;
			OFF_EDT.execute(r::discard);
			showLine(new Loading("Sending…"));
			return;
		}
		sendWhenDone = thenSend;
		showLine(new Loading("Transcribing…"));
		CompletableFuture.supplyAsync(() -> {
			try {
				return r.stop();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, OFF_EDT).thenCompose(clip -> (t == null) ? v.transcribe(clip) : t.dictation.finish().exceptionallyCompose(f -> {
			log.info("Live dictation failed, so the whole recording is transcribed: {}", ChatPanel.describe(ChatPanel.unwrap(f)));
			return v.transcribe(t.dictation.clip());
		})).whenComplete((text, failure) -> onEdt(mine, () -> {
			boolean go = sendWhenDone;
			sendWhenDone = false;
			if (failure != null) {
				release("");
				fail("That couldn't be transcribed", failure);
				return;
			}
			idle();
			Dictation said = Dictation.of(text);
			release(said.text());
			if (said.text().isEmpty() && !said.send() && !go) {
				note("Nothing was heard — check the microphone and try again", "circle-alert", Tone.WARNING);
				return;
			}
			showLine(null);
			if (said.send() || go) send.run();
		}));
	}

	/** Holds the place the words go — the selection — and keeps the owner's typing out of it meanwhile. */
	private void hold() {
		anchor = input.getSelectionStart();
		String selected = input.getSelectedText();
		original = (selected == null) ? "" : selected;
		shown = original.length();
		input.setEditable(false);
	}

	/** {@code words} in the held place, spaced from the words either side; none puts back what it held. */
	private void show(String words) {
		if (anchor < 0) return;
		Document doc = input.getDocument();
		int length = doc.getLength();
		anchor = Math.min(anchor, length);
		shown = Math.min(shown, length - anchor);
		String text = original;
		if (!words.isEmpty()) {
			String draft = input.getText();
			int end = anchor + shown;
			text = words;
			if (anchor > 0 && !Character.isWhitespace(draft.charAt(anchor - 1))) text = " " + text;
			if (end < draft.length() && !Character.isWhitespace(draft.charAt(end))) text = text + " ";
		}
		try {
			// Only what changed is rewritten: answers come twice a second, mostly the
			// same, and a selection in the words already there must survive them.
			String now = doc.getText(anchor, shown);
			int same = 0;
			while (same < now.length() && same < text.length() && now.charAt(same) == text.charAt(same)) same++;
			if (same == now.length() && same == text.length()) return;
			doc.remove(anchor + same, shown - same);
			doc.insertString(anchor + same, text.substring(same), null);
			shown = text.length();
		} catch (BadLocationException e) {
			log.warn("Could not write the dictation into the draft", e);
		}
	}

	/** Lets go of the held place with {@code words} in it, and gives the draft back to the owner's typing. */
	private void release(String words) {
		if (anchor < 0) return;
		show(words);
		if (!words.isEmpty()) dictated = true;
		input.setEditable(true);
		input.setCaretPosition(Math.min(anchor + shown, input.getDocument().getLength()));
		input.requestFocusInWindow();
		anchor = -1;
	}

	/** Drops whatever is under way: a recording is discarded, a late result ignored, the draft as it was. */
	private void abandon() {
		attempt++;
		ticker.stop();
		progress.stop();
		Microphone.Recording r = recording;
		recording = null;
		if (r != null) OFF_EDT.execute(r::discard);
		Take t = take;
		take = null;
		if (t != null) OFF_EDT.execute(t.dictation::discard);
		if (relay != null) relay.drop();
		relay = null;
		handingOver = false;
		stopAfterHandOver = false;
		sendWhenDone = false;
		release("");
		idle();
	}

	private void idle() {
		phase = Phase.IDLE;
		listening = null;
		preparing = null;
		update();
	}

	private void fail(String text, Throwable failure) {
		Throwable cause = ChatPanel.unwrap(failure);
		log.warn("{}: {}", text, ChatPanel.describe(cause));
		idle();
		note(text, "circle-alert", Tone.ERROR);
		// The detail, for whoever wants it, without filling the line.
		line.setToolTipText(ChatPanel.describe(cause));
	}

	/** A line that clears itself after a few seconds. */
	private void note(String text, String icon, Tone tone) {
		StatusLabel label = new StatusLabel(text, icon, tone);
		Styles.classes(label, Styles.SMALL);
		showLine(label);
		noteExpiry.restart();
	}

	private void showLine(Component content) {
		noteExpiry.stop();
		line.setToolTipText(null);
		line.removeAll();
		if (content != null) line.add(content, BorderLayout.WEST);
		line.setVisible(content != null);
		line.revalidate();
		line.repaint();
	}

	private void onEdt(long mine, Runnable step) {
		SwingUtilities.invokeLater(() -> {
			if (attempt == mine) step.run();
		});
	}

	private void update() {
		boolean recordingNow = phase == Phase.RECORDING;
		button.setEnabled(enabled && voice != null && (phase == Phase.IDLE || recordingNow));
		button.setIcon(recordingNow ? Lucide.icon("square", 18, Theme::error) : Lucide.icon("mic", 18, Theme::muted));
		button.setToolTipText(recordingNow ? "Stop, and write down what you said (Esc discards it)"
			: "Speak your message instead of typing it; end with “Go” to send it");
	}
}
