package brightside.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.lang.RT;

/**
 * The model providers Brightside offers at onboarding and in Settings, mirroring
 * the venue's {@code langchain} catalog: each provider maps to a
 * {@code v/models/<provider>/<id>} operation family, the secret name its API key
 * is stored under, and where to get a key. Every model listed here is one the
 * venue catalogues, so its path is an operation the venue resolves.
 *
 * <p>A provider with {@code anyModel} serves far more models than the catalog
 * lists — OpenRouter fronts every vendor, Ollama whatever the owner has pulled —
 * so its model is chosen by id. Brightside still names that choice
 * {@code v/models/<provider>/<id>}; {@link #route} turns it into what the agent
 * runs.</p>
 */
public final class Providers {

	/** One model choice: its {@code id} (path segment) and a friendly {@code label}. */
	public record Model(String id, String label) {
	}

	/**
	 * A provider: {@code id} (path segment), label, API-key secret name (null = none),
	 * console, models, and {@code anyModel}: whether it takes an id beyond those listed.
	 */
	public record Provider(String id, String label, String secretName, String consoleUrl, List<Model> models,
			boolean anyModel) {
		public boolean needsApiKey() {
			return secretName != null;
		}

		public String defaultModelId() {
			return models.isEmpty() ? null : models.get(0).id();
		}

		/** The listed model with this id, or null. */
		public Model model(String modelId) {
			for (Model m : models) {
				if (m.id().equals(modelId)) return m;
			}
			return null;
		}
	}

	/**
	 * What an agent runs for a model choice: its {@code llmOperation}, and the
	 * {@code model} it overrides that operation's default with — null when the
	 * operation names its model itself, which also clears an earlier override.
	 */
	public record Route(String operation, String model) {
	}

	/** All providers, in display order; the first model of each is its default. */
	public static final List<Provider> ALL = List.of(
		new Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "https://console.anthropic.com/settings/keys",
			List.of(new Model("claude-opus-5-5", "Claude Opus 5.5"),
				new Model("claude-sonnet-5-5", "Claude Sonnet 5.5"),
				new Model("claude-fable-5-1", "Claude Fable 5.1"),
				new Model("claude-haiku-5-5", "Claude Haiku 5.5"),
				// The previous line stays listed, as in Covia's catalogue, so an
				// install that chose one of these still sees it by name in Settings.
				new Model("claude-opus-5", "Claude Opus 5 (previous)"),
				new Model("claude-sonnet-5", "Claude Sonnet 5 (previous)"),
				new Model("claude-haiku-4-5-20251001", "Claude Haiku 4.5 (previous)")), false),
		new Provider("openai", "OpenAI", "OPENAI_API_KEY", "https://platform.openai.com/api-keys",
			List.of(new Model("gpt-5.6-terra", "GPT-5.6"),
				new Model("gpt-5.4-mini", "GPT-5.4 mini")), false),
		new Provider("xai", "Grok (xAI)", "XAI_API_KEY", "https://console.x.ai",
			List.of(new Model("grok-4.3", "Grok 4.3"),
				new Model("grok-4.5", "Grok 4.5")), false),
		new Provider("gemini", "Gemini (Google)", "GOOGLE_API_KEY", "https://aistudio.google.com/app/apikey",
			List.of(new Model("gemini-3.6-flash", "Gemini 3.6 Flash"),
				new Model("gemini-3.5-flash", "Gemini 3.5 Flash")), false),
		new Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://platform.deepseek.com/api_keys",
			List.of(new Model("deepseek-v4-flash", "DeepSeek V4 Flash"),
				new Model("deepseek-v4-pro", "DeepSeek V4 Pro")), false),
		new Provider("mistral", "Mistral", "MISTRAL_API_KEY", "https://console.mistral.ai/api-keys",
			List.of(new Model("mistral-medium-latest", "Mistral Medium"),
				new Model("mistral-large-latest", "Mistral Large")), false),
		// A pinned model first: the assistant lives on tool calls, and
		// openrouter/auto may route a turn to a model without them.
		new Provider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai/keys",
			List.of(new Model("anthropic/claude-sonnet-5", "Claude Sonnet 5"),
				new Model("openai/gpt-5.4-mini", "GPT-5.4 mini"),
				new Model("google/gemini-3.5-flash", "Gemini 3.5 Flash"),
				new Model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash"),
				new Model("mistralai/mistral-medium-latest", "Mistral Medium"),
				new Model("openrouter/auto", "Auto (OpenRouter picks)")), true),
		new Provider("ollama", "Ollama (local)", null, "https://ollama.com",
			List.of(new Model("qwen", "Qwen (local)")), true));

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

	/** The model id inside a {@code v/models/<provider>/<id>} path, or null. */
	public static String modelOf(String modelOp) {
		String provider = providerOf(modelOp);
		return (provider != null) ? modelOp.substring(("v/models/" + provider + "/").length()) : null;
	}

	/**
	 * What the agent runs for {@code modelOp}. A listed model, or any operation
	 * that is not a model path, runs as it is. An id the venue does not
	 * catalogue, for a provider that takes {@code anyModel}, runs through the
	 * provider's own operation with that id as the agent's model.
	 */
	public static Route route(String modelOp) {
		Provider p = byId(providerOf(modelOp));
		String modelId = modelOf(modelOp);
		if (p == null || !p.anyModel() || modelId.isEmpty() || p.model(modelId) != null) {
			return new Route(modelOp, null);
		}
		return new Route("v/ops/langchain/" + p.id(), modelId);
	}

	/**
	 * The models the venue's {@code langchain:models} {@code report} says each
	 * provider serves right now beyond its catalog — Ollama's installed models —
	 * by provider id, in the report's order. Only providers that take
	 * {@code anyModel} are included, since only their ids {@link #route} can run.
	 */
	public static Map<String, List<String>> served(ACell report) {
		Map<String, List<String>> served = new LinkedHashMap<>();
		if (!(RT.getIn(report, "providers") instanceof AVector<?> providers)) return served;
		for (long i = 0; i < providers.count(); i++) {
			ACell entry = (ACell) providers.get(i);
			Provider p = byId(String.valueOf(RT.getIn(entry, "provider")));
			if (p == null || !p.anyModel() || !(RT.getIn(entry, "installedModels") instanceof AVector<?> ids)) continue;
			List<String> list = new ArrayList<>();
			for (long j = 0; j < ids.count(); j++) {
				AString id = RT.ensureString((ACell) ids.get(j));
				if (id != null) list.add(id.toString());
			}
			served.put(p.id(), List.copyOf(list));
		}
		return served;
	}

	/** The default provider (Anthropic). */
	public static Provider defaultProvider() {
		return ALL.get(0);
	}
}
