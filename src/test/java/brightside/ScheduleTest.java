package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Status;
import covia.grid.Venue;

/**
 * Mechanism test for Settings → Schedule: {@link Schedule#list} reports the
 * principal's own pending events from the venue's scheduler, soonest first,
 * with each operation's display name and the outcome of a tracked recurring
 * event's last run — and reading it leaves no job record.
 */
class ScheduleTest {

	private static final long TIMEOUT_SECONDS = 30;
	private static final long HOUR = 3_600_000L;
	private static final String OP = "v/ops/covia/read";

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void listsOwnEventsWithLabelsAndTheLastRun() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("schedule-test").userDID(venue.did());
		Venue client = venue.clientAs(did);
		assertTrue(Schedule.list(client).isEmpty(), "a new user has nothing scheduled");

		long now = System.currentTimeMillis();
		ACell once = run(client, "v/ops/scheduler/schedule", Maps.of(
			"operation", OP, "input", Maps.of("path", "w"), "after", 2 * HOUR));
		ACell every = run(client, "v/ops/scheduler/schedule", Maps.of(
			"operation", OP, "input", Maps.of("path", "w"), "after", HOUR,
			"repeat", Maps.of("every", HOUR), "track", true));
		// Fire the recurring one now: it stays scheduled and records this run.
		run(client, "v/ops/scheduler/trigger", Maps.of("handle", RT.getIn(every, "handle")));

		long before = RecordedJobs.of(venue, did);
		List<Schedule.Event> events = Schedule.list(client);
		assertEquals(before, RecordedJobs.of(venue, did), "looking at the schedule leaves no job record");

		assertEquals(2, events.size());
		Schedule.Event first = events.get(0);
		Schedule.Event second = events.get(1);
		assertTrue(first.nextAt() <= second.nextAt(), "soonest first");

		assertTrue(first.recurring());
		assertEquals(HOUR, first.everyMillis());
		assertEquals(OP, first.operation());
		String name = RT.getIn(client.getAsset(OP).meta(), "name").toString();
		assertEquals(name, first.label(), "the operation's display name, not its reference");
		assertTrue(first.lastFiredAt() >= now, "the triggered run is recorded");
		assertNotNull(first.lastJob());
		assertTrue(first.tracked());
		assertEquals(Status.COMPLETE.toString(), first.lastStatus());
		assertNull(first.lastError());

		assertFalse(second.recurring());
		assertEquals(0, second.lastFiredAt(), "not run yet");
		assertNull(second.lastJob());
		assertNull(second.lastStatus());
		assertEquals(RT.getIn(once, "time"), convex.core.data.prim.CVMLong.create(second.nextAt()));

