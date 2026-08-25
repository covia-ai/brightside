package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

	@TempDir
	Path home;

	@Test
	void startsEmpty() {
		ConversationStore s = ConversationStore.load(home, "mike");
		assertTrue(s.isEmpty());
		assertNull(s.sessionId());
	}

	@Test
	void persistsAndReopens() {
		ConversationStore s = ConversationStore.load(home, "mike");
		s.record("user", "hello", "sid-1");
		s.record("assistant", "hi Mike", "sid-1");

		// A fresh load (as on restart) sees the saved transcript and session.
		ConversationStore reopened = ConversationStore.load(home, "mike");
		assertEquals("sid-1", reopened.sessionId());
		List<ConversationStore.Msg> msgs = reopened.messages();
		assertEquals(2, msgs.size());
		assertEquals("user", msgs.get(0).role());
		assertEquals("hello", msgs.get(0).text());
		assertEquals("assistant", msgs.get(1).role());
		assertEquals("hi Mike", msgs.get(1).text());
	}

	@Test
	void isPerUser() {
		ConversationStore.load(home, "mike").record("user", "mine", "m");
		ConversationStore sarah = ConversationStore.load(home, "sarah");
		assertTrue(sarah.isEmpty(), "each user has their own conversation");
	}

	@Test
	void clearStartsFresh() {
		ConversationStore s = ConversationStore.load(home, "mike");
		s.record("user", "x", "sid");
		s.clear();
		ConversationStore reopened = ConversationStore.load(home, "mike");
		assertTrue(reopened.isEmpty());
		assertNull(reopened.sessionId());
	}
}
