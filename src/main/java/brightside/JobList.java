package brightside;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Venue;
import covia.venue.User;
import covia.venue.Users;

/**
 * A principal's jobs, as the Jobs page shows them: their {@code j/} index, the
 * Covia {@code Jobs} lattice component, read straight from the in-process
 * lattice, and viewed newest first through a {@link Filter}.
 *
 * <p>The index is keyed by job id, and ids rise with submission time: their
 * first 48 bits are the creation time in milliseconds (covia-ai/covia#700). So
 * a time range is a {@link Index#seek} for its lower bound, and a row is a
 * position counted back from the end: a {@link View} finds its rows without
 * reading the records, unless a status or starter filter needs to look at each
 * one, and then only at those two fields. A row's {@link Job} summary is
 * projected only when something asks for it, so a long history costs what is
 * on screen.
 *
 * <p>Looking is a read and leaves no job record. {@link #cancel},
 * {@link #pause}, {@link #resume} and {@link #message} are actions through
 * Covia's Job tools, as the principal, and leave one each.
 */
public final class JobList {

	static final String OP_CANCEL = "v/ops/job/cancel";
	static final String OP_PAUSE = "v/ops/job/pause";
	static final String OP_RESUME = "v/ops/job/resume";
	static final String OP_MESSAGE = "v/ops/job/message";
	private static final long TIMEOUT_SECONDS = 30;
	private static final AString K_OWNER = Strings.intern("owner");
	private static final AString K_PARENT = Strings.intern("parent");
	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_TOKENS = Strings.intern("tokens");
	private static final AString K_TOTAL = Strings.intern("total");

	private JobList() {
	}

	/** How a job stands, grouped as the owner thinks of it. */
	public enum Group {
		/** Queued, running or paused. */
		ACTIVE("Active", "PENDING", "STARTED", "PAUSED"),
		/** Asked the owner something. */
		WAITING("Waiting on you", "INPUT_REQUIRED", "AUTH_REQUIRED"),
		FAILED("Failed", "FAILED", "TIMEOUT", "REJECTED"),
		COMPLETED("Completed", "COMPLETE"),
		CANCELLED("Cancelled", "CANCELLED");

		public final String label;
		private final Set<String> statuses;

		Group(String label, String... statuses) {
			this.label = label;
			this.statuses = Set.of(statuses);
		}

		/** The group of {@code status}, or null for one Covia adds later. */
		public static Group of(String status) {
			if (status == null) return null;
			for (Group g : values()) if (g.statuses.contains(status)) return g;
			return null;
		}
	}

	/**
	 * Who started a job, read from its {@code caller}. A scheduled run counts as
	 * whoever scheduled it: its record does not say a schedule started it
	 * (covia-ai/covia#701).
	 */
	public enum Starter {
		YOU("You"),
		/** One of the principal's agents ({@code <owner>:g:<agent>}). */
		AGENTS("Your agents"),
		/** Anyone else acting for the principal: a god, a delegate, another user with a grant. */
		OTHERS("Others");

		public final String label;

		Starter(String label) {
			this.label = label;
		}

		static Starter of(String caller, String owner) {
			if (caller == null || caller.equals(owner)) return YOU;
			return caller.startsWith(owner + ":g:") ? AGENTS : OTHERS;
		}
	}

	/** How far back to look. */
	public enum Range {
		TODAY("Today"), WEEK("This week"), ALL("All time");

		public final String label;

		Range(String label) {
			this.label = label;
		}

		/** The earliest creation time this range admits, 0 for no bound; {@code midnight} starts today. */
		long since(long now, long midnight) {
			return switch (this) {
				case TODAY -> midnight;
				case WEEK -> now - TimeUnit.DAYS.toMillis(7);
				case ALL -> 0;
			};
		}
	}

	/** What to show: the groups (empty for all), who started them (null for anyone), and the time range. */
	public record Filter(Set<Group> groups, Starter starter, Range range) {
		public static final Filter ALL = new Filter(EnumSet.noneOf(Group.class), null, Range.ALL);

		public Filter {
			groups = (groups == null || groups.isEmpty()) ? EnumSet.noneOf(Group.class) : EnumSet.copyOf(groups);
		}

		/** The jobs in {@code group}, or in every group when it is null — one status at a time, as the Jobs page offers. */
		public static Filter of(Group group, Starter starter, Range range) {
			return new Filter((group == null) ? null : EnumSet.of(group), starter, range);
		}

		boolean readsRecords() {
			return !groups.isEmpty() || starter != null;
		}

