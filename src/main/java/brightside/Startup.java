package brightside;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.Engine;

/**
 * Brightside's startup pass. It runs in Covia's launch hook once Brightside's
 * adapters are registered: Covia has migrated the store, and no recovered job,
 * schedule or agent has run yet, so what it puts right holds before anything
 * acts. Three steps, each safe to run on every start:
 * <ol>
 * <li><b>Migrate</b>: compare the versions the store last started under with
 * this build's. Brightside's own version-gated steps, which follow Covia's
 * migration, belong here; there are none yet.</li>
 * <li><b>Repair</b>: bring the gods up to date with the chosen model, their
 * caps included, and resume one stopped by a fault ({@link God#ensure}).</li>
 * <li><b>Verify</b>: check that each god is there, configured as it should be,
 * and not stopped by a fault, and report any that is not.</li>
 * </ol>
 * Before a model is chosen there are no gods to keep: they are made on the
 * first message. Nothing here fails the launch; a problem is logged and
 * reported for the owner. The versions are recorded last, so a start that
 * stops before then counts as an upgrade again. See {@code docs/LAUNCH.md}.
 */
public final class Startup {

	private static final Logger log = LoggerFactory.getLogger(Startup.class);

	private static final AString K_BRIGHTSIDE = Strings.intern("brightside");
	private static final AString K_COVIA = Strings.intern("covia");

	private Startup() {
	}

	/**
	 * What a start found.
	 *
	 * @param from     the Brightside and Covia versions the store last started under, or null if never recorded
	 * @param to       this start's versions
	 * @param problems what verification found, in words for the owner; empty when all is well
	 */
	public record Report(AMap<AString, ACell> from, AMap<AString, ACell> to, List<String> problems) {
		/** Whether this build differs from the one the store last started under. */
		public boolean upgraded() {
			return from != null && !from.equals(to);
		}
	}

	/** Runs the pass on {@code engine}, whose Brightside adapter is {@code brightside}. */
	static Report run(Engine engine, BrightsideAdapter brightside) {
		AMap<AString, ACell> to = Maps.of(K_BRIGHTSIDE, BrightsideAdapter.version(), K_COVIA, Engine.jarVersion());
		AMap<AString, ACell> from = brightside.startupVersions();
		migrate(from, to);
		String model = brightside.model();
		if (model != null) Gods.ensureAll(engine, model);
		List<String> problems = verify(engine, model);
		brightside.saveStartupVersions(to);
		return new Report(from, to, problems);
	}

	private static void migrate(AMap<AString, ACell> from, AMap<AString, ACell> to) {
		if (from != null && !from.equals(to)) log.info("Upgraded from {} to {}", from, to);
	}

	private static List<String> verify(Engine engine, String model) {
		if (model == null) return List.of();
		String venueDID = engine.getDIDString().toString();
		List<String> problems = new ArrayList<>();
		for (God god : Gods.ALL) {
			ACell record = EmbeddedVenue.agentRecord(engine, venueDID, god.id());
			if (record == null) {
				problems.add(god.id() + " couldn't be set up. Details are in the logs.");
			} else if (!God.configured(record, god.config(model))) {
				problems.add(god.id() + "'s settings couldn't be brought up to date, so he may hold more or less"
					+ " than he should. Details are in the logs.");
			} else if (Suspension.byFault(record)) {
				problems.add(god.id() + " is stopped by a fault and couldn't be resumed. Details are in the logs.");
			}
		}
		for (String problem : problems) log.warn("Startup check: {}", problem);
		return problems;
	}
}
