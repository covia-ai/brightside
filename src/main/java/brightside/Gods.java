package brightside;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.venue.Engine;

/**
 * The gods: the venue's own agents, each specialised in one job on the
 * owner's behalf — {@link Odin}, the administrator, {@link Thor}, the
 * guardian, {@link Njord}, the treasurer, and {@link Eir}, the healer. Add one by giving it a
 * {@link God} definition, its two bridge resources under
 * {@code adapters/brightside/}, and a place in {@link #ALL}.
 */
public final class Gods {

	private static final Logger log = LoggerFactory.getLogger(Gods.class);

	public static final List<God> ALL = List.of(Odin.GOD, Thor.GOD, Njord.GOD, Eir.GOD);

	private Gods() {
	}

	/** The god with agent id {@code id}, or null. */
	public static God byId(String id) {
		for (God g : ALL) if (g.id().equals(id)) return g;
		return null;
	}

	/** The god whose ask or run bridge is {@code brightside:<subOperation>}, or null. */
	static God byBridge(String subOperation) {
		for (God g : ALL) if (g.ask().equals(subOperation) || g.run().equals(subOperation)) return g;
		return null;
	}

	/**
	 * Creates or brings up to date every god, each with {@code llmOperation},
	 * as the venue operator. Best-effort: one that fails is logged and the rest
	 * still go ahead.
	 */
	public static void ensureAll(EmbeddedVenue venue, String llmOperation) {
		ensureAll(venue.engine(), llmOperation);
	}

	/** As {@link #ensureAll(EmbeddedVenue, String)}, on {@code engine} — as the launch hook does ({@link Startup}). */
	static void ensureAll(Engine engine, String llmOperation) {
		for (God g : ALL) {
			try {
				g.ensure(engine, llmOperation);
			} catch (Exception e) {
				log.warn("{} is not available: {}", g.id(), e.toString());
			}
		}
	}
}
