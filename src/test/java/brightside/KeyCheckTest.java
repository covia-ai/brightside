package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.KeyCheck.Outcome;
import brightside.KeyCheck.Result;
import covia.grid.Venue;

/**
 * Mechanism test for the <b>Test</b> action beside an API key: a call through
 * the venue reports that the model works, a failing call says why in plain
 * words, the key never shows in what comes back, and onboarding's standalone
 * test runs without a home venue.
 */
class KeyCheckTest {

	private static final String ECHO = "v/test/ops/llm";
	private static final String SONNET = "v/models/anthropic/claude-sonnet-5-5";
	private static final String KEY = "sk-ant-test-0123456789abcdef";

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void aWorkingModelSaysSoAsTheCaller() {
		EmbeddedVenue venue = shared.venue();
		Venue client = venue.clientAs(Identity.of("key-check").userDID(venue.did()));
		Result r = KeyCheck.check(client, ECHO, KEY);
		assertTrue(r.ok(), r.message());
		assertTrue(r.message().startsWith("Works: "), r.message());
		assertFalse(r.message().contains(KEY));
	}

	@Test
	void aFailingCallIsReportedWithoutTheKey() {
		EmbeddedVenue venue = shared.venue();
		Venue client = venue.clientAs(Identity.of("key-check-fail").userDID(venue.did()));
		Result r = KeyCheck.check(client, "v/models/anthropic/no-such-model", KEY);
		assertFalse(r.ok(), r.message());
		assertFalse(r.message().contains(KEY), r.message());
	}

	@Test
	void onboardingTestsWithoutAHomeVenue() {
		Result r = KeyCheck.checkStandalone(ECHO, null);
		assertTrue(r.ok(), r.message());
	}

	@Test
	void providerErrorsAreNamed() {
		assertEquals(Outcome.BAD_KEY, KeyCheck.classify("401 Unauthorized: invalid x-api-key"));
		assertEquals(Outcome.BAD_KEY, KeyCheck.classify("Incorrect API key provided"));
		assertEquals(Outcome.BAD_KEY, KeyCheck.classify("API key not valid. Please pass a valid API key."));
		assertEquals(Outcome.NO_CREDIT, KeyCheck.classify("429 insufficient_quota: You exceeded your current quota"));
		assertEquals(Outcome.NO_CREDIT, KeyCheck.classify("Your credit balance is too low to access the Anthropic API"));
		assertEquals(Outcome.NO_CREDIT, KeyCheck.classify("402 Payment Required"));
		assertEquals(Outcome.RATE_LIMITED, KeyCheck.classify("429 Too Many Requests"));
		assertEquals(Outcome.RATE_LIMITED, KeyCheck.classify("529 overloaded_error"));
		assertEquals(Outcome.MODEL_UNAVAILABLE, KeyCheck.classify("404 not_found_error: model: claude-x"));
		assertEquals(Outcome.MODEL_UNAVAILABLE, KeyCheck.classify("The model `grok-9` does not exist"));
		assertEquals(Outcome.UNREACHABLE, KeyCheck.classify("java.net.ConnectException: Connection refused"));
		assertEquals(Outcome.UNREACHABLE, KeyCheck.classify("java.net.UnknownHostException: api.x.ai"));
		assertEquals(Outcome.NO_KEY, KeyCheck.classify("API key is required for provider anthropic"));
		assertEquals(Outcome.FAILED, KeyCheck.classify("something unexpected"));
		assertEquals(Outcome.FAILED, KeyCheck.classify(null));
	}

	@Test
	void theKeyIsMaskedInWhatIsShown() {
		Result r = KeyCheck.fromError(SONNET, "401 invalid x-api-key " + KEY + " was rejected", KEY, 5);
		assertEquals(Outcome.BAD_KEY, r.outcome());
		assertFalse(r.message().contains(KEY), r.message());
		Result other = KeyCheck.fromError(SONNET, "echoed sk-proj-AAAABBBBCCCCDDDD1234 back", null, 5);
		assertFalse(other.message().contains("sk-proj-AAAABBBBCCCCDDDD1234"), other.message());
		assertTrue(other.message().contains("•••"), other.message());
	}

	@Test
	void theKeysOwnShapeExplainsARefusalFirst() {
		String router = "sk-or-v1-0123456789abcdef0123456789abcdef0123456789abcdef";
		String hint = KeyCheck.hint(SONNET, Outcome.BAD_KEY, "401 invalid x-api-key", router);
		assertTrue(hint.startsWith("This looks like an OpenRouter key"), hint);
		assertFalse(hint.contains(router));
		// A good-looking key that was refused points at the provider's key page instead.
		assertTrue(KeyCheck.hint(SONNET, Outcome.BAD_KEY, "401", KEY + "0123456789abcdefghij").contains("console.anthropic.com/settings/keys"));
	}

	@Test
	void eachOutcomeGetsItsNextStep() {
		assertTrue(KeyCheck.hint(SONNET, Outcome.NO_CREDIT, "credit balance is too low", null).contains("billing"));
		assertTrue(KeyCheck.hint(SONNET, Outcome.RATE_LIMITED, "529 overloaded", null).contains("isn't about your key"));
		assertTrue(KeyCheck.hint("v/models/ollama/qwen", Outcome.UNREACHABLE, "Connection refused", null).contains("ollama serve"));
		assertEquals(null, KeyCheck.hint(SONNET, Outcome.FAILED, "something odd", null));
	}

	@Test
	void aFailureReturnedAsAResultIsAFailure() {
		assertEquals("blocked", KeyCheck.inBandFailure(convex.core.data.Maps.of("status", "FAILED", "message", "blocked")));
		assertEquals("REJECTED", KeyCheck.inBandFailure(convex.core.data.Maps.of("status", "REJECTED")));
		assertEquals(null, KeyCheck.inBandFailure(convex.core.data.Maps.of("role", "assistant", "content", "OK")));
		assertEquals(null, KeyCheck.inBandFailure(null));
	}
}
