package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

/**
 * A live dictation sends the recording as it gathers, half a second or more at a
 * time and never two requests at once; each answer reaches the listener in
 * order; the end carries what is left and yields the settled words. A failed
 * request fails the dictation, and a discarded one tells nobody what it heard,
 * but either still sends the end so the engine lets it go. The test answers
 * each request itself, so nothing waits on a clock.
 */
class LiveDictationTest {

	private static final int RATE = 16_000;
	private static final int HALF_SECOND = RATE;

	/** Each request, answered when the test says. */
	record Request(int bytes, boolean end, CompletableFuture<Voice.Heard> reply) {
	}

	private final List<Request> requests = new CopyOnWriteArrayList<>();
	private final List<Voice.Heard> told = new CopyOnWriteArrayList<>();
	private final LiveDictation dictation = new LiveDictation((pcm, end) -> {
		CompletableFuture<Voice.Heard> reply = new CompletableFuture<>();
		requests.add(new Request(pcm.length, end, reply));
		return reply;
	}, RATE, told::add);

	@Test
	void theRecordingGoesAsItGathersOneRequestAtATime() throws Exception {
		dictation.hear(new byte[HALF_SECOND / 2]);
		assertEquals(0, requests.size(), "a quarter second is not yet worth sending");
		dictation.hear(new byte[HALF_SECOND / 2]);
		assertEquals(1, requests.size());
		assertEquals(HALF_SECOND, requests.get(0).bytes());

		dictation.hear(new byte[HALF_SECOND]);
		dictation.hear(new byte[HALF_SECOND]);
		assertEquals(1, requests.size(), "nothing more goes while one is on its way");
		Voice.Heard first = new Voice.Heard("", "Remind me", 0);
		requests.get(0).reply().complete(first);
		assertEquals(List.of(first), told);
		assertEquals(2 * HALF_SECOND, requests.get(1).bytes(), "what gathered meanwhile goes at once");

		dictation.hear(new byte[100]);
		CompletableFuture<String> words = dictation.finish();
		Voice.Heard second = new Voice.Heard("Remind me to call Sam.", "at four", 0);
		requests.get(1).reply().complete(second);
		Request end = requests.get(2);
		assertTrue(end.end());
		assertEquals(100, end.bytes(), "the end carries what is left");
		Voice.Heard last = new Voice.Heard("Remind me to call Sam. At four.", "", 0);
		end.reply().complete(last);

		assertEquals("Remind me to call Sam. At four.", words.get());
		assertEquals(List.of(first, second, last), told, "every answer, in order");
		assertEquals(3, requests.size());
	}

	@Test
	void aFailedRequestFailsTheDictationButStillEndsIt() {
		dictation.hear(new byte[HALF_SECOND]);
		RuntimeException lost = new RuntimeException("the engine went away");
		requests.get(0).reply().completeExceptionally(lost);
		dictation.hear(new byte[HALF_SECOND]);
		assertEquals(1, requests.size(), "nothing more is sent after a failure");

		CompletableFuture<String> words = dictation.finish();
		Request end = requests.get(1);
		assertTrue(end.end());
		assertEquals(0, end.bytes());
		end.reply().complete(new Voice.Heard("Some", "", 0));
		assertSame(lost, assertThrows(ExecutionException.class, words::get).getCause(), "the words are not known");
		assertTrue(told.isEmpty());
	}

	@Test
	void aDiscardedDictationTellsNobodyAndEnds() {
		dictation.hear(new byte[HALF_SECOND]);
		dictation.discard();
		dictation.hear(new byte[HALF_SECOND]);
		requests.get(0).reply().complete(new Voice.Heard("", "Never mind", 0));
		assertTrue(told.isEmpty(), "what it heard is no one's");
		Request end = requests.get(1);
		assertTrue(end.end());
		assertEquals(0, end.bytes(), "and nothing more of the recording went");
		assertEquals(2, requests.size());
	}
}
