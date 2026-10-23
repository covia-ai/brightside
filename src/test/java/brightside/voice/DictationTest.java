package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "Go" sends only as the transcript's last sentence, by itself, and is not
 * written into the draft; anywhere else it is just a word. A long pause after
 * words sends too.
 */
class DictationTest {

	@Test
	void goAsItsOwnLastSentenceSends() {
		assertEquals(new Dictation("Remind me to call Sam at four.", true), Dictation.of("Remind me to call Sam at four. Go."));
		assertEquals(new Dictation("Ready?", true), Dictation.of(" Ready? Go! "));
		assertEquals(new Dictation("He said “stop.”", true), Dictation.of("He said “stop.” go"), "after a closing quote");
		assertEquals(new Dictation("First line", true), Dictation.of("First line\nGO..."), "a line break is a break too");
		assertEquals(new Dictation("", true), Dictation.of("Go."), "alone: send what is already typed");
		assertEquals(new Dictation("Hi Bright Side, are you hearing me?", true),
			Dictation.of("Hi Bright Side, are you hearing me? Go"), "as Parakeet writes a lone go: no stop of its own");
	}

	@Test
	void aLongPauseAfterWordsSends() {
		assertTrue(Dictation.pausedToSend(new Voice.Heard("Remind me to call Sam.", "", Dictation.LONG_PAUSE_SECONDS)));
		assertTrue(Dictation.pausedToSend(new Voice.Heard("", "Remind me", Dictation.LONG_PAUSE_SECONDS + 1)),
			"words not yet settled count too");
		assertFalse(Dictation.pausedToSend(new Voice.Heard("Remind me to call Sam.", "", Dictation.LONG_PAUSE_SECONDS - 0.5)),
			"a breath or a thought is not the end");
		assertFalse(Dictation.pausedToSend(new Voice.Heard("", "", 30)), "silence alone sends nothing");
		assertFalse(Dictation.pausedToSend(new Voice.Heard("Remind me", "", 0)), "while still speaking");
	}

	@Test
	void goInsideASentenceIsOnlyAWord() {
		for (String words : new String[] { "Let's go.", "Okay, go.", "I want to go. Then home.", "Go go.",
				"Remind me to call Sam go", "Going.", "Ago." }) {
			assertEquals(new Dictation(words, false), Dictation.of(words), words);
		}
		assertEquals(new Dictation("", false), Dictation.of("  "), "silence");
	}
}
