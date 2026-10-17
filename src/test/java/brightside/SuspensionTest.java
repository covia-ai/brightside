package brightside;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.AgentEvents;

/**
 * Telling a failed turn's suspension from one made on purpose, as Covia records
 * them. The last two tests have a real venue write the records, so a change in
 * what Covia records fails here rather than letting Brightside quietly restart
 * an agent that was stopped on purpose.
 */
class SuspensionTest {

	private static final long TIMEOUT_SECONDS = 30;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
		userDID = Identity.of("suspension-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
	}

	@Test
	void aFailedTurnIsAFault() {
		var failed = Maps.of("status", "SUSPENDED", "error", "Transition failed: no such model");
		assertTrue(Suspension.byFault(failed));
		assertFalse(Suspension.deliberate(failed));
	}

	@Test
	void agentSuspendIsDeliberateWhetherOrNotItCutATurnShort() {
		assertTrue(Suspension.deliberate(Maps.of("status", "SUSPENDED")));
		assertTrue(Suspension.deliberate(Maps.of("status", "SUSPENDED", "error", Suspension.CANCELLED)));
		assertFalse(Suspension.byFault(Maps.of("status", "SUSPENDED", "error", Suspension.CANCELLED)));
	}

	@Test
	void aRunningAgentIsNeither() {
		var sleeping = Maps.of("status", "SLEEPING", "error", "stale");
		assertFalse(Suspension.byFault(sleeping));
		assertFalse(Suspension.deliberate(sleeping));
		assertFalse(Suspension.deliberate(null));
	}

	@Test
	void anAgentStoppedMidTurnOnAVenueWasStoppedOnPurpose() throws Exception {
		String agent = "stopped-mid-turn";
		// Its turn never finishes by itself, so the stop always cuts it short.
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agent, Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/test/ops/never")));
		CountDownLatch turnStarted = new CountDownLatch(1);
		Job task;
		try (AutoCloseable events = venue.subscribeAgent(userDID, agent, e -> {
				if (AgentEvents.CYCLE_START.equals(e.type())) turnStarted.countDown();
			})) {
			task = request(agent);
			assertTrue(turnStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "its turn never started");
		}

		run("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, agent));
		// Covia fails the cut-short task once it has recorded the suspension.
		assertThrows(ExecutionException.class, () -> task.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		ACell record = venue.agentRecord(userDID, agent);
		assertTrue(Suspension.deliberate(record), "stopped on purpose: " + record);
	}

	@Test
	void anAgentWhoseTurnFailsOnAVenueIsSuspendedByFault() throws Exception {
		String agent = "failed-turn";
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agent, Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/test/ops/error")));
		Job task = request(agent);

		// Covia fails the task once it has recorded the suspension.
		assertThrows(ExecutionException.class, () -> task.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		ACell record = venue.agentRecord(userDID, agent);
		assertTrue(Suspension.byFault(record), "a failed turn: " + record);
	}

	private static Job request(String agent) throws Exception {
		return user.invoke("v/ops/agent/request", Maps.of(Fields.AGENT_ID, agent, Fields.INPUT, Maps.of("ask", "work")))
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static ACell run(String op, AMap<AString, ACell> input) throws Exception {
		return user.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
