package brightside;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;

/** Telling a failed turn's suspension from one made on purpose, as Covia records them. */
class SuspensionTest {

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
}
