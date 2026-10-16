package brightside.ui.settings;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import brightside.Schedule;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Schedule</b> settings page: what is set to run on its own for the
 * acting principal — reminders, routines and check-ins scheduled by the owner,
 * the assistant or any other agent — soonest first. A summary strip says
 * what is next, how much repeats and how the last runs went; below it one
 * card per event shows when it runs next (counting down live), how often, and
 * how its last run went, with <em>Run now</em>, <em>Cancel</em> and a fold-out
 * of the details. Reading is a read ({@link Schedule}); the two buttons are
 * actions, each confirmed first.
 */
@SuppressWarnings("serial")
public final class SchedulePanel extends SettingsPage {

	/** What the page asks of the app. Each answers on the event thread. */
	public interface Host {
		/** Read the schedule again; the answer arrives through {@link SchedulePanel#show}. */
		void refresh();

		/** Fire the event now; {@code done} gets a note and whether it is an error. */
		void runNow(Schedule.Event event, Result done);

		/** Cancel the event; {@code done} gets a note and whether it is an error. */
		void cancel(Schedule.Event event, Result done);
	}

	/** How an action went: a note for the action bar, and whether it is an error. */
	public interface Result {
		void done(String note, boolean error);
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");
	private static final DateTimeFormatter WHEN_EXACT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss");
	private static final long SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
	/** How often the countdowns are redrawn while the page is on screen. */
	private static final int TICK_MS = 15_000;

	private final Host host;
	private final JPanel summary = new JPanel(new MigLayout("insets 0, fillx, gap 10", "[grow,fill,sg s][grow,fill,sg s][grow,fill,sg s]", ""));
	private final JPanel list = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 8", "[grow,fill]", ""));
	private final Timer ticker;
	private final List<Row> rows = new ArrayList<>();
	/** Handles whose details are open, kept across refreshes. */
	private final Set<String> expanded = new HashSet<>();
	private List<Schedule.Event> events = List.of();
	private boolean refreshPending;
	/** How the last Run now or Cancel went, kept over the refresh it triggers. */
	private String actionNote;
	private boolean actionError;

