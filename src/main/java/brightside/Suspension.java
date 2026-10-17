package brightside;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Why an agent is suspended, read from its record or its {@code agent:info}
 * summary — both carry its status and error.
 *
 * <p>Covia suspends an agent in two ways. A failed turn (a bad model route, a
 * missing key) suspends it with the failure as its error; re-applying the
 * configuration and resuming is the usual cure, and Brightside does that by
 * itself. {@code agent:suspend} — the owner, the operator or {@link Thor}
 * stopping it on purpose — suspends it with no error, or with
 * {@value #CANCELLED} when the stop cut a turn short. Brightside never lifts
 * one of those by itself: whoever stopped it, or the owner, decides.
 */
public final class Suspension {

	private static final String SUSPENDED = "SUSPENDED";
	/** The error Covia records when {@code agent:suspend} cuts a turn short. */
	static final String CANCELLED = "Transition cancelled";

	private Suspension() {
	}

	/** Whether {@code agent} is suspended because a turn failed. */
	public static boolean byFault(ACell agent) {
		if (!suspended(agent)) return false;
		AString error = RT.ensureString(RT.getIn(agent, Fields.ERROR));
		return error != null && !CANCELLED.equals(error.toString());
	}

	/** Whether {@code agent} was suspended on purpose, by {@code agent:suspend}. */
	public static boolean deliberate(ACell agent) {
		return suspended(agent) && !byFault(agent);
	}

	private static boolean suspended(ACell agent) {
		AString status = RT.ensureString(RT.getIn(agent, Fields.STATUS));
		return status != null && SUSPENDED.equals(status.toString());
	}
}
