package brightside.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import brightside.model.Providers;

class ProvidersTest {

	@Test
	void mapsModelOpsAndProviders() {
		assertEquals("v/models/anthropic/claude-sonnet-5-5",
			Providers.modelOp("anthropic", "claude-sonnet-5-5"));
		assertEquals("anthropic", Providers.providerOf("v/models/anthropic/claude-sonnet-5-5"));
		assertEquals("openrouter", Providers.providerOf("v/models/openrouter/anthropic/claude-sonnet-5"));
		assertEquals("anthropic/claude-sonnet-5", Providers.modelOf("v/models/openrouter/anthropic/claude-sonnet-5"));
		assertNull(Providers.providerOf("not a model path"));
		assertNull(Providers.modelOf("not a model path"));
	}

	@Test
	void routesAModelTheCatalogDoesNotListThroughItsProvider() {
		assertEquals(new Providers.Route("v/models/openrouter/anthropic/claude-sonnet-5", null),
			Providers.route("v/models/openrouter/anthropic/claude-sonnet-5"), "a listed model runs as its preset");
		assertEquals(new Providers.Route("v/ops/langchain/openrouter", "google/gemini-3.6-flash"),
			Providers.route("v/models/openrouter/google/gemini-3.6-flash"));
		assertEquals(new Providers.Route("v/ops/langchain/ollama", "llama3.2:3b"),
			Providers.route("v/models/ollama/llama3.2:3b"));
		assertEquals(new Providers.Route("v/models/anthropic/claude-new", null),
			Providers.route("v/models/anthropic/claude-new"), "a provider without anyModel is never rerouted");
		assertEquals(new Providers.Route("v/test/ops/llm", null), Providers.route("v/test/ops/llm"));
	}

	@Test
	void openRouterDefaultsToAPinnedModel() {
		Providers.Provider openrouter = Providers.byId("openrouter");
		assertTrue(openrouter.anyModel());
		assertNotEquals("openrouter/auto", openrouter.defaultModelId(), "auto may route a turn to a model without tools");
		assertNotNull(openrouter.model("openrouter/auto"), "auto stays a choice");
	}

	@Test
	void carriesSecretNamesAndDefaults() {
		Providers.Provider anthropic = Providers.byId("anthropic");
		assertEquals("ANTHROPIC_API_KEY", anthropic.secretName());
		assertTrue(anthropic.needsApiKey());
		assertEquals("claude-opus-5-5", anthropic.defaultModelId(), "Opus 5.5 is the default (strong tool use)");

		Providers.Provider ollama = Providers.byId("ollama");
		assertFalse(ollama.needsApiKey(), "local provider needs no key");

		assertEquals("GOOGLE_API_KEY", Providers.byId("gemini").secretName(), "Gemini uses GOOGLE_API_KEY");
		assertEquals("XAI_API_KEY", Providers.byId("xai").secretName());
		assertEquals("anthropic", Providers.defaultProvider().id());
	}
}
