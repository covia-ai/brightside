package brightside.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.model.ProviderHints.Problem;

/**
 * What {@link ProviderHints} says about a key, for every provider: the
 * pasting slips, another provider's key, the tokens that can't call models,
 * a cut-off key, and the next step after each kind of failure. Keys here are
 * made-up values in each provider's shape.
 */
class ProviderHintsTest {

	static final String ANTHROPIC = "sk-ant-api03-" + "AbCdEfGhIjKlMnOpQrStUvWxYz" + "0123456789abcdefghij";
	static final String OPENAI = "sk-proj-" + "AbCdEfGhIjKlMnOpQrStUvWxYz" + "0123456789abcdefghij";
	static final String XAI = "xai-" + "AbCdEfGhIjKlMnOpQrStUvWxYz" + "0123456789abcdefghijklmnop";
	static final String GEMINI = "AIza" + "SyAbCdEfGhIjKlMnOpQrStUvWxYz012345";
	static final String OPENROUTER = "sk-or-v1-" + "0123456789abcdef".repeat(3);
	static final String DEEPSEEK = "sk-" + "0123456789abcdef".repeat(2);
	static final String MISTRAL = "AbCdEfGhIjKlMnOp" + "QrStUvWxYz012345";

	// ---------------- A well-formed key raises nothing ----------------

	@Test
	void aWellFormedKeyRaisesNothing() {
		String[][] cases = {
			{ "anthropic", ANTHROPIC }, { "openai", OPENAI }, { "xai", XAI }, { "gemini", GEMINI },
			{ "openrouter", OPENROUTER }, { "deepseek", DEEPSEEK }, { "mistral", MISTRAL },
		};
		for (String[] c : cases) {
			assertNull(ProviderHints.forKey(c[0], c[1]), c[0]);
			assertNull(ProviderHints.forKey(c[0], "  " + c[1] + "\n"), c[0] + ": surrounding space is trimmed, not flagged");
		}
	}

	@Test
	void nothingToSayAboutNoKey() {
		assertNull(ProviderHints.forKey("anthropic", null));
		assertNull(ProviderHints.forKey("anthropic", ""));
		assertNull(ProviderHints.forKey("anthropic", "   "));
		assertNull(ProviderHints.forKey("ollama", null));
	}

	// ---------------- Pasting slips ----------------

	@Test
	void pastingSlipsAreNamed() {
		assertTrue(ProviderHints.forKey("anthropic", "Bearer " + ANTHROPIC).contains("without \"Bearer \""));
		assertTrue(ProviderHints.forKey("anthropic", "bearer " + ANTHROPIC).contains("Bearer"));
		assertTrue(ProviderHints.forKey("anthropic", "ANTHROPIC_API_KEY=" + ANTHROPIC).contains("NAME="));
		assertTrue(ProviderHints.forKey("openai", "OPENAI_API_KEY: " + OPENAI).contains("NAME="));
		assertTrue(ProviderHints.forKey("anthropic", "\"" + ANTHROPIC + "\"").contains("quotation marks"));
		assertTrue(ProviderHints.forKey("anthropic", "'" + ANTHROPIC + "'").contains("quotation marks"));
		assertTrue(ProviderHints.forKey("anthropic", "“" + ANTHROPIC + "”").contains("quotation marks"));
		assertTrue(ProviderHints.forKey("anthropic", ANTHROPIC.substring(0, 20) + "\n" + ANTHROPIC.substring(20)).contains("line break"));
		assertTrue(ProviderHints.forKey("anthropic", ANTHROPIC.substring(0, 20) + " " + ANTHROPIC.substring(20)).contains("space"));
	}

	@Test
	void theShortenedKeyAConsoleDisplaysIsNamed() {
		for (String shown : List.of("sk-ant-api03-AbCd...wxyz", "sk-ant-api03-AbCd…wxyz", "sk-proj-••••wxyz", "sk-proj-****wxyz")) {
			String hint = ProviderHints.forKey("anthropic", shown);
			assertNotNull(hint, shown);
			assertTrue(hint.contains("shortened key"), hint);
		}
	}

	// ---------------- Tokens that can't call models ----------------

	@Test
	void tokensThatCantCallModelsAreNamed() {
		assertTrue(ProviderHints.forKey("anthropic", "sk-ant-admin01-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789").contains("Admin key"));
		assertTrue(ProviderHints.forKey("anthropic", "sk-ant-oat01-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789").contains("subscription sign-in token"));
		assertTrue(ProviderHints.forKey("openai", "sk-admin-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789").contains("OpenAI Admin key"));
	}

	// ---------------- Another provider's key ----------------

