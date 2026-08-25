package covia.brightside.model;

import java.util.List;

/**
 * The model providers Brightside offers at onboarding and in Settings, mirroring
 * the venue's {@code langchain} catalog: each provider maps to a
 * {@code v/models/<provider>/<id>} operation family, the secret name its API key
 * is stored under, and where to get a key. The live per-provider model list comes
 * from {@code langchain:models}; this static table is the fallback and the source
 * of the secret name + console URL.
 */
public final class Providers {

	/** One model choice: its {@code id} (path segment) and a friendly {@code label}. */
	public record Model(String id, String label) {
	}

	/** A provider: {@code id} (path segment), label, API-key secret name (null = none), console, models. */
	public record Provider(String id, String label, String secretName, String consoleUrl, List<Model> models) {
		public boolean needsApiKey() {
			return secretName != null;
		}

		public String defaultModelId() {
			return models.isEmpty() ? null : models.get(0).id();
		}
	}

	/** All providers, in display order; the first model of each is its default. */
	public static final List<Provider> ALL = List.of(
		new Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "https://console.anthropic.com/settings/keys",
			List.of(new Model("claude-sonnet-5", "Claude Sonnet"),
				new Model("claude-opus-5", "Claude Opus"),
				new Model("claude-haiku-4-5-20251001", "Claude Haiku"))),
		new Provider("openai", "OpenAI", "OPENAI_API_KEY", "https://platform.openai.com/api-keys",
			List.of(new Model("gpt-5.6-terra", "GPT-5.6"),
				new Model("gpt-5.4-mini", "GPT-5.4 mini"))),
		new Provider("xai", "Grok (xAI)", "XAI_API_KEY", "https://console.x.ai",
			List.of(new Model("grok-4.3", "Grok 4.3"),
				new Model("grok-4.5", "Grok 4.5"))),
		new Provider("gemini", "Gemini (Google)", "GOOGLE_API_KEY", "https://aistudio.google.com/app/apikey",
			List.of(new Model("gemini-3.6-flash", "Gemini 3.6 Flash"),
				new Model("gemini-3.5-flash", "Gemini 3.5 Flash"))),
		new Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://platform.deepseek.com/api_keys",
			List.of(new Model("deepseek-v4-flash", "DeepSeek V4 Flash"),
				new Model("deepseek-v4-pro", "DeepSeek V4 Pro"))),
		new Provider("mistral", "Mistral", "MISTRAL_API_KEY", "https://console.mistral.ai/api-keys",
			List.of(new Model("mistral-medium-latest", "Mistral Medium"),
				new Model("mistral-large-latest", "Mistral Large"))),
		new Provider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai/keys",
			List.of(new Model("openrouter/auto", "Auto (OpenRouter picks)"),
				new Model("anthropic/claude-sonnet-5", "Claude Sonnet (via OpenRouter)"))),
		new Provider("ollama", "Ollama (local)", null, "https://ollama.com",
			List.of(new Model("qwen", "Qwen (local)"))));

	private Providers() {
	}

	/** The provider with this id, or null. */
	public static Provider byId(String id) {
		for (Provider p : ALL) {
			if (p.id().equals(id)) return p;
		}
		return null;
	}

	/** The model operation path {@code v/models/<provider>/<id>}. */
	public static String modelOp(String providerId, String modelId) {
		return "v/models/" + providerId + "/" + modelId;
	}

	/** The provider id inside a {@code v/models/<provider>/<id>} path, or null. */
	public static String providerOf(String modelOp) {
		if (modelOp == null || !modelOp.startsWith("v/models/")) return null;
		String rest = modelOp.substring("v/models/".length());
		int slash = rest.indexOf('/');
		return (slash > 0) ? rest.substring(0, slash) : null;
	}

	/** The default provider (Anthropic). */
	public static Provider defaultProvider() {
		return ALL.get(0);
	}
}
