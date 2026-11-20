package brightside.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The composer's model chip says the model in plain words, never an operation id where a name is known. */
class ProvidersLabelTest {

	@Test
	void aListedModelShowsItsLabelAndWhoPays() {
		Providers.Provider p = Providers.defaultProvider();
		Providers.Model m = p.models().get(0);
		String op = Providers.modelOp(p.id(), m.id());
		assertEquals(m.label(), Providers.label(op));
		assertEquals(p.needsApiKey() ? "Your key" : "On this computer", Providers.marker(op));
	}

	@Test
	void theOfflineModelAndUnknownOnes() {
		assertEquals("Offline test model", Providers.label("v/test/ops/llm"));
		assertNull(Providers.marker("v/test/ops/llm"));
		assertEquals("v/ops/custom", Providers.label("v/ops/custom"));
		assertEquals("No model", Providers.label(null));
	}
}
