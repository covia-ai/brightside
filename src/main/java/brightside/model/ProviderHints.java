package brightside.model;

import java.util.Locale;

/**
 * What is probably wrong with a model key, from what each provider's keys and
 * errors look like: a key pasted with a stray space or "Bearer ", a key from a
 * different provider, an admin or subscription token that can't call models,
 * and, once a test has failed, where on that provider's site to look next.
 *
 * <p>Everything here is a suggestion shown beside the {@code Test} answer,
 * never a gate: providers change their key formats, so a key that looks
 * unusual is still sent and the provider's own reply decides. Nothing returned
 * ever contains the key.</p>
 */
public final class ProviderHints {

	/** What went wrong, as far as hints need to know; mirrors the test outcomes. */
	public enum Problem {
		NO_KEY, BAD_KEY, NO_CREDIT, RATE_LIMITED, MODEL_UNAVAILABLE, UNREACHABLE
	}

	private ProviderHints() {
	}

	/**
	 * A hint about the key itself, before or after any call; null when it looks
	 * fine. {@code providerId} is the provider chosen; {@code key} as pasted.
	 */
	public static String forKey(String providerId, String key) {
		if (key == null || key.isEmpty()) return null;
		String k = key.strip();
		if (k.isEmpty()) return null;
		if (k.regionMatches(true, 0, "Bearer ", 0, 7)) return "Paste just the key, without \"Bearer \" in front of it.";
		if (k.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*[=:].*")) return "Paste only the key itself, not the NAME= part in front of it.";
		if (quoted(k)) return "Remove the quotation marks around the key.";
		if (k.chars().anyMatch(Character::isWhitespace)) return "The key has a space or line break in it. Paste it again on its own.";
		if (k.contains("…") || k.contains("...") || k.contains("•") || k.contains("*")) {
			return "This looks like the shortened key a console shows (with … or dots). Copy the full key: most providers show it only once, when it's created.";
		}
		String special = specialToken(k);
		if (special != null) return special;
		String looksLike = providerOfKey(k);
		Providers.Provider chosen = Providers.byId(providerId);
		if (looksLike != null && chosen != null && !looksLike.equals(providerId)) {
			Providers.Provider other = Providers.byId(looksLike);
			String otherName = (other != null) ? other.label() : looksLike;
			return "This looks like " + article(otherName) + " " + otherName + " key" + prefixNote(looksLike)
				+ ". Choose " + otherName + " as the provider, or paste " + article(chosen.label()) + " "
				+ chosen.label() + " key" + expectedNote(providerId) + ".";
		}
		if (looksLike == null && k.startsWith("gsk_")) return "This looks like a Groq key. Groq isn't one of the providers here yet; OpenRouter can reach many of the same models.";
		if (looksLike == null && k.startsWith("pplx-")) return "This looks like a Perplexity key. Perplexity isn't one of the providers here.";
		String expected = distinctivePrefix(providerId);
		if (expected != null && chosen != null && !k.startsWith(expected)) {
			return chosen.label() + " keys usually start " + expected + ". This one doesn't, so it may be from another provider"
				+ (k.startsWith("sk-") ? " (OpenAI and DeepSeek keys start sk-)" : "") + ".";
		}
		Integer min = minimumLength(providerId);
		if (min != null && k.length() < min && chosen != null) {
			return "That's short for " + article(chosen.label()) + " " + chosen.label() + " key. It may have been cut off when you copied it.";
		}
		return null;
	}

	/**
	 * Where to look next after a failed test, for this provider; null when there
	 * is nothing useful to add. {@code error} is the provider's error text, for
	 * the few cases where it says more than the problem does.
	 */
	public static String forProblem(String providerId, Problem problem, String error) {
		return forProblem(providerId, problem, error, null);
	}

	/** As {@link #forProblem(String, Problem, String)}, naming {@code modelId} where it helps (Ollama). */
	public static String forProblem(String providerId, Problem problem, String error, String modelId) {
		String e = (error == null) ? "" : error.toLowerCase(Locale.ROOT);
		String id = (providerId == null) ? "" : providerId;
		return switch (problem) {
			case NO_KEY -> keysPage(id);
			case BAD_KEY -> switch (id) {
				case "anthropic" -> "Check the key at console.anthropic.com/settings/keys: it may have been deleted or disabled.";
				case "openai" -> "Check the key at platform.openai.com/api-keys. A project key stops working if its project is archived.";
				case "xai" -> "Check the key in console.x.ai under API Keys.";
				case "gemini" -> "Check the key at aistudio.google.com/app/apikey. In Google Cloud it may be restricted to other APIs.";
				case "openrouter" -> "Check the key at openrouter.ai/keys: it may be disabled or past its spending limit.";
				case "deepseek" -> "Check the key at platform.deepseek.com/api_keys.";
				case "mistral" -> "Check the key at console.mistral.ai/api-keys. A new Mistral key can take a few minutes to start working.";
				default -> null;
			};
			case NO_CREDIT -> switch (id) {
				case "anthropic" -> "Add credit at console.anthropic.com/settings/billing. A Claude Pro or Max subscription doesn't cover API use.";
				case "openai" -> "Add credit at platform.openai.com/settings/organization/billing. A ChatGPT subscription doesn't cover API use.";
				case "xai" -> "Add credit in console.x.ai under Billing. A Grok subscription doesn't cover API use.";
				case "gemini" -> "The free tier's quota may be used up, or billing isn't set up for this key's Google Cloud project.";
				case "openrouter" -> "Buy credits at openrouter.ai/settings/credits, or choose a free model.";
				case "deepseek" -> "Top up at platform.deepseek.com/top_up.";
				case "mistral" -> "Choose a plan or add billing at console.mistral.ai.";
				default -> null;
			};
			case RATE_LIMITED -> (e.contains("529") || e.contains("overloaded"))
				? "The provider is overloaded right now. This isn't about your key."
				: "New accounts start with low limits that rise as you use them.";
			case MODEL_UNAVAILABLE -> {
				if (e.contains("location") && e.contains("not supported")) {
					yield "The provider doesn't offer this model where you are.";
				}
				yield switch (id) {
					case "openai" -> "Some newer OpenAI models need your organisation verified (platform.openai.com/settings/organization/general). Or choose another model.";
					case "openrouter" -> "OpenRouter model names include the vendor, such as anthropic/claude-sonnet-5. Choose another from the list.";
					case "ollama" -> "Download it first with: ollama pull " + (modelId != null ? modelId : "<model>");
					default -> "The model may have been renamed or retired. Choose another from the list.";
				};
			}
			case UNREACHABLE -> "ollama".equals(id)
				? "Start Ollama (open the app, or run: ollama serve) and try again."
				: "A VPN, company proxy or firewall may be blocking the connection.";
		};
	}

	/** The provider whose keys look like this, or null when the shape is shared or unknown. */
	static String providerOfKey(String k) {
		if (k.startsWith("sk-ant-")) return "anthropic";
		if (k.startsWith("sk-or-")) return "openrouter";
		if (k.startsWith("xai-")) return "xai";
		if (k.startsWith("AIza")) return "gemini";
		if (k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-")) return "openai";
		return null;
	}

	/** The start every key of this provider has, where it is distinctive; null when keys share a shape. */
	private static String distinctivePrefix(String providerId) {
		return switch (providerId == null ? "" : providerId) {
			case "anthropic" -> "sk-ant-";
			case "openrouter" -> "sk-or-";
			case "xai" -> "xai-";
			case "gemini" -> "AIza";
			default -> null;
		};
	}

	/** Tokens that look like keys but can't call models. */
	private static String specialToken(String k) {
		if (k.startsWith("sk-ant-admin")) {
			return "This is an Anthropic Admin key. It manages the organisation but can't call models. Create an ordinary key under API keys in the console.";
		}
		if (k.startsWith("sk-ant-oat") || k.startsWith("sk-ant-ort")) {
			return "This looks like a Claude subscription sign-in token, not an API key. API keys come from console.anthropic.com, billed separately from Claude Pro or Max.";
		}
		if (k.startsWith("sk-admin-")) {
			return "This is an OpenAI Admin key. It manages the organisation but can't call models. Create an ordinary key under API keys.";
		}
		return null;
	}

	private static String prefixNote(String providerId) {
		return switch (providerId) {
			case "anthropic" -> " (it starts sk-ant-)";
			case "openrouter" -> " (it starts sk-or-)";
			case "xai" -> " (it starts xai-)";
			case "gemini" -> " (it starts AIza)";
			case "openai" -> " (it starts sk-proj- or sk-svcacct-)";
			default -> "";
		};
	}

	private static String expectedNote(String providerId) {
		return switch (providerId) {
			case "anthropic" -> " (they start sk-ant-)";
			case "openrouter" -> " (they start sk-or-)";
			case "xai" -> " (they start xai-)";
			case "gemini" -> " (they start AIza)";
			case "openai", "deepseek" -> " (they start sk-)";
			default -> "";
		};
	}

	/** Below this a key of this provider has almost certainly been cut short; null when unknown. */
	private static Integer minimumLength(String providerId) {
		return switch (providerId == null ? "" : providerId) {
			case "anthropic", "openrouter", "xai", "openai" -> 40;
			case "gemini" -> 35;
			case "deepseek", "mistral" -> 30;
			default -> null;
		};
	}

	private static String keysPage(String providerId) {
		Providers.Provider p = Providers.byId(providerId);
		if (p == null || p.consoleUrl() == null || !p.needsApiKey()) return null;
		return "Get one at " + p.consoleUrl().replaceFirst("^https?://", "") + ".";
	}

	private static boolean quoted(String k) {
		char a = k.charAt(0);
		char z = k.charAt(k.length() - 1);
		return k.length() > 1 && ((a == '"' && z == '"') || (a == '\'' && z == '\'') || (a == '“' && z == '”'));
	}

	private static String article(String name) {
		return "AEIOU".indexOf(Character.toUpperCase(name.charAt(0))) >= 0 ? "an" : "a";
	}
}
