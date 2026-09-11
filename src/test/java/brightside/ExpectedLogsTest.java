package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;

class ExpectedLogsTest {

	private final ExpectedLogs filter = new ExpectedLogs();

	@Test
	void onlyMatchingEventsAreSuppressedAndClosingRestoresOutput() throws Exception {
		LoggingEvent expected = event("test.expected", Level.WARN, "deliberate failure: detail");
		assertEquals(FilterReply.NEUTRAL, filter.decide(expected));
		try (var logs = ExpectedLogs.expect("test.expected", Level.WARN, "deliberate failure:")) {
			assertEquals(FilterReply.DENY, CompletableFuture.supplyAsync(() -> filter.decide(expected)).get());
			assertEquals(FilterReply.NEUTRAL, filter.decide(event("test.other", Level.WARN, "deliberate failure:")));
			assertEquals(FilterReply.NEUTRAL, filter.decide(event("test.expected", Level.ERROR, "deliberate failure:")));
			assertEquals(FilterReply.NEUTRAL, filter.decide(event("test.expected", Level.WARN, "unexpected failure")));
		}
		assertEquals(FilterReply.NEUTRAL, filter.decide(expected));
	}

	@Test
	void overlappingScopesCloseIndependently() {
		LoggingEvent expected = event("test.overlap", Level.ERROR, "deliberate failure");
		try (var outer = ExpectedLogs.expect("test.overlap", Level.ERROR, "deliberate")) {
			try (var inner = ExpectedLogs.expect("test.overlap", Level.ERROR, "deliberate")) {
				assertEquals(FilterReply.DENY, filter.decide(expected));
			}
			assertEquals(FilterReply.DENY, filter.decide(expected));
		}
		assertEquals(FilterReply.NEUTRAL, filter.decide(expected));
	}

	private static LoggingEvent event(String logger, Level level, String message) {
		LoggingEvent event = new LoggingEvent();
		event.setLoggerName(logger);
		event.setLevel(level);
		event.setMessage(message);
		return event;
	}
}
