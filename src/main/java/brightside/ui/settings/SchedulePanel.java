package brightside.ui.settings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.Schedule;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Schedule</b> settings page: what is set to run on its own for the
 * acting principal — reminders, routines and check-ins the assistant (or any
 * of the owner's agents) scheduled — soonest first, with when each runs next,
 * how often, and how its last run went. Read-only: a refresh is a read and
 * leaves no job record ({@link Schedule}).
 */
@SuppressWarnings("serial")
public final class SchedulePanel extends SettingsPage {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");
	private static final long SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

	private final JPanel rows = new JPanel(new MigLayout(
		"insets 0, fillx, wrap 4, gapy 0", "[grow 40,fill]16[grow 25,fill]16[grow 15,fill]16[grow 20,fill]", ""));

	/** @param refresh asks the app to read the schedule again; it answers through {@link #show}. */
	public SchedulePanel(Runnable refresh) {
		super("Refresh");
		addDescription("What is set to run on its own: reminders, routines and check-ins your assistant scheduled "
			+ "for you, soonest first. Each one runs with the permissions it was given when it was set up. "
			+ "To change or stop one, ask your assistant.");
		rows.setOpaque(false);
		addSpan(rows);
		primary.setToolTipText("Read the schedule again");
		onPrimary(() -> {
			setNote("Reading the schedule…", false);
			refresh.run();
		});
	}

	/**
	 * Shows the schedule as read: {@code events} soonest first, or the
	 * {@code error} that stopped the read (the rows then say so rather than
	 * claiming nothing is scheduled).
	 */
	public void show(List<Schedule.Event> events, String error) {
		rows.removeAll();
		if (error != null) {
			rows.add(Labels.small("Couldn't read the schedule.", Styles.ERROR), "span 4");
			setNote(error, true);
		} else if (events == null || events.isEmpty()) {
			rows.add(Labels.small("Nothing is scheduled. Ask your assistant to remind you of something or to check "
				+ "in regularly, and it appears here.", Styles.MUTED), "span 4, wmin 0");
			clearNote();
		} else {
			long now = System.currentTimeMillis();
			header("What");
			header("Next run");
			header("Repeats");
			header("Last run");
			for (int i = 0; i < events.size(); i++) addRow(events.get(i), now, i > 0);
			setNote(events.size() == 1 ? "1 scheduled." : events.size() + " scheduled.", false);
		}
		rows.revalidate();
		rows.repaint();
	}

	/** Drops what was shown when the user logs out or switches. */
	public void clear() {
		rows.removeAll();
		rows.revalidate();
		rows.repaint();
		clearNote();
	}

	private void header(String text) {
		rows.add(Labels.small(text, Styles.MUTED), "gapbottom 6");
	}

	private void addRow(Schedule.Event e, long now, boolean rule) {
		if (rule) rows.add(Panels.rule(), "span 4, growx, gaptop 8, gapbottom 8");

		JLabel what = Styles.classes(new JLabel(e.label()), Styles.STRONG);
		JLabel ref = Labels.small(e.operation() != null ? e.operation() : "", Styles.MUTED);
		Styles.add(ref, Styles.MONOSPACED);
		ref.setToolTipText("Handle " + e.handle());
		rows.add(stack(what, ref), "wmin 0, aligny top");

		JLabel next = Labels.text(relative(e.nextAt(), now));
		rows.add(stack(next, Labels.small(absolute(e.nextAt()), Styles.MUTED)), "wmin 0, aligny top");

		rows.add(Labels.text(e.recurring() ? every(e.everyMillis()) : "Once"), "wmin 0, aligny top");

		rows.add(lastRun(e, now), "wmin 0, aligny top");
	}

	private static JComponent lastRun(Schedule.Event e, long now) {
		if (e.lastFiredAt() <= 0) return Labels.text("Not yet");
		JLabel when = Labels.text(relative(e.lastFiredAt(), now));
		when.setToolTipText(absolute(e.lastFiredAt()));
		JLabel outcome;
		if (e.lastStatus() != null) {
			outcome = Labels.small(outcome(e.lastStatus()), tone(e.lastStatus()));
			if (e.lastError() != null) outcome.setToolTipText(e.lastError());
		} else if (!e.tracked()) {
			outcome = Labels.small("Outcome not kept", Styles.MUTED);
			outcome.setToolTipText("This one runs untracked, so no record of how it went is kept");
		} else {
			outcome = Labels.small("Outcome unknown", Styles.MUTED);
		}
		return stack(when, outcome);
	}

	private static JComponent stack(JComponent top, JComponent below) {
		JPanel p = Panels.column();
		p.add(top);
		p.add(below);
		top.setAlignmentX(LEFT_ALIGNMENT);
		below.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	/** "In 5 minutes", "2 hours ago": the distance from now, rounded, in the largest unit that fits. */
	static String relative(long at, long now) {
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

	/** "Every 15 minutes", "Every hour", "Every 2 days": an interval in its largest exact unit. */
	static String every(long millis) {
		if (millis % DAY == 0) return everyUnit(millis / DAY, "day");
		if (millis % HOUR == 0) return everyUnit(millis / HOUR, "hour");
		if (millis % MINUTE == 0) return everyUnit(millis / MINUTE, "minute");
		return everyUnit(Math.max(1, millis / SECOND), "second");
	}

	private static String everyUnit(long n, String unit) {
		return (n == 1) ? "Every " + unit : "Every " + n + " " + unit + "s";
	}

	private static String plural(long n, String unit) {
		return n + " " + unit + (n == 1 ? "" : "s");
	}

	private static String absolute(long at) {
		return WHEN.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
	}

	private static String outcome(String status) {
		return switch (status) {
			case "COMPLETE" -> "Completed";
			case "FAILED" -> "Failed";
			case "PENDING", "STARTED" -> "Running";
			case "CANCELLED" -> "Cancelled";
			case "TIMEOUT" -> "Timed out";
			case "REJECTED" -> "Refused";
			case "INPUT_REQUIRED", "AUTH_REQUIRED" -> "Waiting for you";
			case "PAUSED" -> "Paused";
			default -> status.charAt(0) + status.substring(1).toLowerCase().replace('_', ' ');
		};
	}

	private static String tone(String status) {
		return switch (status) {
			case "COMPLETE" -> Styles.SUCCESS;
			case "FAILED", "TIMEOUT", "REJECTED" -> Styles.ERROR;
			case "INPUT_REQUIRED", "AUTH_REQUIRED" -> Styles.WARNING;
			default -> Styles.MUTED;
		};
	}
}
