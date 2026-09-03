package brightside.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.SessionHistory;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.venue.AgentEvents;

/**
 * The turn in flight, built from the venue's live agent events exactly as
 * {@code AgentEvents} emits them (AGENT_LOOP.md §2.6): the steps it yields
 * are the ones the stored turn projects to afterwards.
 */
class LiveTurnTest {

	@SuppressWarnings("unchecked")
	private static AgentEvents.Event event(String type, ACell data) {
		return new AgentEvents.Event(Strings.create("did:key:owner"), Strings.create("Brightside"),
			1, 0, Strings.create(type), (AMap<AString, ACell>) data);
	}

	@Test
	void narrationAndToolCallsBecomeTheTurnsSteps() {
		LiveTurn t = new LiveTurn();
		assertEquals(LiveTurn.PREPARING, t.status());
		assertNull(t.activity(), "no steps before the first event");

		assertTrue(t.accepted());
		assertEquals(LiveTurn.WORKING, t.status());
		assertTrue(t.apply(event("inference:start", Maps.empty()), null));
		assertEquals(LiveTurn.THINKING, t.status());
		assertFalse(t.accepted(), "acceptance no longer has anything to say once the model is at work");

		// The model narrates and asks for a tool: the narration is a step and the status.
		assertTrue(t.apply(event("inference:end", Maps.of(
			"content", "Let me look at your notes.",
			"toolCalls", Vectors.of(Maps.of("id", "c1", "name", "covia_read")))), null));
		assertEquals("Let me look at your notes.", t.status());

		ACell input = Maps.of("path", "w/notes");
		assertTrue(t.apply(event("tool:start", Maps.of(
			"id", "c1", "name", "covia_read", "detail", Maps.of("input", input))), "Read a value"));
		assertEquals("Read a value…", t.status(), "the display name is the status; the step keeps the tool's name");
		SessionHistory.Step running = t.activity().steps().get(1);
		assertTrue(running.tool());
		assertEquals("covia_read", running.title());
		assertNull(running.detail(), "no result yet: the step is running");
		assertEquals(SessionHistory.renderContent(input), running.call());

		assertTrue(t.apply(event("tool:result", Maps.of(
			"id", "c1", "name", "covia_read", "ms", 12L, "detail", Maps.of("result", "hello"))), null));
		assertEquals(new SessionHistory.Activity(List.of(
			new SessionHistory.Step(false, "", "Let me look at your notes.", false, null),
			new SessionHistory.Step(true, "covia_read", "hello", false, SessionHistory.renderContent(input)))),
			t.activity());
		assertEquals("Read a value…", t.status(), "a result does not change the status line");

		// The final reply asks for no tool: it is the answer, which arrives through the chat job.
		assertFalse(t.apply(event("inference:end", Maps.of("content", "Here they are.")), null));
		assertEquals(2, t.activity().steps().size());
	}

	@Test
	void aFailedToolIsMarkedAndAResultWithoutAStartStillShows() {
		LiveTurn t = new LiveTurn();
		ACell structured = Maps.of("count", 3L);
		assertTrue(t.apply(event("tool:start", Maps.of("id", "c2", "name", "http_get", "detail", Maps.empty())), null));
		assertEquals("http get…", t.status(), "without a display name the tool's name is spaced");
		assertTrue(t.apply(event("tool:result", Maps.of(
			"id", "c2", "name", "http_get", "isError", true, "detail", Maps.of("result", "Error: refused"))), null));
		assertTrue(t.apply(event("tool:result", Maps.of(
			"id", "c9", "name", "covia_list", "detail", Maps.of("result", structured))), null));

		assertEquals(List.of(
			new SessionHistory.Step(true, "http_get", "Error: refused", true, null),
			new SessionHistory.Step(true, "covia_list", SessionHistory.renderContent(structured), false, null)),
			t.activity().steps());
	}

	@Test
	void otherEventsChangeNothing() {
		LiveTurn t = new LiveTurn();
		assertFalse(t.apply(event("status", Maps.of("status", "RUNNING")), null));
		assertFalse(t.apply(event("cycle:start", Maps.empty()), null));
		assertFalse(t.apply(event("inference:end", Maps.of(
			"toolCalls", Vectors.of(Maps.of("id", "c1", "name", "covia_read")))), null),
			"a tool request with nothing said is not a step");
		assertNull(t.activity());
		assertEquals(LiveTurn.PREPARING, t.status());
	}

	@Test
	void narrationIsCutToOneLineForTheStatus() {
		assertEquals("First line here", LiveTurn.oneLine("\n\nFirst   line\there\nsecond line"));
		String words = "word ".repeat(40).trim();
		String cut = LiveTurn.oneLine(words);
		assertTrue(cut.endsWith("…"), cut);
		assertTrue(cut.length() <= LiveTurn.STATUS_CHARS + 1, cut);
		assertFalse(cut.endsWith(" …"), "cut at a word boundary, trailing space dropped: " + cut);
	}
}
