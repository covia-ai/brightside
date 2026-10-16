package brightside;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The acting principal's scheduled operations, as Settings → Schedule shows
 * them: the venue's {@code scheduler:list} (pending events, soonest first,
 * including those the principal's agents queued), each joined with its
 * operation's display name and, for a tracked recurring event, the outcome of
 * its most recent run.
 *
 * <p>Looking is a read: {@code scheduler:list} is declared readOnly, and the
 * operation and job lookups are plain queries, so it leaves no job record.
 * {@link #runNow} and {@link #cancel} are actions and leave one each.
 */
public final class Schedule {

	private static final long TIMEOUT_SECONDS = 30;
	static final String OP_LIST = "v/ops/scheduler/list";
	static final String OP_TRIGGER = "v/ops/scheduler/trigger";
	static final String OP_CANCEL = "v/ops/scheduler/cancel";

	/**
	 * One pending event. Times are epoch millis; {@code everyMillis} is 0 for a
	 * one-shot event and {@code lastFiredAt} 0 before the first run.
	 * {@code lastStatus} and {@code lastError} describe the last tracked run,
	 * null when it is not tracked or not known.
	 */
	public record Event(String handle, String operation, String label, long nextAt, long everyMillis,
			long lastFiredAt, String lastJob, String lastStatus, String lastError, boolean tracked) {

		public boolean recurring() {
			return everyMillis > 0;
		}
	}

	private Schedule() {
	}

	/**
	 * The events {@code client}'s principal has pending, soonest first. Throws
	 * when the venue cannot be asked, so a failure is never mistaken for an
	 * empty schedule.
	 */
	public static List<Event> list(Venue client) throws Exception {
		ACell out = client.run(OP_LIST, Maps.empty()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		ACell events = RT.getIn(out, "events");
		List<Event> list = new ArrayList<>();
		if (!(events instanceof AVector<?> vec)) return list;
		Map<String, String> labels = new HashMap<>();
		for (long i = 0; i < vec.count(); i++) {
			ACell e = vec.get(i);
			if (!(e instanceof AMap)) continue;
			String op = str(RT.getIn(e, "op"));
			String handle = hex(RT.getIn(e, "handle"));
			String lastJob = hex(RT.getIn(e, "lastJob"));
			String status = null;
			String error = null;
			if (lastJob != null) {
				AMap<AString, ACell> job = jobStatus(client, lastJob);
				status = (job != null) ? str(job.get(Fields.STATUS)) : null;
				error = (job != null) ? str(job.get(Fields.ERROR)) : null;
			}
			String label = (op == null) ? "Unknown operation" : labels.computeIfAbsent(op, o -> label(client, o));
			list.add(new Event(handle, op, label, num(RT.getIn(e, "time")), num(RT.getIn(e, "repeat", "every")),
				num(RT.getIn(e, "lastFired")), lastJob, status, error, CVMBool.TRUE.equals(RT.getIn(e, "track"))));
		}
		return list;
	}

	/**
	 * Fires {@code handle} now, ahead of its time, and waits up to
	 * {@code waitMillis} for the run to finish. A recurring event stays
	 * scheduled at its unchanged next time; a one-shot one is consumed. An
	 * action, so it leaves a job record. Returns the run's result when it
	 * finished in time, or null while it is still running; throws when the
	 * event is gone or the run failed.
	 */
	public static ACell runNow(Venue client, String handle, long waitMillis) throws Exception {
		Job job = client.invoke(OP_TRIGGER, Maps.of("handle", handle)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		try {
			return job.future().get(waitMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return null;
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof Exception cause) ? cause : e;
		}
	}

	/** Cancels {@code handle}; false if it was already gone. An action, so it leaves a job record. */
	public static boolean cancel(Venue client, String handle) throws Exception {
		Job job = client.invoke(OP_CANCEL, Maps.of("handle", handle)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		ACell out = job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return CVMBool.TRUE.equals(RT.getIn(out, "cancelled"));
	}

	/** The operation's display name from its metadata, or the last segment of its reference. */
	static String label(Venue client, String op) {
		try {
			Asset asset = client.getAsset(op);
			String name = (asset != null) ? str(RT.getIn(asset.meta(), "name")) : null;
			if (name != null && !name.isBlank()) return name;
		} catch (Exception e) {
			// Not resolvable here (removed, remote): fall back to the reference.
		}
		int slash = op.lastIndexOf('/');
		return (slash >= 0 && slash < op.length() - 1) ? op.substring(slash + 1) : op;
	}

	private static AMap<AString, ACell> jobStatus(Venue client, String jobId) {
		try {
			return client.getJobStatus(jobId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			return null; // the job record may have been deleted since
		}
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	private static String hex(ACell c) {
		if (c instanceof Blob b) return b.toHexString();
		return str(c);
	}

	private static long num(ACell c) {
		return (c instanceof CVMLong l) ? l.longValue() : 0;
	}
}
