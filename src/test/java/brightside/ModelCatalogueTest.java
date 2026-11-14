package brightside;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.model.Providers;

/** The model pickers' catalogue, read from a real venue's {@code v/models}. */
class ModelCatalogueTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void everyListedModelOfACatalogueOnlyProviderIsCatalogued() {
		EmbeddedVenue v = shared.venue();
		Map<String, List<String>> catalogued =
			Providers.catalogued(v.resolve(v.did() + ":u:catalogue-reader", "v/models"));
		assertFalse(catalogued.isEmpty(), "the venue catalogues models");
		for (Providers.Provider p : Providers.ALL) {
			if (p.anyModel()) continue;
			List<String> ids = catalogued.getOrDefault(p.id(), List.of());
			for (Providers.Model m : p.models()) {
				assertTrue(ids.contains(m.id()), p.id() + "/" + m.id() + " is offered but not catalogued");
			}
		}
	}
}
