package brightside;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.AgentState;

/**
 * Why an agent is suspended, read from its record or its {@code agent:info}
 * summary — both carry its status and error.
 *
 * <p>Covia suspends an agent in two ways. A failed turn (a bad model route, a
 * missing key) suspends it with the failure as its error; re-applying the
 * configuration and resuming is the usual cure, and Brightside does that by
 * itself. {@code agent:suspend} — the owner, the operator or {@link Thor}
 * stopping it on purpose — records who stopped it, when and why, under
 * {@code suspension}, which a failure racing the stop does not displace.
 * A store from before that record says the same by leaving the error empty,
 * or {@value #CANCELLED} when the stop cut a turn short. Brightside never
 * lifts a deliberate stop by itself: whoever stopped it, or the owner, decides.
 */
public final class Suspension {

	private static final String SUSPENDED = "SUSPENDED";
	/** The error Covia records when {@code agent:suspend} cuts a turn short. */
	static final String CANCELLED = "Transition cancelled";

	private Suspension() {
	}

	/** Whether {@code agent} is suspended because a turn failed. */
	public static boolean byFault(ACell agent) {
		if (!suspended(agent) || RT.getIn(agent, AgentState.KEY_SUSPENSION) != null) return false;
		AString error = RT.ensureString(RT.getIn(agent, Fields.ERROR));
		return error != null && !CANCELLED.equals(error.toString());
	}

	/** Whether {@code agent} was suspended on purpose, by {@code agent:suspend}. */
	public static boolean deliberate(ACell agent) {
		return suspended(agent) && !byFault(agent);
	}

	/** Why it was stopped on purpose, in the words of whoever stopped it, or null. */
	public static String reason(ACell agent) {
		AString reason = RT.ensureString(RT.getIn(agent, AgentState.KEY_SUSPENSION, Fields.REASON));
		return (reason == null || reason.isEmpty()) ? null : reason.toString();
	}

	private static boolean suspended(ACell agent) {
		AString status = RT.ensureString(RT.getIn(agent, Fields.STATUS));
		return status != null && SUSPENDED.equals(status.toString());
	}
}
