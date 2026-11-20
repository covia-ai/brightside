package brightside.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import brightside.JobList;
import brightside.ui.LAF;
import convex.core.data.Blob;
import convex.core.data.Maps;
import covia.api.Fields;

/**
 * The Jobs list: its own title only where no screen header says Jobs, and
 * bar buttons that are unavailable, and say why, until a job that can take
 * them is selected. Headless.
 */
class JobsBarTest {

	private static JobsScreen screen() throws Exception {
		LAF.init(LAF.TAU_DARK, null, null, null);
		JobsScreen[] s = new JobsScreen[1];
		SwingUtilities.invokeAndWait(() -> s[0] = new JobsScreen(new JobsScreen.Host() {
			@Override
			public void control(JobList.Job job, JobsScreen.Control control, String message, JobsScreen.Result done) {
			}

			@Override
			public void openConversation(String agentId, String sessionId) {
			}
		}));
		return s[0];
	}

	@Test
	void theTitleShowsOnceWhereverTheListSits() throws Exception {
		JobsScreen s = screen();
		assertTrue(s.ownHeading(), "in Settings the list names itself");
		SwingUtilities.invokeAndWait(() -> s.setOwnHeading(false));
		assertFalse(s.ownHeading(), "on the Jobs screen its header already does");
		SwingUtilities.invokeAndWait(() -> s.setOwnHeading(true));
		assertTrue(s.ownHeading());
	}

	@Test
	void withNothingSelectedTheBarIsUnavailableAndSaysWhy() throws Exception {
		JobsScreen s = screen();
		for (String text : new String[] { "Details", "Pause", "Message…", "Cancel…" }) {
			JButton b = button(s, text);
			assertNotNull(b, text);
			assertFalse(b.isEnabled(), text + " is unavailable");
			assertEquals("Select a job first", b.getToolTipText(), text);
			assertNotNull(b.getDisabledIcon(), text + " has a faded icon");
		}
	}

	@Test
	void eachButtonSaysWhyAJobCannotTakeIt() {
		JobList.Job done = JobList.project(Blob.fromHex("02"), Maps.of(Fields.STATUS, "COMPLETE"), "did:key:z6Mko:u:sam");
		JobList.Job running = JobList.project(Blob.fromHex("03"), Maps.of(Fields.STATUS, "STARTED"), "did:key:z6Mko:u:sam");
		assertEquals("It has already finished", JobsScreen.why(done, JobsScreen.Control.CANCEL));
		assertEquals("Only a running or queued job can be paused", JobsScreen.why(done, JobsScreen.Control.PAUSE));
		assertNull(JobsScreen.why(running, JobsScreen.Control.PAUSE));
		assertNull(JobsScreen.why(running, JobsScreen.Control.CANCEL));
	}

	private static JButton button(Container c, String text) {
		for (Component k : c.getComponents()) {
			if (k instanceof JButton b && text.equals(b.getText())) return b;
			if (k instanceof Container kc) {
				JButton f = button(kc, text);
				if (f != null) return f;
			}
		}
		return null;
	}
}
