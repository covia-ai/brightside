package brightside.ui.jobs;

import brightside.JobList;
import brightside.ui.components.Times;
import brightside.ui.components.Tone;

/** How a job's status and circumstances read on screen, shared by its row and its detail. */
final class JobLooks {

	private JobLooks() {
	}

	/** The status in words. */
	static String status(JobList.Job job) {
		String s = job.status();
		if (s == null) return "Unknown";
		return switch (s) {
			case "PENDING" -> "Queued";
			case "STARTED" -> "Running";
			case "PAUSED" -> "Paused";
			case "INPUT_REQUIRED", "AUTH_REQUIRED" -> "Waiting for you";
			case "COMPLETE" -> "Completed";
			case "FAILED" -> "Failed";
			case "TIMEOUT" -> "Timed out";
			case "REJECTED" -> "Refused";
			case "CANCELLED" -> "Cancelled";
			default -> s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
		};
	}

	/** The {@code Lucide} icon beside the status. */
	static String icon(JobList.Job job) {
		JobList.Group g = job.group();
		if (g == null) return "history";
		return switch (g) {
			case ACTIVE -> "PAUSED".equals(job.status()) ? "circle-pause" : "loader-circle";
			case WAITING -> "circle-pause";
			case FAILED -> "circle-alert";
			case COMPLETED -> "circle-check";
			case CANCELLED -> "circle-x";
		};
	}

	static Tone tone(JobList.Job job) {
		JobList.Group g = job.group();
		if (g == null) return Tone.MUTED;
		return switch (g) {
			case ACTIVE -> "PAUSED".equals(job.status()) ? Tone.MUTED : Tone.ACCENT;
			case WAITING -> Tone.WARNING;
			case FAILED -> Tone.ERROR;
			case COMPLETED -> Tone.SUCCESS;
			case CANCELLED -> Tone.MUTED;
		};
	}

	/** Who started it, in words: "You", the agent's name, or the caller. */
	static String startedBy(JobList.Job job) {
		return switch (job.starter()) {
			case YOU -> "You";
			case AGENTS -> (job.agent() != null) ? job.agent() : "An agent";
			case OTHERS -> (job.caller() != null) ? shortDID(job.caller()) : "Someone else";
		};
	}

	/** The line under a job's title: what it ran, who started it, when, and for how long. */
	static String meta(JobList.Job job, long now) {
		StringBuilder line = new StringBuilder();
		if (job.purpose() != null) line.append(job.name()).append(" · ");
		line.append(startedBy(job));
		if (job.created() > 0) line.append(" · ").append(Times.relative(job.created(), now));
		if (job.created() > 0 && !"PENDING".equals(job.status())) {
			line.append(" · ").append(job.finished() ? "took " : "for ").append(Times.elapsed(job.elapsed(now)));
		}
		return line.toString();
	}

	/** A DID shortened for a line of text: its last part. */
	static String shortDID(String did) {
		int colon = did.lastIndexOf(':');
		String tail = (colon >= 0) ? did.substring(colon + 1) : did;
		return (tail.length() > 16) ? tail.substring(0, 6) + "…" + tail.substring(tail.length() - 4) : tail;
	}
}