	@Test
	void anotherProvidersKeyIsNamed() {
		String[][] cases = {
			{ "anthropic", "openrouter", OPENROUTER, "an OpenRouter key" },
			{ "anthropic", "openai", OPENAI, "an OpenAI key" },
			{ "openai", "anthropic", ANTHROPIC, "an Anthropic key" },
			{ "xai", "gemini", GEMINI, "a Gemini (Google) key" },
			{ "gemini", "xai", XAI, "a Grok (xAI) key" },
			{ "openrouter", "anthropic", ANTHROPIC, "an Anthropic key" },
			{ "deepseek", "openai", OPENAI, "an OpenAI key" },
			{ "mistral", "openrouter", OPENROUTER, "an OpenRouter key" },
		};
		for (String[] c : cases) {
			String hint = ProviderHints.forKey(c[0], c[2]);
			assertNotNull(hint, c[1] + " key chosen as " + c[0]);
			assertTrue(hint.startsWith("This looks like " + c[3]), hint);
			assertTrue(hint.contains("Choose " + Providers.byId(c[1]).label() + " as the provider"), hint);
		}
	}

	@Test
	void keysForProvidersNotOfferedAreNamed() {
		assertTrue(ProviderHints.forKey("openai", "gsk_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefghij").contains("Groq"));
		assertTrue(ProviderHints.forKey("openai", "pplx-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefghij").contains("Perplexity"));
	}

	@Test
	void aCutOffKeyIsNamed() {
		assertTrue(ProviderHints.forKey("anthropic", "sk-ant-api03-AbCd").contains("cut off"));
		assertTrue(ProviderHints.forKey("gemini", "AIzaSyAbCd").contains("cut off"));
		assertTrue(ProviderHints.forKey("mistral", "AbCdEf").contains("cut off"));
	}

	@Test
	void aHintNeverRepeatsTheKey() {
		for (String key : List.of(ANTHROPIC, OPENAI, XAI, GEMINI, OPENROUTER, DEEPSEEK, MISTRAL,
				"Bearer " + ANTHROPIC, "\"" + OPENAI + "\"", "sk-ant-admin01-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")) {
			for (Providers.Provider p : Providers.ALL) {
				String hint = ProviderHints.forKey(p.id(), key);
				if (hint != null) assertFalse(hint.contains(key.strip().replace("Bearer ", "").replace("\"", "")), p.id() + ": " + hint);
			}
		}
	}

	// ---------------- After a failed test ----------------

	@Test
	void everyKeyedProviderHasANextStepForARefusedKeyOrNoCredit() {
		for (Providers.Provider p : Providers.ALL) {
			if (!p.needsApiKey()) continue;
			assertNotNull(ProviderHints.forProblem(p.id(), Problem.BAD_KEY, null), p.id() + " refused key");
			assertNotNull(ProviderHints.forProblem(p.id(), Problem.NO_CREDIT, null), p.id() + " no credit");
			assertNotNull(ProviderHints.forProblem(p.id(), Problem.NO_KEY, null), p.id() + " no key");
		}
	}

	@Test
	void noCreditSaysWhereToPay() {
		String[][] cases = {
			{ "anthropic", "console.anthropic.com/settings/billing" },
			{ "openai", "platform.openai.com/settings/organization/billing" },
			{ "openrouter", "openrouter.ai/settings/credits" },
			{ "deepseek", "platform.deepseek.com/top_up" },
		};
		for (String[] c : cases) assertTrue(ProviderHints.forProblem(c[0], Problem.NO_CREDIT, null).contains(c[1]), c[0]);
	}

	@Test
	void subscriptionsAreNotApiCredit() {
		assertTrue(ProviderHints.forProblem("anthropic", Problem.NO_CREDIT, null).contains("Claude Pro or Max"));
		assertTrue(ProviderHints.forProblem("openai", Problem.NO_CREDIT, null).contains("ChatGPT subscription"));
		assertTrue(ProviderHints.forProblem("xai", Problem.NO_CREDIT, null).contains("Grok subscription"));
	}

	@Test
	void overloadedIsTheProvidersProblemNotTheKeys() {
		assertTrue(ProviderHints.forProblem("anthropic", Problem.RATE_LIMITED, "529 overloaded_error").contains("isn't about your key"));
		assertTrue(ProviderHints.forProblem("openai", Problem.RATE_LIMITED, "429 Too Many Requests").contains("low limits"));
	}

	@Test
	void modelAndReachHints() {
		assertTrue(ProviderHints.forProblem("gemini", Problem.MODEL_UNAVAILABLE, "User location is not supported for the API use.").contains("where you are"));
		assertTrue(ProviderHints.forProblem("openai", Problem.MODEL_UNAVAILABLE, "404").contains("organisation verified"));
		assertTrue(ProviderHints.forProblem("ollama", Problem.MODEL_UNAVAILABLE, "404").contains("ollama pull"));
		assertTrue(ProviderHints.forProblem("ollama", Problem.UNREACHABLE, null).contains("ollama serve"));
		assertTrue(ProviderHints.forProblem("anthropic", Problem.UNREACHABLE, null).contains("VPN"));
	}

	@Test
	void anUnknownProviderStillGetsGeneralHints() {
		assertNull(ProviderHints.forProblem(null, Problem.BAD_KEY, null));
		assertEquals("A VPN, company proxy or firewall may be blocking the connection.",
			ProviderHints.forProblem(null, Problem.UNREACHABLE, null));
	}
}