		String other = Identity.of("schedule-other").userDID(venue.did());
		assertTrue(Schedule.list(venue.clientAs(other)).isEmpty(), "another user's events are not shown");
	}

	@Test
	void anEventIsNamedByWhatItIsFor() throws Exception {
		EmbeddedVenue venue = shared.venue();
		Venue client = venue.clientAs(Identity.of("schedule-purpose").userDID(venue.did()));
		run(client, "v/ops/scheduler/schedule", Maps.of(
			"operation", OP, "input", Maps.of("path", "w"), "after", HOUR, "description", "Tidy the workspace"));
		Schedule.Event described = Schedule.list(client).get(0);
		assertEquals("Tidy the workspace", described.title(), "the description it was scheduled with");
		assertEquals(RT.getIn(client.getAsset(OP).meta(), "name").toString(), described.label());

		// One that hands an agent a task is named by the task, as its conversation will be.
		assertEquals("Daily check-in", Schedule.purpose(Maps.of("input",
			Maps.of("agentId", "Brightside", "input", Maps.of("task", "Daily check-in", "format", "brief")))));
		assertEquals("Say good morning", Schedule.purpose(Maps.of("input",
			Maps.of("agentId", "Brightside", "message", "Say good morning"))));
		assertNull(Schedule.purpose(Maps.of("input", Maps.of("path", "w"))), "nothing to say: the operation's name stands");
	}

	@Test
	void runNowAndCancelActOnlyOnTheOwnersEvents() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("schedule-actions").userDID(venue.did());
		Venue client = venue.clientAs(did);
		String once = handle(run(client, "v/ops/scheduler/schedule", Maps.of(
			"operation", OP, "input", Maps.of("path", "w"), "after", HOUR)));
		String every = handle(run(client, "v/ops/scheduler/schedule", Maps.of(
			"operation", OP, "input", Maps.of("path", "w"), "after", HOUR,
			"repeat", Maps.of("every", HOUR), "track", true)));
		assertEquals(2, Schedule.list(client).size());

		// Someone else can neither fire nor cancel them.
		Venue stranger = venue.clientAs(Identity.of("schedule-stranger").userDID(venue.did()));
		assertThrows(Exception.class, () -> Schedule.runNow(stranger, once, 10_000));
		assertThrows(Exception.class, () -> Schedule.cancel(stranger, every));
		assertEquals(2, Schedule.list(client).size());

		// Running a one-shot event now consumes it, and is recorded as an action.
		long before = RecordedJobs.of(venue, did);
		assertNotNull(Schedule.runNow(client, once, 30_000), "the run finished and returned its result");
		assertTrue(RecordedJobs.of(venue, did) > before, "running now leaves a job record");
		List<Schedule.Event> left = Schedule.list(client);
		assertEquals(1, left.size());
		assertTrue(left.get(0).recurring());

		// Cancelling removes it; a second cancel finds nothing.
		assertTrue(Schedule.cancel(client, left.get(0).handle()));
		assertTrue(Schedule.list(client).isEmpty());
		assertFalse(Schedule.cancel(client, left.get(0).handle()));
	}

	@Test
	void schedulesFromTheDialogAndRefusesWhatWouldFailLater() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("schedule-new").userDID(venue.did());
		Venue client = venue.clientAs(did);

		long before = RecordedJobs.of(venue, did);
		String handle = Schedule.schedule(client, OP, Schedule.parseInput("{path: 'w'}"), HOUR, 15 * 60_000L, true);
		assertNotNull(handle);
		assertTrue(RecordedJobs.of(venue, did) > before, "scheduling is an action and leaves a job record");
		List<Schedule.Event> events = Schedule.list(client);
		assertEquals(1, events.size());
		assertEquals(15 * 60_000L, events.get(0).everyMillis());
		assertTrue(events.get(0).tracked());
		assertNotNull(Schedule.runNow(client, events.get(0).handle(), 30_000), "the scheduled input is the one run");

		String once = Schedule.schedule(client, OP, null, HOUR, 0, false);
		assertNotNull(once);
		assertEquals(2, Schedule.list(client).size());

		assertThrows(IllegalArgumentException.class, () -> Schedule.schedule(client, "v/ops/no/such-op", null, HOUR, 0, false),
			"an operation that doesn't resolve is refused now, not when it fires");
		assertThrows(IllegalArgumentException.class, () -> Schedule.schedule(client, OP, null, HOUR, 500, false));
		assertEquals(2, Schedule.list(client).size(), "nothing refused was scheduled");

		assertNull(Schedule.parseInput("  "));
		assertEquals(Maps.of("path", "w"), Schedule.parseInput("{\"path\": \"w\"}"));
		assertThrows(IllegalArgumentException.class, () -> Schedule.parseInput("[1, 2]"), "only an object");
		assertThrows(IllegalArgumentException.class, () -> Schedule.parseInput("{path:"), "malformed");
	}

	private static String handle(ACell scheduled) {
		ACell h = RT.getIn(scheduled, "handle");
		return (h instanceof convex.core.data.Blob b) ? b.toHexString() : String.valueOf(h);
	}

	private static ACell run(Venue client, String op, ACell input) throws Exception {
		return client.run(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
