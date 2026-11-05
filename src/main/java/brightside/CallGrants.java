package brightside;

import convex.core.data.ACell;
import convex.core.data.AVector;

/**
 * An adapter whose operations a god runs as itself, under a grant naming
 * exactly what one call touches: the run bridge asks the adapter what that is
 * and the venue signs it for that call alone ({@link God#grantedOperations}).
 * The treasury is one: a payment is granted spending on the budget it names
 * and nothing else.
 */
public interface CallGrants {

	/**
	 * The capabilities {@code operation} needs for {@code input}, each over an
	 * absolute resource ({@link VenueGrants#cap}).
	 *
	 * @throws IllegalArgumentException when a god may not be granted that call at all
	 */
	AVector<ACell> capabilitiesFor(String operation, ACell input);
}
