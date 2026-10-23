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
import javax.swing.text.JTextComponent;

import brightside.ui.components.Buttons;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Loading;
import brightside.ui.components.Lucide;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;
import brightside.voice.Dictation;
import brightside.voice.Microphone;
import brightside.voice.Voice;

/**
 * Voice typing in the composer: a microphone beside Send, and one quiet line
 * above the composer while it is at work. Press to speak, press again to stop;
 * what was said goes into the draft at the caret for the owner to read, correct
 * and send. Ending with "go" as a sentence of its own sends the draft as Enter
 * would ({@link Dictation}); nothing else does. Escape discards the recording
 * or the transcription under way.
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
	private final JButton button = Buttons.icon(Lucide.icon("mic", 18, Theme::muted), null);
	private final JPanel line = new JPanel(new BorderLayout());
	private final Timer ticker = new Timer(TICK_MS, e -> tick());
	private final Timer progress = new Timer(PROGRESS_MS, e -> pollProgress());
	private final Timer noteExpiry = new Timer(NOTE_MS, e -> showLine(null));

	private Voice voice;
	private boolean enabled;
	private Phase phase = Phase.IDLE;
	/** Bumped whenever an attempt is abandoned, so whatever it finishes later is dropped. */
	private long attempt;
	private Microphone.Recording recording;
	private StatusLabel listening;
	private Loading preparing;

	/** @param send sends the draft, as Enter does */
	VoiceInput(JTextComponent input, Runnable send) {
		this.input = input;
		this.send = send;
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

	private void press() {
		switch (phase) {
			case IDLE -> check();
			case RECORDING -> finish();
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
				listen(mine);
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

	/** Opens the microphone and starts listening. */
	private void listen(long mine) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return Microphone.record(() -> onEdt(mine, this::finish));
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
			listening = new StatusLabel(listeningText(0), "mic", Tone.ERROR);
			Styles.classes(listening, Styles.SMALL);
			showLine(listening);
			ticker.start();
			update();
		}));
	}

	private void tick() {
		Microphone.Recording r = recording;
		if (r != null && listening != null) listening.setText(listeningText(r.seconds()));
	}

	private static String listeningText(double seconds) {
		int s = (int) seconds;
		return String.format("Listening… %d:%02d", s / 60, s % 60);
	}

	/** Stops listening and writes down what was said. */
	private void finish() {
		if (phase != Phase.RECORDING) return;
		Microphone.Recording r = recording;
		Voice v = voice;
		long mine = attempt;
		recording = null;
		ticker.stop();
		phase = Phase.TRANSCRIBING;
		showLine(new Loading("Transcribing…"));
		update();
		CompletableFuture.supplyAsync(() -> {
			try {
				return r.stop();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, OFF_EDT).thenCompose(v::transcribe).whenComplete((text, failure) -> onEdt(mine, () -> {
			if (failure != null) {
				fail("That couldn't be transcribed", failure);
				return;
			}
			idle();
			Dictation said = Dictation.of(text);
			if (said.text().isEmpty() && !said.send()) {
				note("Nothing was heard — check the microphone and try again", "circle-alert", Tone.WARNING);
				return;
			}
			showLine(null);
			if (!said.text().isEmpty()) insert(said.text());
			if (said.send()) send.run();
		}));
	}

	/** Puts {@code text} in the draft in place of its selection, spaced from the words either side. */
	private void insert(String text) {
		String draft = input.getText();
		int start = input.getSelectionStart();
		int end = input.getSelectionEnd();
		if (start > 0 && !Character.isWhitespace(draft.charAt(start - 1))) text = " " + text;
		if (end < draft.length() && !Character.isWhitespace(draft.charAt(end))) text = text + " ";
		input.replaceSelection(text);
		input.requestFocusInWindow();
	}

	/** Drops whatever is under way: a recording is discarded, a late result ignored. */
	private void abandon() {
		attempt++;
		ticker.stop();
		progress.stop();
		Microphone.Recording r = recording;
		recording = null;
		if (r != null) OFF_EDT.execute(r::discard);
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
