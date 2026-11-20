package brightside.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import brightside.JobList;
import brightside.ui.LAF;
import brightside.ui.components.StatusBadge;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;
import convex.core.data.Blob;
import convex.core.data.Maps;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/**
 * A job that timed out is a status of its own: never filed under Failed and
 * never shown in the error colour. Like Covia, it is not finished, so it can
 * be cancelled, but nothing resumes, retries, or messages it, and its clock
 * stops. Headless.
 */
class TimedOutStatusTest {

	private static JobList.Job job(String status) {
		return JobList.project(Blob.fromHex("01"), Maps.of(Fields.STATUS, status, Fields.CREATED, 1_000L,
			Fields.UPDATED, 61_000L), "did:key:z6Mkowner:u:sam");
	}

	@Test
	void itIsItsOwnGroupNotAFailure() {
		assertEquals(JobList.Group.TIMED_OUT, JobList.Group.of("TIMEOUT"));
		assertEquals("Timed out", JobList.Group.TIMED_OUT.label);
		assertEquals(JobList.Group.FAILED, JobList.Group.of("FAILED"));
		assertEquals(JobList.Group.FAILED, JobList.Group.of("REJECTED"));
	}

	@Test
	void whatCanBeDoneMatchesCovia() {
		assertFalse(Job.isFinished(Maps.of(Fields.STATUS, Status.TIMEOUT)), "Covia does not count it finished");
		JobList.Job t = job("TIMEOUT");
		assertTrue(t.timedOut());
		assertFalse(t.finished(), "nor does Tau");
		assertTrue(t.canCancel(), "so it can be cancelled");
		assertFalse(t.canResume(), "Covia resumes only a paused or waiting job");
		assertFalse(t.canPause());
		assertFalse(t.canMessage(), "it is not going");
		assertTrue(t.ended());
		assertEquals(60_000L, t.elapsed(10_000_000L), "its clock stops when it timed out");
	}

	@Test
	void itLooksCalmNeverLikeAnError() {
		for (String theme : new String[] { LAF.TAU_DARK, LAF.TAU_LIGHT }) {
			LAF.init(theme, null, null, null);
			JobList.Job t = job("TIMEOUT");
			assertEquals("Timed out", JobLooks.status(t));
			assertEquals("clock", JobLooks.icon(t));
			assertEquals(Tone.TIMED_OUT, JobLooks.tone(t));
			assertNotEquals(Theme.error(), Tone.TIMED_OUT.colour(), theme);
			assertNotEquals(Theme.error(), Tone.TIMED_OUT.text(), theme);
			StatusBadge b = StatusBadge.timedOut();
			assertEquals("Timed out", b.getText());
			assertEquals(Tone.ERROR, JobLooks.tone(job("FAILED")), "a failure keeps its colour");
		}
		LAF.init(LAF.TAU_DARK, null, null, null);
	}
}