	public SchedulePanel(Host host) {
		super("Refresh");
		this.host = host;
		addDescription("What is set to run on its own: reminders, routines and check-ins that you, your assistant "
			+ "or another of your agents scheduled, soonest first. Each one runs with the permissions it was given "
			+ "when it was set up.");
		summary.setOpaque(false);
		list.setOpaque(false);
		addSpan(summary, "gapbottom 16");
		addSpan(list);
		primary.setIcon(Lucide.icon("refresh-cw", 14, () -> primary.getForeground()));
		primary.setToolTipText("Read the schedule again");
		onPrimary(this::requestRefresh);

		// Countdowns move on their own while the page is showing, and an event
		// that has come due is read again so it moves to its next slot.
		ticker = new Timer(TICK_MS, e -> tick());
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
			if (isShowing()) ticker.start();
			else ticker.stop();
		});
	}

	/**
	 * Shows the schedule as read: {@code events} soonest first, or the
	 * {@code error} that stopped the read — the page then says so rather than
	 * claiming nothing is scheduled.
	 */
	public void show(List<Schedule.Event> events, String error) {
		refreshPending = false;
		primary.setEnabled(true);
		this.events = (events == null) ? List.of() : List.copyOf(events);
		rows.clear();
		summary.removeAll();
		list.removeAll();
		if (error != null) {
			list.add(emptyState("circle-alert", Theme::error, "Couldn't read the schedule",
				"Try Refresh. If it keeps failing, the log has the details."));
			setNote(error, true);
		} else if (this.events.isEmpty()) {
			list.add(emptyState("calendar-clock", Theme::accentText, "Nothing is scheduled",
				"Ask your assistant to remind you of something, or to check in on something regularly, "
					+ "and it appears here."));
			clearNote();
		} else {
			long now = System.currentTimeMillis();
			buildSummary(now);
			expanded.retainAll(this.events.stream().map(Schedule.Event::handle).toList());
			for (Schedule.Event e : this.events) {
				Row row = new Row(e);
				rows.add(row);
				list.add(row.card);
			}
			tick(now);
			clearNote();
		}
		if (error == null && actionNote != null) setNote(actionNote, actionError);
		actionNote = null;
		revalidate();
		repaint();
	}

	/** Drops what was shown when the user logs out or switches. */
	public void clear() {
		events = List.of();
		rows.clear();
		expanded.clear();
		summary.removeAll();
		list.removeAll();
		clearNote();
		revalidate();
		repaint();
	}

	private void requestRefresh() {
		if (refreshPending) return;
		refreshPending = true;
		primary.setEnabled(false);
		setNote("Reading the schedule…", false);
		host.refresh();
	}

	private void tick() {
		long now = System.currentTimeMillis();
		// Something has come due: it has fired (or is firing) and moved on.
		for (Schedule.Event e : events) {
			if (e.nextAt() <= now - 2 * SECOND) {
				requestRefresh();
				return;
			}
		}
		tick(now);
	}

	private void tick(long now) {
		for (Row r : rows) r.update(now);
	}

	// ------------------------------------------------------------------
	// Summary strip
	// ------------------------------------------------------------------

	private void buildSummary(long now) {
		Schedule.Event next = events.get(0);
		long repeating = events.stream().filter(Schedule.Event::recurring).count();
		long failed = events.stream().filter(e -> "FAILED".equals(e.lastStatus())
			|| "TIMEOUT".equals(e.lastStatus()) || "REJECTED".equals(e.lastStatus())).count();
		long waiting = events.stream().filter(e -> "INPUT_REQUIRED".equals(e.lastStatus())
			|| "AUTH_REQUIRED".equals(e.lastStatus())).count();

		summary.add(stat("clock", Theme::accentText, "Next up", relative(next.nextAt(), now), next.label()));
		summary.add(stat("repeat", Theme::accentText, "Scheduled", plural(events.size(), "event"),
			repeating == 0 ? "None repeat" : repeating == events.size() ? "All repeat" : repeating + " repeat"));
		if (failed > 0) {
			summary.add(stat("circle-alert", Theme::error, "Last runs", failed + " failed", "Open one for the error"));
		} else if (waiting > 0) {
			summary.add(stat("circle-pause", Theme::warning, "Last runs", waiting + " waiting for you",
				"Answer in your Inbox"));
		} else {
			summary.add(stat("circle-check", Theme::success, "Last runs", "No failures", "Of those tracked"));
		}
	}

	private static JComponent stat(String icon, Supplier<Color> tone, String caption, String value, String detail) {
		Card c = new Card(12);
		c.setLayout(new MigLayout("insets 12 14 12 14, wrap 2, gapy 1", "[]12[grow,fill]", ""));
		c.add(badge(icon, tone, 36), "spany 3, aligny center");
		c.add(Labels.small(caption, Styles.MUTED), "wmin 0");
		JLabel v = Styles.style(new JLabel(value), "font: bold +1");
		c.add(v, "wmin 0");
		c.add(Labels.small(detail, Styles.MUTED), "wmin 0");
		return c;
	}

	private static JComponent emptyState(String icon, Supplier<Color> tone, String title, String body) {
		Card c = new Card(14);
		c.setLayout(new MigLayout("insets 28 24 28 24, fillx, wrap 1, gapy 6", "[center]", ""));
		c.add(badge(icon, tone, 48), "gapbottom 6");
		c.add(Styles.style(new JLabel(title), "font: bold +1"));
		JLabel b = Labels.html(body, 380, SwingConstants.CENTER);
		Styles.classes(b, Styles.MUTED);
		c.add(b);
		return c;
	}

	// ------------------------------------------------------------------
	// One event
	// ------------------------------------------------------------------

	private final class Row {
		final Schedule.Event event;
		final Card card = new Card(14);
		final JLabel next = Styles.classes(new JLabel(), Styles.STRONG);
		final JLabel nextAt = Labels.small("", Styles.MUTED);
		final JLabel lastWhen = Labels.small("", Styles.MUTED);
		final JButton run = Buttons.icon(Lucide.icon("play", 16, Theme::muted), "Run now");
		final JButton cancel = Buttons.icon(Lucide.icon("trash-2", 16, Theme::muted), "Cancel…");
		final JButton fold = Buttons.icon(null, null);
		final JPanel details;

		Row(Schedule.Event e) {
			this.event = e;
			card.setLayout(new MigLayout("insets 12 14 12 10, fillx, hidemode 3, gapy 0",
				"[]14[grow,fill,180:]16[140!]12[150!]8[]2[]2[]", "[]"));

			// What it is: the kind as a badge, the name, how often, the reference.
			card.add(badge(e.recurring() ? "repeat" : "calendar-clock", Theme::accentText, 38), "aligny top");
			JPanel what = column();
			JLabel name = Styles.style(new JLabel(e.label()), "font: bold +1");
			name.setToolTipText(e.operation());
			what.add(name, "wmin 0");
			JPanel chips = new JPanel(new MigLayout("insets 0, gap 6", "", ""));
			chips.setOpaque(false);
			chips.add(e.recurring() ? pill("repeat", every(e.everyMillis())) : pill("calendar-clock", "Once"));
			what.add(chips, "wmin 0, gaptop 4");
			card.add(what, "wmin 0, aligny top");

			// When it runs next, counting down.
			JPanel when = column();
			JPanel nextLine = new JPanel(new MigLayout("insets 0, gap 6", "", ""));
			nextLine.setOpaque(false);
			nextLine.add(new JLabel(Lucide.icon("clock", 14, Theme::muted)));
			nextLine.add(next);
			when.add(Labels.small("Next run", Styles.MUTED));
			when.add(nextLine, "gaptop 2");
			when.add(nextAt, "gaptop 1");
			card.add(when, "aligny top");

			// How the last run went.
			JPanel last = column();
			last.add(Labels.small("Last run", Styles.MUTED));
			last.add(lastOutcome(e), "gaptop 2");
			last.add(lastWhen, "gaptop 1");
			card.add(last, "aligny top");

			run.addActionListener(a -> onRun());
			cancel.addActionListener(a -> onCancel());
			fold.addActionListener(a -> toggle());
			card.add(run, "aligny center");
			card.add(cancel, "aligny center");
			card.add(fold, "aligny center, wrap");

			details = details(e);
			card.add(details, "span, growx, wmin 0, gaptop 12");
			setExpanded(expanded.contains(e.handle()));

			// A hover outline, and a click anywhere outside the buttons folds the details.
			MouseAdapter hover = new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent m) {
					card.outline(() -> Theme.fade(Theme.accent(), 0.45f));
				}

				@Override
				public void mouseExited(MouseEvent m) {
					if (!card.contains(javax.swing.SwingUtilities.convertPoint(m.getComponent(), m.getPoint(), card))) {
						card.outline((Color) null);
					}
				}

				@Override
				public void mouseClicked(MouseEvent m) {
					toggle();
				}
			};
			card.addMouseListener(hover);
			for (java.awt.Component c : new java.awt.Component[] { what, when, last }) c.addMouseListener(hover);
			card.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		}

		void update(long now) {
			long d = event.nextAt() - now;
			next.setText(d <= 0 ? "Due now" : relative(event.nextAt(), now));
			if (d < HOUR) Styles.classes(next, Styles.STRONG, Styles.ACCENT);
			else Styles.classes(next, Styles.STRONG);
			nextAt.setText(absolute(event.nextAt()));
			nextAt.setToolTipText(exact(event.nextAt()));
			lastWhen.setText(event.lastFiredAt() > 0 ? relative(event.lastFiredAt(), now) : " ");
			lastWhen.setToolTipText(event.lastFiredAt() > 0 ? exact(event.lastFiredAt()) : null);
		}

		void toggle() {
			setExpanded(!details.isVisible());
		}

		void setExpanded(boolean on) {
			details.setVisible(on);
			if (on) expanded.add(event.handle());
			else expanded.remove(event.handle());
			fold.setIcon(Lucide.icon(on ? "chevron-down" : "chevron-right", 16, Theme::muted));
			fold.setToolTipText(on ? "Hide the details" : "Show the details");
			card.revalidate();
			card.repaint();
		}

		void onRun() {
			String also = event.recurring()
				? "It stays scheduled and runs again " + relative(event.nextAt(), System.currentTimeMillis()).toLowerCase() + "."
				: "It runs once now and will not run again later.";
			if (!Dialogs.confirm(SchedulePanel.this, "Run now", "Run \"" + event.label() + "\" now? " + also)) return;
			busy(true, "Running " + event.label() + "…");
			host.runNow(event, this::finished);
		}

		void onCancel() {
			String what = event.recurring() ? "It stops repeating and will not run again."
				: "It will not run.";
			if (!Dialogs.confirmDanger(SchedulePanel.this, "Cancel scheduled run",
				"Cancel \"" + event.label() + "\"? " + what)) return;
			busy(true, "Cancelling " + event.label() + "…");
			host.cancel(event, this::finished);
		}

		void finished(String note, boolean error) {
			busy(false, null);
			actionNote = note;
			actionError = error;
			requestRefresh();
		}

		void busy(boolean on, String note) {
			run.setEnabled(!on);
			cancel.setEnabled(!on);
			run.setIcon(Lucide.icon(on ? "loader-circle" : "play", 16, Theme::muted));
			if (note != null) setNote(note, false);
		}
	}

	private JPanel details(Schedule.Event e) {
		JPanel d = new JPanel(new MigLayout("insets 12 52 2 0, fillx, wrap 2, gapy 6", "[]16[grow,fill]", ""));
		d.setOpaque(false);
		d.setBorder(brightside.ui.components.Borders.hairlineTop());
		detail(d, "Operation", e.operation(), true);
		detail(d, "Repeats", e.recurring() ? every(e.everyMillis()) + ", on a fixed grid; missed runs are skipped"
			: "No — it runs once", false);
		detail(d, "Next run", exact(e.nextAt()), false);
		detail(d, "Last run", e.lastFiredAt() > 0 ? exact(e.lastFiredAt()) : "Not yet", false);
		detail(d, "History", e.tracked() ? "Each run is kept in your job history"
			: "Runs aren't kept, so how they went isn't known", false);
		if (e.lastJob() != null) detail(d, "Last job", e.lastJob(), true);
		if (e.lastError() != null) {
			d.add(Labels.small("Error", Styles.MUTED), "aligny top");
			SelectableText err = new SelectableText(e.lastError()).tone(Styles.ERROR);
			d.add(err, "growx, wmin 0");
		}
		detail(d, "Handle", e.handle(), true);
		return d;
	}

	private static void detail(JPanel d, String label, String value, boolean copyable) {
		if (value == null) return;
		d.add(Labels.small(label, Styles.MUTED), "aligny top");
		JPanel line = new JPanel(new MigLayout("insets 0, fillx, gap 4", "[grow,fill][]", ""));
		line.setOpaque(false);
		SelectableText v = new SelectableText(value);
		if (copyable) v.mono();
		line.add(v, "growx, wmin 0");
		if (copyable) {
			JButton copy = Buttons.icon(Lucide.icon("copy", 14, Theme::muted), "Copy");
			copy.addActionListener(a -> Clipboard.copy(value));
			line.add(copy);
		}
		d.add(line, "growx, wmin 0");
	}

	private static JComponent lastOutcome(Schedule.Event e) {
		if (e.lastFiredAt() <= 0) return iconLabel("hourglass", Theme::muted, "Not run yet", null);
		String s = e.lastStatus();
		if (s == null) {
			return e.tracked() ? iconLabel("history", Theme::muted, "Ran", null)
				: iconLabel("history", Theme::muted, "Ran", "Runs aren't kept, so how it went isn't known");
		}
		JComponent l = switch (s) {
			case "COMPLETE" -> iconLabel("circle-check", Theme::success, "Completed", null);
			case "FAILED" -> iconLabel("circle-alert", Theme::error, "Failed", e.lastError());
			case "TIMEOUT" -> iconLabel("circle-alert", Theme::error, "Timed out", e.lastError());
			case "REJECTED" -> iconLabel("circle-x", Theme::error, "Refused", e.lastError());
			case "CANCELLED" -> iconLabel("circle-x", Theme::muted, "Cancelled", null);
			case "INPUT_REQUIRED", "AUTH_REQUIRED" -> iconLabel("circle-pause", Theme::warning, "Waiting for you",
				"It asked you something: answer it in your Inbox");
			case "PAUSED" -> iconLabel("circle-pause", Theme::muted, "Paused", null);
			case "PENDING", "STARTED" -> iconLabel("loader-circle", Theme::accentText, "Running", null);
			default -> iconLabel("history", Theme::muted, s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' '), null);
		};
		return l;
	}

	private static JLabel iconLabel(String icon, Supplier<Color> tone, String text, String tooltip) {
		// The text takes the icon's tone, read when painting so it follows a theme change.
		JLabel l = new JLabel(text, Lucide.icon(icon, 14, tone), SwingConstants.LEADING) {
			@Override
			public Color getForeground() {
				return tone.get();
			}
		};
		l.setIconTextGap(6);
		l.setToolTipText(tooltip);
		return l;
	}

	// ------------------------------------------------------------------
	// Small pieces
	// ------------------------------------------------------------------

	/** A round tinted badge holding an icon in {@code tone}. */
	private static JComponent badge(String icon, Supplier<Color> tone, int size) {
		Card b = new Card(size).fill(() -> Theme.fade(tone.get(), Theme.isDark() ? 0.18f : 0.14f));
		b.setLayout(new MigLayout("insets 0, align 50% 50%", "[center]", "[center]"));
		b.add(new JLabel(Lucide.icon(icon, size / 2, tone)));
		Dimension d = new Dimension(size, size);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		return b;
	}

	/** A small rounded chip: an icon and a word or two. */
	private static JComponent pill(String icon, String text) {
		Card p = new Card(999).fill(() -> Theme.fade(Theme.accent(), Theme.isDark() ? 0.16f : 0.11f));
		p.setLayout(new MigLayout("insets 2 8 2 9, gap 4", "[][]", "[]"));
		p.add(new JLabel(Lucide.icon(icon, 12, Theme::accentText)));
		JLabel l = Labels.small(text);
		Styles.style(l, "foreground: $Brightside.tinted.foreground");
		p.add(l);
		return p;
	}

	private static JPanel column() {
		JPanel p = new JPanel(new MigLayout("insets 0, wrap 1, gapy 0", "[grow,fill]", ""));
		p.setOpaque(false);
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

	private static String exact(long at) {
		return WHEN_EXACT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
	}
}
