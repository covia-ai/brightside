package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	private static ACell run(Venue client, String op, ACell input) throws Exception {
		return client.run(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