		boolean admits(AMap<AString, ACell> record, String owner) {
			if (!groups.isEmpty() && !groups.contains(Group.of(str(record.get(Fields.STATUS))))) return false;
			return starter == null || starter == Starter.of(str(record.get(Fields.CALLER)), owner);
		}
	}

	/**
	 * One job, summarised for a row: the record and what is read from it.
	 *
	 * @param id        the job id, in hex
	 * @param created   when it was submitted, epoch millis
	 * @param updated   when its record last changed, epoch millis (0 if never)
	 * @param name      the operation's display name, else its reference
	 * @param operation the operation it invoked, as invoked
	 * @param status    its Covia status
	 * @param purpose   what it is for in words — a description, an agent's task or message — or null
	 * @param starter   who started it
	 * @param caller    the principal that started it
	 * @param agent     the agent that ran it or asked for it, or null
	 * @param session   the agent conversation it worked in, or null
	 * @param error     why it failed, or null
	 * @param parent    the job it ran inside, in hex, or null for a top-level job
	 * @param tokens    the model tokens it used, 0 when none were measured
	 * @param record    the whole record, for the detail view
	 */
	public record Job(String id, long created, long updated, String name, String operation, String status,
			String purpose, Starter starter, String caller, String agent, String session, String error,
			String parent, long tokens, AMap<AString, ACell> record) {

		public Group group() {
			return Group.of(status);
		}

		/** Its title on screen: what it is for when it says, else its operation's name. */
		public String title() {
			if (purpose == null) return name;
			int nl = purpose.indexOf('\n');
			return (nl < 0) ? purpose : purpose.substring(0, nl);
		}

		/** Whether it is still going: queued, running or paused. */
		public boolean active() {
			return group() == Group.ACTIVE;
		}

		/** Whether it has finished one way or another, so nothing more will happen to it. */
		public boolean finished() {
			Group g = group();
			return g == Group.FAILED || g == Group.COMPLETED || g == Group.CANCELLED;
		}

		public boolean canPause() {
			return "STARTED".equals(status) || "PENDING".equals(status);
		}

		public boolean canResume() {
			return "PAUSED".equals(status);
		}

		public boolean canCancel() {
			return !finished();
		}

		/** Whether it can be sent a message: it is still going or waiting for an answer. */
		public boolean canMessage() {
			return !finished();
		}

		/** How long it ran, or has run so far at {@code now}. */
		public long elapsed(long now) {
			long end = finished() && updated > 0 ? updated : now;
			return Math.max(0, end - created);
		}
	}

	/**
	 * The rows a {@link Filter} selects from one snapshot of the index, newest
	 * first. Rows are positions in the index: the whole time range counted back
	 * from the end when no record needs reading, else the matching positions,
	 * found by reading only each record's status and caller. Immutable.
	 */
	public static final class View {
		private final Index<Blob, ACell> index;
		private final String owner;
		private final long newest;
		private final long count;
		private final long[] positions;

		private View(Index<Blob, ACell> index, String owner, long newest, long count, long[] positions) {
			this.index = index;
			this.owner = owner;
			this.newest = newest;
			this.count = count;
			this.positions = positions;
		}

		/** How many jobs it shows. */
		public int size() {
			return (int) Math.min(Integer.MAX_VALUE, count);
		}

		/** How many jobs the principal has in all, whatever the filter. */
		public long total() {
			return index.count();
		}

		/** The job on row {@code row}, newest first, projected now. */
		public Job job(int row) {
			if (row < 0 || row >= count) throw new IndexOutOfBoundsException(row);
			long position = (positions != null) ? positions[row] : newest - row;
			MapEntry<Blob, ACell> entry = index.entryAt(position);
			return project(entry.getKey(), RT.ensureMap(entry.getValue()), owner);
		}

