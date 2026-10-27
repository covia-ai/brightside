package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The chosen model is kept in Brightside's adapter state in the venue store,
 * one for the installation, outside every person's namespace.
 */
class ChosenModelTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void theChosenModelIsKeptInTheAdaptersState() {
		EmbeddedVenue venue = shared.venue();
		BrightsideAdapter app = venue.brightside();
		app.saveModel("v/models/anthropic/claude-opus-5");
		assertEquals("v/models/anthropic/claude-opus-5", app.model());

		covia.venue.AdapterState state = venue.engine().adapterState("brightside");
		assertEquals(convex.core.data.Strings.create("v/models/anthropic/claude-opus-5"),
			state.read(BrightsideAdapter.MODEL));
	}
}
