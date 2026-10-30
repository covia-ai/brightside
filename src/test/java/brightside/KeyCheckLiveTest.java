package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import brightside.KeyCheck.Outcome;
import brightside.KeyCheck.Result;

/**
 * The <b>Test</b> key action against the real providers, for a person checking
 * a release by hand. Off unless the key is in the environment under the name
 * below, so the ordinary suite never makes a paid call or needs a key:
 *
 * <pre>BRIGHTSIDE_LIVE_ANTHROPIC_KEY=sk-ant-… mvn test -Dtest=KeyCheckLiveTest</pre>
 *
 * Each provider is one tiny call that works and one with the key spoiled,
 * which the provider must refuse: a fraction of a cent in all.
 */
class KeyCheckLiveTest {

	private static String env(String name) {
		return System.getenv(name);
	}

	/** {@code key} with its last character changed: the same shape, refused. */
	private static String spoiled(String key) {
		char last = key.charAt(key.length() - 1);
		return key.substring(0, key.length() - 1) + (last == 'x' ? 'y' : 'x');
	}

	private static void worksAndRefusesASpoiledKey(String modelOp, String key) {
		Result ok = KeyCheck.checkStandalone(modelOp, key);
		assertEquals(Outcome.WORKS, ok.outcome(), ok.message());
		Result bad = KeyCheck.checkStandalone(modelOp, spoiled(key));
		assertEquals(Outcome.BAD_KEY, bad.outcome(), bad.message());
		// Neither the key nor the spoiled key actually sent may come back, whole
		// or as a long prefix.
		String sent = spoiled(key);
		String prefix = sent.substring(0, Math.min(sent.length(), 16));
		for (String shown : new String[] { bad.message(), bad.hint() == null ? "" : bad.hint() }) {
			assertTrue(!shown.contains(key) && !shown.contains(sent) && !shown.contains(prefix), "key leaked: " + shown);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BRIGHTSIDE_LIVE_ANTHROPIC_KEY", matches = ".+")
	void anthropic() {
		worksAndRefusesASpoiledKey("v/models/anthropic/claude-haiku-5-5", env("BRIGHTSIDE_LIVE_ANTHROPIC_KEY"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BRIGHTSIDE_LIVE_OPENAI_KEY", matches = ".+")
	void openai() {
		worksAndRefusesASpoiledKey("v/models/openai/gpt-5.4-mini", env("BRIGHTSIDE_LIVE_OPENAI_KEY"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BRIGHTSIDE_LIVE_XAI_KEY", matches = ".+")
	void xai() {
		worksAndRefusesASpoiledKey("v/models/xai/grok-4.3", env("BRIGHTSIDE_LIVE_XAI_KEY"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BRIGHTSIDE_LIVE_OPENROUTER_KEY", matches = ".+")
	void openrouter() {
		worksAndRefusesASpoiledKey("v/models/openrouter/openrouter/auto", env("BRIGHTSIDE_LIVE_OPENROUTER_KEY"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BRIGHTSIDE_LIVE_GEMINI_KEY", matches = ".+")
	void gemini() {
		worksAndRefusesASpoiledKey("v/models/gemini/gemini-3.6-flash", env("BRIGHTSIDE_LIVE_GEMINI_KEY"));
	}
}
