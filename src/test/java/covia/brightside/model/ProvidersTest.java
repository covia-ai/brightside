package covia.brightside.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProvidersTest {

	@Test
	void mapsModelOpsAndProviders() {
		assertEquals("v/models/anthropic/claude-sonnet-5",
			Providers.modelOp("anthropic", "claude-sonnet-5"));
		assertEquals("anthropic", Providers.providerOf("v/models/anthropic/claude-sonnet-5"));
		assertEquals("openrouter", Providers.providerOf("v/models/openrouter/anthropic/claude-sonnet-5"));
		assertNull(Providers.providerOf("not a model path"));
	}

	@Test
	void carriesSecretNamesAndDefaults() {
		Providers.Provider anthropic = Providers.byId("anthropic");
		assertEquals("ANTHROPIC_API_KEY", anthropic.secretName());
		assertTrue(anthropic.needsApiKey());
		assertEquals("claude-opus-5", anthropic.defaultModelId(), "Opus 5 is the default (strong tool use)");

		Providers.Provider ollama = Providers.byId("ollama");
		assertFalse(ollama.needsApiKey(), "local provider needs no key");

		assertEquals("GOOGLE_API_KEY", Providers.byId("gemini").secretName(), "Gemini uses GOOGLE_API_KEY");
		assertEquals("XAI_API_KEY", Providers.byId("xai").secretName());
		assertEquals("anthropic", Providers.defaultProvider().id());
	}
}
