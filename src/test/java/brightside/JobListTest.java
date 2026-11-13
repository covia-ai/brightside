package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.JobList.Filter;
import brightside.JobList.Group;
import brightside.JobList.Range;
import brightside.JobList.Starter;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The Jobs page's model on a real venue: one user's job index, read from the
 * lattice without a job, viewed newest first through status, starter and time
 * filters, with the time range found by seeking the id-ordered index, and
 * controlled through Covia's Job tools.
 */
class JobListTest {

	private static final long TIMEOUT_SECONDS = 30;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;
	private static long before;
	private static String completed, failed, agentJob;
	private static Job running;

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		userDID = Identity.of("jobs-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
		new ChatSession(user, new AppConfig.Chat("jobs-agent", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo the user.")).ensureAgent();

		before = System.currentTimeMillis();
		completed = finish(user.invoke("v/test/ops/echo", Maps.of("message", "hello")));
		Job error = user.invoke("v/test/ops/error", Maps.of("message", "it broke")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThrows(ExecutionException.class, () -> error.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		failed = error.getID().toHexString();
		Job byAgent = venue.engine().jobs().invokeOperation("v/test/ops/echo", Maps.of("message", "from the agent"),
			venue.engine().context(Strings.create(userDID), Strings.create(userDID + ":g:jobs-agent")));
		byAgent.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		agentJob = byAgent.getID().toHexString();
		running = user.invoke("v/test/ops/never", Maps.empty()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	@Test
	void readingTheJobsLeavesNoJob() {
		long recorded = RecordedJobs.of(venue, userDID);
		Index<Blob, ACell> index = jobs();
		JobList.View all = JobList.view(index, userDID, Filter.ALL, System.currentTimeMillis(), 0);
		for (int row = 0; row < all.size(); row++) assertNotNull(all.job(row));
		assertEquals(recorded, RecordedJobs.of(venue, userDID), "looking leaves no job record");
	}

	@Test
	void allJobsShowNewestFirst() {
		JobList.View all = view(Filter.ALL);
		assertEquals(all.total(), all.size(), "no filter shows every job");
		assertTrue(all.size() >= 4);
		for (int row = 1; row < all.size(); row++) {
			assertTrue(all.job(row - 1).id().compareTo(all.job(row).id()) > 0, "newest first");
		}
		assertEquals(running.getID().toHexString(), all.job(0).id(), "the last one started is on top");
	}

	@Test
	void statusGroupsSelectTheirJobs() {
		JobList.View failures = view(new Filter(EnumSet.of(Group.FAILED), null, Range.ALL));
		int row = failures.rowOf(failed);
		assertTrue(row >= 0, "the failed job is there");
		assertNotNull(failures.job(row).error(), "with the reason");
		assertEquals(-1, failures.rowOf(completed), "a completed one is not");
		for (int r = 0; r < failures.size(); r++) assertEquals(Group.FAILED, failures.job(r).group());

		JobList.View active = view(new Filter(EnumSet.of(Group.ACTIVE), null, Range.ALL));
		assertTrue(active.rowOf(running.getID().toHexString()) >= 0, "the job still running is active");
		assertEquals(-1, active.rowOf(completed), "a finished one is not");

		JobList.View either = view(new Filter(EnumSet.of(Group.FAILED, Group.COMPLETED), null, Range.ALL));
		assertTrue(either.rowOf(failed) >= 0 && either.rowOf(completed) >= 0, "groups combine");
	}

	@Test
	void whoStartedItIsReadFromTheCaller() {
		JobList.View agents = view(new Filter(null, Starter.AGENTS, Range.ALL));
		assertEquals(1, agents.size());
		assertEquals(agentJob, agents.job(0).id());
		assertEquals("jobs-agent", agents.job(0).agent());
		JobList.View mine = view(new Filter(null, Starter.YOU, Range.ALL));
		assertEquals(-1, mine.rowOf(agentJob));
		assertTrue(mine.rowOf(completed) >= 0);
	}

	@Test
	void aTimeRangeSeeksTheIdOrderedIndex() {
		long now = System.currentTimeMillis();
		Index<Blob, ACell> index = jobs();
		assertEquals(0, JobList.view(index, userDID, new Filter(null, null, Range.TODAY), now, now + 60_000).size(),
			"nothing was started after a time still to come");
		JobList.View today = JobList.view(index, userDID, new Filter(null, null, Range.TODAY), now, before);
		assertTrue(today.rowOf(completed) >= 0 && today.rowOf(running.getID().toHexString()) >= 0,
			"everything started since then");
		assertTrue(JobList.idAt(before).compareTo(Blob.fromHex(completed)) <= 0, "an id is at or after its time's bound");
	}

	@Test
	void aRunningJobIsCancelled() throws Exception {
		String id = running.getID().toHexString();
		JobList.Job job = view(Filter.ALL).job(view(Filter.ALL).rowOf(id));
		assertTrue(job.canCancel() && job.active() && !job.finished());
		JobList.cancel(user, id);
		assertThrows(Exception.class, () -> running.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"the cancelled job ends");
		JobList.Job after = view(Filter.ALL).job(view(Filter.ALL).rowOf(id));
		assertEquals(Group.CANCELLED, after.group(), String.valueOf(after.status()));
		assertFalse(after.canCancel(), "and can't be cancelled again");
	}

	private static JobList.View view(Filter filter) {
		return JobList.view(jobs(), userDID, filter, System.currentTimeMillis(), 0);
	}

	private static String finish(java.util.concurrent.CompletableFuture<Job> submitted) throws Exception {
		Job job = submitted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.getID().toHexString();
	}

	/** The principal's job index, from their Jobs lattice component. */
	private static Index<Blob, ACell> jobs() {
		return venue.jobs(userDID).getAll();
	}
}