		/** The row of job {@code id} (hex), or -1 when this view does not show it. */
		public int rowOf(String id) {
			Blob key = Blob.fromHex(id.startsWith("0x") ? id.substring(2) : id);
			if (key == null) return -1;
			long position = index.seek(key);
			if (position >= index.count() || !key.equals(index.entryAt(position).getKey())) return -1;
			if (positions == null) {
				long row = newest - position;
				return (row >= 0 && row < count) ? (int) row : -1;
			}
			// Positions run newest first, so search them in reverse order.
			int lo = 0, hi = positions.length - 1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (positions[mid] == position) return mid;
				if (positions[mid] > position) lo = mid + 1;
				else hi = mid - 1;
			}
			return -1;
		}
	}

	/**
	 * The rows {@code filter} selects from {@code index}, a snapshot of
	 * {@code owner}'s jobs. {@code now} and {@code midnight} (the start of today)
	 * fix the time range.
	 */
	public static View view(Index<Blob, ACell> index, String owner, Filter filter, long now, long midnight) {
		long end = index.count();
		long since = filter.range().since(now, midnight);
		long start = (since > 0) ? index.seek(idAt(since)) : 0;
		if (!filter.readsRecords()) return new View(index, owner, end - 1, end - start, null);
		long[] matched = new long[(int) Math.min(Integer.MAX_VALUE, end - start)];
		int n = 0;
		for (long p = end - 1; p >= start; p--) {
			if (filter.admits(RT.ensureMap(index.entryAt(p).getValue()), owner)) matched[n++] = p;
		}
		return new View(index, owner, end - 1, n, Arrays.copyOf(matched, n));
	}

	/**
	 * The lowest job id Covia can give a job submitted at {@code millis}: its
	 * 48-bit creation time followed by zeros (covia-ai/covia#700).
	 */
	public static Blob idAt(long millis) {
		byte[] key = new byte[8];
		long prefix = millis << 16;
		for (int i = 7; i >= 0; i--) {
			key[i] = (byte) prefix;
			prefix >>>= 8;
		}
		return Blob.wrap(key);
	}

	/** Job {@code id} (hex) in {@code index}, {@code owner}'s jobs, summarised; null when it is not there. */
	public static Job find(Index<Blob, ACell> index, String owner, String id) {
		if (index == null || id == null) return null;
		Blob key = Blob.fromHex(id.startsWith("0x") ? id.substring(2) : id);
		MapEntry<Blob, ACell> entry = (key != null) ? index.getEntry(key) : null;
		return (entry == null) ? null : project(entry.getKey(), RT.ensureMap(entry.getValue()), owner);
	}

	/** A row's summary of one record, job {@code id} of {@code owner}'s. */
	public static Job project(Blob id, AMap<AString, ACell> record, String owner) {
		if (record == null) record = Maps.empty();
		String caller = str(record.get(Fields.CALLER));
		String operation = str(record.get(Fields.OP));
		String name = str(record.get(Fields.NAME));
		if (name == null || name.isBlank()) name = (operation != null) ? lastSegment(operation) : "Job";
		String agent = str(record.get(Fields.AGENT_ID));
		Starter starter = Starter.of(caller, str(record.get(K_OWNER)) != null ? str(record.get(K_OWNER)) : owner);
		if (agent == null && starter == Starter.AGENTS) agent = caller.substring(caller.lastIndexOf(":g:") + 3);
		String session = Ids.text(record.get(Fields.SESSION_ID));
		ACell parent = record.get(K_PARENT);
		return new Job(id.toHexString(), num(record.get(Fields.CREATED)), num(record.get(Fields.UPDATED)), name,
			operation, str(record.get(Fields.STATUS)), purpose(record), starter, caller, agent, session,
			str(record.get(Fields.ERROR)), Ids.text(parent),
			num(RT.getIn(record, K_TOKENS, K_TOTAL)), record);
	}

	/** What a job is for: its {@code description}, else the task or message it handed an agent. */
	static String purpose(AMap<AString, ACell> record) {
		String description = str(record.get(K_DESCRIPTION));
		if (description != null && !description.isBlank()) return description.strip();
		return Schedule.purpose(Maps.of(Fields.INPUT, record.get(Fields.INPUT)));
	}

	/** Cancels job {@code id} as {@code client}'s principal; an existing outcome stands. */
	public static void cancel(Venue client, String id) throws Exception {
		control(client, OP_CANCEL, Maps.of(Fields.ID, id));
	}

	/** Pauses job {@code id}, where the work supports pausing. */
	public static void pause(Venue client, String id) throws Exception {
		control(client, OP_PAUSE, Maps.of(Fields.ID, id));
	}

	/** Resumes a paused job {@code id}. */
	public static void resume(Venue client, String id) throws Exception {
		control(client, OP_RESUME, Maps.of(Fields.ID, id));
	}

	/** Delivers {@code message} to job {@code id}: input it asked for, or a word for the agent running it. */
	public static void message(Venue client, String id, String message) throws Exception {
		control(client, OP_MESSAGE, Maps.of(Fields.ID, id, Fields.MESSAGE, message));
	}

	private static void control(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static String lastSegment(String op) {
		int slash = op.lastIndexOf('/');
		return (slash >= 0 && slash < op.length() - 1) ? op.substring(slash + 1) : op;
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	private static long num(ACell c) {
		return (c instanceof CVMLong l) ? l.longValue() : 0;
	}
}
