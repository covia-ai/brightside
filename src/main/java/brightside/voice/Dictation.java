package brightside.voice;

/**
 * A transcript read as the owner meant it: the words to write into the draft,
 * and whether it ended with the spoken command to send. "Go" sends only as a
 * sentence of its own at the very end — said after a pause, which the engine
 * writes as a sentence break — ignoring case and punctuation, and is not
 * itself written. "Let's go." and "Okay, go." are only words; so is a
 * transcript with no punctuation at all, since a pause cannot be told from it.
 * A long pause once something has been said sends too, as the owner finishing
 * ({@link #pausedToSend}).
 */
public record Dictation(String text, boolean send) {

	/** The spoken command, as a sentence by itself. */
	static final String GO = "go";
	/** How long a pause after speech ends a dictation and sends it: longer than a breath or a thought. */
	public static final double LONG_PAUSE_SECONDS = 3;

	/** Whether the owner has said something and then been quiet long enough to mean they have finished. */
	public static boolean pausedToSend(Voice.Heard heard) {
		return !heard.text().isBlank() && heard.quiet() >= LONG_PAUSE_SECONDS;
	}
	/** What may close a sentence, and what may stand after that inside it (a closing quote or bracket). */
	private static final String TERMINATORS = ".!?…";
	private static final String CLOSERS = "\"'”’)]»";

	/** The draft's words and the command, from what the engine heard. */
	public static Dictation of(String transcript) {
		String t = transcript.strip();
		int start = lastSentence(t);
		if (bare(t.substring(start)).equalsIgnoreCase(GO)) return new Dictation(t.substring(0, start).strip(), true);
		return new Dictation(t, false);
	}

	/** Where the last sentence begins: after the last sentence break, or at the start. */
	private static int lastSentence(String t) {
		for (int i = t.length() - 1; i > 0; i--) {
			char c = t.charAt(i);
			if (c == '\n' || c == '\r') return i + 1;
			if (!Character.isWhitespace(c)) continue;
			int j = i - 1;
			while (j >= 0 && CLOSERS.indexOf(t.charAt(j)) >= 0) j--;
			if (j >= 0 && TERMINATORS.indexOf(t.charAt(j)) >= 0) return i + 1;
		}
		return 0;
	}

	/** Only the letters and digits: "Go!" and "“go.”" are both "go". */
	private static String bare(String sentence) {
		StringBuilder b = new StringBuilder();
		sentence.codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);
		return b.toString();
	}
}
