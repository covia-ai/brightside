package brightside.ui.components;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Times as screens show them, in the owner's own time zone: how far off
 * something is, how long it has been going, and the date and time itself.
 */
public final class Times {

	public static final long SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");
	private static final DateTimeFormatter WHEN_EXACT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss");

	private Times() {
	}

	/** "In 5 minutes", "2 hours ago": the distance from now, rounded, in the largest unit that fits. */
	public static String relative(long at, long now) {
		long d = at - now;
		long a = Math.abs(d);
		if (a < MINUTE) return (d >= 0) ? "In a moment" : "Just now";
		long minutes = Math.round((double) a / MINUTE);
		long hours = Math.round((double) a / HOUR);
		String span = (minutes < 60) ? plural(minutes, "minute")
			: (hours < 24) ? plural(hours, "hour")
			: plural(Math.round((double) a / DAY), "day");
		return (d >= 0) ? "In " + span : span.substring(0, 1).toUpperCase() + span.substring(1) + " ago";
	}

	/** How long something has been going: "under a minute", "13 minutes", "2 hours 5 minutes", "3 days". */
	public static String elapsed(long millis) {
		if (millis < MINUTE) return "under a minute";
		if (millis < HOUR) return plural(millis / MINUTE, "minute");
		if (millis < DAY) {
			long minutes = (millis % HOUR) / MINUTE;
			return plural(millis / HOUR, "hour") + (minutes > 0 ? " " + plural(minutes, "minute") : "");
		}
		return plural(millis / DAY, "day");
	}

	/** "Fri 13 Nov, 09:05". */
	public static String absolute(long at) {
		return WHEN.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
	}

	/** "Fri 13 Nov 2026, 09:05:12". */
	public static String exact(long at) {
		return WHEN_EXACT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
	}

	/** "1 job", "3 jobs". */
	public static String plural(long n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}
}
