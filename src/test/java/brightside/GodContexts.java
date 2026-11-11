package brightside;

import convex.core.data.AString;
import convex.core.data.Strings;
import covia.venue.RequestContext;

/**
 * The context a god's run loop gives its tool calls: the venue's own context
 * for the god's sub-principal, which reads the god's {@code config.caps} at
 * each point of action. Tests acting as a god use it, so what they show is
 * what the god's caps allow.
 */
final class GodContexts {

	private GodContexts() {
	}

	/** As god {@code godId} of {@code venue}, which must already exist. */
	static RequestContext of(EmbeddedVenue venue, String godId) {
		AString venueDID = Strings.create(venue.did());
		return venue.engine().context(venueDID, Strings.create(venue.did() + ":g:" + godId));
	}
}
