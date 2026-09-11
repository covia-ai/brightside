package brightside;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** Console filtering for known test diagnostics; logger levels remain unchanged. */
public final class ExpectedLogs extends Filter<ILoggingEvent> {

	private static final CopyOnWriteArrayList<Predicate<ILoggingEvent>> EXPECTED = new CopyOnWriteArrayList<>();
	// Most test venues deliberately use an unencrypted temporary store.
	private static final String UNENCRYPTED_VAULT = "Vault adapter is enabled for DLFS drive '{}' but this venue does "
		+ "not declare an encrypted Etch policy. Vault contents may be stored "
		+ "unencrypted; configure etch.cipher and key management for sensitive data.";

	public static Scope warn(Class<?> source, String messagePrefix) {
		return expect(source.getName(), Level.WARN, messagePrefix);
	}

	/** Includes worker threads; keep the scope open until the operation has finished. */
	public static Scope expect(String logger, Level level, String messagePrefix) {
		Predicate<ILoggingEvent> match = event -> logger.equals(event.getLoggerName())
			&& level.equals(event.getLevel()) && event.getFormattedMessage().startsWith(messagePrefix);
		EXPECTED.add(match);
		return new Scope(match);
	}

	public static final class Scope implements AutoCloseable {
		private final Predicate<ILoggingEvent> match;

		private Scope(Predicate<ILoggingEvent> match) {
			this.match = match;
		}

		@Override
		public void close() {
			EXPECTED.remove(match);
		}
	}

	@Override
	public FilterReply decide(ILoggingEvent event) {
		if (Level.WARN.equals(event.getLevel()) && "covia.adapter.VaultAdapter".equals(event.getLoggerName())
			&& UNENCRYPTED_VAULT.equals(event.getMessage())) return FilterReply.DENY;
		for (var match : EXPECTED) {
			if (match.test(event)) return FilterReply.DENY;
		}
		return FilterReply.NEUTRAL;
	}
}
