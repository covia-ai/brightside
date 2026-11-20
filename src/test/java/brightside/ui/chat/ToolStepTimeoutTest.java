package brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import brightside.SessionHistory;
import brightside.ui.LAF;

/**
 * A tool step reads as Covia reports it: a call given up at its time limit is
 * Timed out, and one still running as a job past its limit is Running, since
 * its outcome is still to come. Headless.
 */
class ToolStepTimeoutTest {

	private static String badge(String result, boolean error) {
		return ExpandableActivity.statusBadge(new SessionHistory.Step(true, "covia_read", result, error, "{}")).getText();
	}

	@Test
	void eachStepSaysWhatHappened() {
		LAF.init(LAF.TAU_DARK, null, null, null);
		assertEquals("Timed out", badge("Error: tool call timed out after 30000ms", true));
		assertEquals("Running", badge("Error: v/ops/http/get did not complete within 30000ms and is still running as job "
			+ "0x01ab. Its outcome will arrive later in this session.", true));
		assertEquals("Failed", badge("Error: no such path", true));
		assertEquals("Done", badge("{\"ok\":true}", false));
	}
}
