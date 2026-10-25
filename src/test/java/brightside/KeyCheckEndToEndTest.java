package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.KeyCheck.Outcome;
import brightside.KeyCheck.Result;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * End to end for the <b>Test</b> key action: the real venue, its real model
 * operations and their HTTP clients, against a {@link StubProvider} speaking
 * each wire format — Anthropic's own, and the OpenAI-compatible one that
 * OpenAI, xAI, Gemini, DeepSeek, Mistral and OpenRouter share. Checks what the
 * owner is told for each kind of provider reply, and what actually left the
 * machine: which key, in which header, how many calls, and that the key is
 * kept out of the venue's job records.
 */
class KeyCheckEndToEndTest {

	@RegisterExtension
	static final SharedVenue SHARED = new SharedVenue();

	private static final String SONNET = "v/models/anthropic/claude-sonnet-5-5";
	private static final String GPT = "v/models/openai/gpt-5.6-terra";
	private static final String GROK = "v/models/xai/grok-4.5";
	private static final String GEMINI = "v/models/gemini/gemini-3.6-flash";
	private static final String ROUTER = "v/models/openrouter/openrouter/auto";
	private static final String DEEPSEEK = "v/models/deepseek/deepseek-v4-flash";
	private static final String ANTHROPIC_KEY = "sk-ant-api03-e2etypedkey0123456789abcdefghijklmnop";
	private static final String OPENAI_KEY = "sk-proj-e2etypedkey0123456789abcdefghijklmnopqrs";

	/**
	 * The venue's SSRF guard refuses loopback, so the stub is allowed in by
	 * name here, as an owner would allow a local model server; a refused URL
	 * is itself tested below.
	 */
	static final AMap<AString, ACell> LOOPBACK_ALLOWED = Maps.of("adapters",
		Maps.of("http", Maps.of("allowedHosts", Vectors.of("127.0.0.1"))));

	private static Path home;
	private static EmbeddedVenue venue;

	@BeforeAll
	static void launch() throws Exception {
		home = Files.createTempDirectory("brightside-keycheck-e2e");
		venue = EmbeddedVenue.launch(AppConfig.merge(SharedVenue.config(home), LOOPBACK_ALLOWED));
	}

	@AfterAll
	static void close() throws Exception {
		if (venue != null) venue.close();
		try (var paths = Files.walk(home)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	private static Venue client(String name) {
		return venue.clientAs(Identity.of(name).userDID(venue.did()));
	}

	// ---------------- Anthropic wire format ----------------

	@Test
	void anthropicWorksAndSendsOneSmallRequestWithTheTypedKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(200, StubProvider.ANTHROPIC_OK)) {
			Result r = KeyCheck.check(client("e2e-anthropic-ok"), SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
			assertEquals(Outcome.WORKS, r.outcome(), r.message());
			assertTrue(r.message().startsWith("Works: Claude Sonnet 5.5 replied in "), r.message());
			assertEquals(null, r.hint());
			List<StubProvider.Request> sent = stub.received();
			assertEquals(1, sent.size(), "one call, no retries");
			StubProvider.Request req = sent.get(0);
			assertEquals("POST", req.method());
			assertTrue(req.path().endsWith("/messages"), req.path());
			assertEquals(ANTHROPIC_KEY, req.headers().get("x-api-key"), "the typed key, in Anthropic's header");
			assertNotNull(req.headers().get("anthropic-version"));
			assertTrue(req.body().matches("(?s).*\"max_tokens\"\\s*:\\s*" + KeyCheck.MAX_TOKENS + "\\b.*"), req.body());
			assertTrue(req.body().contains("claude-sonnet-5-5"), "the model chosen is the model tested");
		}
	}

	@Test
	void anthropicRefusedKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(401,
				StubProvider.anthropicError("authentication_error", "invalid x-api-key"))) {
			Result r = KeyCheck.check(client("e2e-anthropic-401"), SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
			assertEquals(Outcome.BAD_KEY, r.outcome(), r.message());
			assertEquals("Anthropic didn't accept this key. Check it was copied in full.", r.message());
			assertTrue(r.hint().contains("console.anthropic.com/settings/keys"), r.hint());
			assertEquals(1, stub.received().size(), "a refused key isn't retried");
		}
	}

	@Test
	void anthropicNoCredit() throws Exception {
		try (StubProvider stub = StubProvider.replying(400, StubProvider.anthropicError("invalid_request_error",
				"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."))) {
			Result r = KeyCheck.check(client("e2e-anthropic-credit"), SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
			assertEquals(Outcome.NO_CREDIT, r.outcome(), r.message());
			assertTrue(r.hint().contains("console.anthropic.com/settings/billing"), r.hint());
			assertTrue(r.hint().contains("Pro or Max subscription doesn't cover API use"), r.hint());
		}
	}

	@Test
	void anthropicUnknownModel() throws Exception {
		try (StubProvider stub = StubProvider.replying(404,
				StubProvider.anthropicError("not_found_error", "model: claude-sonnet-5-5"))) {
			Result r = KeyCheck.check(client("e2e-anthropic-404"), SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
			assertEquals(Outcome.MODEL_UNAVAILABLE, r.outcome(), r.message());
		}
	}

	@Test
	void theStoredKeyIsUsedWhenNoneIsTypedAndATypedKeyWins() throws Exception {
		String did = Identity.of("e2e-stored").userDID(venue.did());
		String stored = "sk-ant-api03-e2estoredkey0123456789abcdefghijklmnop";
		venue.secrets(did, true).store("ANTHROPIC_API_KEY", stored, venue.secretKey());
		try (StubProvider stub = StubProvider.replying(200, StubProvider.ANTHROPIC_OK)) {
			assertTrue(KeyCheck.check(venue.clientAs(did), SONNET, null, stub.anthropicUrl()).ok());
			assertTrue(KeyCheck.check(venue.clientAs(did), SONNET, ANTHROPIC_KEY, stub.anthropicUrl()).ok());
			List<StubProvider.Request> sent = stub.received();
			assertEquals(stored, sent.get(0).headers().get("x-api-key"), "blank field: the stored key");
			assertEquals(ANTHROPIC_KEY, sent.get(1).headers().get("x-api-key"), "typed key: that one, unsaved");
		}
		assertEquals(stored, venue.secrets(did, false).decrypt("ANTHROPIC_API_KEY", venue.secretKey()).toString(),
			"testing a typed key never replaces the stored one");
	}

	@Test
	void theTypedKeyIsKeptOutOfJobRecords() throws Exception {
		String did = Identity.of("e2e-records").userDID(venue.did());
		try (StubProvider stub = StubProvider.replying(401,
				StubProvider.anthropicError("authentication_error", "invalid x-api-key"))) {
			KeyCheck.check(venue.clientAs(did), SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
		}
		Index<Blob, ACell> jobs = venue.engine().jobs().getJobs(RequestContext.of(Strings.create(did)));
		String recorded = (jobs == null) ? "" : jobs.toString();
		assertFalse(recorded.contains(ANTHROPIC_KEY), "the key is a declared secret field, never recorded");
	}

	// ---------------- OpenAI-compatible wire format ----------------

	@Test
	void openAiWorksWithABearerKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(200, StubProvider.OPENAI_OK)) {
			Result r = KeyCheck.check(client("e2e-openai-ok"), GPT, OPENAI_KEY, stub.openAiUrl());
			assertEquals(Outcome.WORKS, r.outcome(), r.message());
			StubProvider.Request req = stub.received().get(0);
			assertTrue(req.path().endsWith("/chat/completions"), req.path());
			assertEquals("Bearer " + OPENAI_KEY, req.headers().get("authorization"));
			assertTrue(req.body().contains("gpt-5.6-terra"), req.body());
		}
	}

	@Test
	void openAiRefusedKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(401, StubProvider.openAiError("invalid_request_error",
				"invalid_api_key", "Incorrect API key provided: sk-proj-****mnop. You can find your API key at https://platform.openai.com/account/api-keys."))) {
			Result r = KeyCheck.check(client("e2e-openai-401"), GPT, OPENAI_KEY, stub.openAiUrl());
			assertEquals(Outcome.BAD_KEY, r.outcome(), r.message());
			assertTrue(r.hint().contains("platform.openai.com/api-keys"), r.hint());
		}
	}

	@Test
	void openAiQuotaIsNoCreditNotARateLimit() throws Exception {
		try (StubProvider stub = StubProvider.replying(429, StubProvider.openAiError("insufficient_quota",
				"insufficient_quota", "You exceeded your current quota, please check your plan and billing details."))) {
			Result r = KeyCheck.check(client("e2e-openai-quota"), GPT, OPENAI_KEY, stub.openAiUrl());
			assertEquals(Outcome.NO_CREDIT, r.outcome(), r.message());
			assertTrue(r.hint().contains("ChatGPT subscription doesn't cover API use"), r.hint());
			// The venue's client retries a 429 (twice today), quota or not: bounded, never a loop.
			assertTrue(stub.received().size() <= 3, "retries are bounded: " + stub.received().size());
		}
	}

	@Test
	void xaiRefusedKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(400, StubProvider.openAiError("invalid_request_error", null,
				"Incorrect API key provided: xa***yz. You can obtain an API key from https://console.x.ai."))) {
			Result r = KeyCheck.check(client("e2e-xai-400"), GROK, "xai-e2etypedkey0123456789abcdefghijklmnopqrstuvwx", stub.openAiUrl());
			assertEquals(Outcome.BAD_KEY, r.outcome(), r.message());
			assertTrue(r.message().startsWith("Grok (xAI) didn't accept this key"), r.message());
		}
	}

	@Test
	void geminiRefusedKey() throws Exception {
		try (StubProvider stub = StubProvider.replying(400,
				"[{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}]")) {
			Result r = KeyCheck.check(client("e2e-gemini-400"), GEMINI, "AIzaSyE2eTypedKey0123456789abcdefghijk", stub.openAiUrl());
			assertEquals(Outcome.BAD_KEY, r.outcome(), r.message());
			assertTrue(r.hint().contains("aistudio.google.com"), r.hint());
		}
	}

	@Test
	void openRouterOutOfCredits() throws Exception {
		try (StubProvider stub = StubProvider.replying(402,
				"{\"error\":{\"message\":\"Insufficient credits. Add more using https://openrouter.ai/settings/credits\",\"code\":402}}")) {
			Result r = KeyCheck.check(client("e2e-router-402"), ROUTER, "sk-or-v1-e2etypedkey0123456789abcdefghijklmnopqrstuvwxyz", stub.openAiUrl());
			assertEquals(Outcome.NO_CREDIT, r.outcome(), r.message());
			assertTrue(r.hint().contains("openrouter.ai/settings/credits"), r.hint());
		}
	}

	@Test
	void deepSeekOutOfBalance() throws Exception {
		try (StubProvider stub = StubProvider.replying(402, StubProvider.openAiError("unknown_error", "invalid_request_error",
				"Insufficient Balance"))) {
			Result r = KeyCheck.check(client("e2e-deepseek-402"), DEEPSEEK, "sk-e2etypedkey0123456789abcdef01", stub.openAiUrl());
			assertEquals(Outcome.NO_CREDIT, r.outcome(), r.message());
			assertTrue(r.hint().contains("platform.deepseek.com/top_up"), r.hint());
		}
	}

	@Test
	void aKeyForAnotherProviderIsNamed() throws Exception {
		try (StubProvider stub = StubProvider.replying(401,
				StubProvider.anthropicError("authentication_error", "invalid x-api-key"))) {
			Result r = KeyCheck.check(client("e2e-wrong-provider"), SONNET,
				"sk-or-v1-e2ewrongprovider0123456789abcdefghijklmnopqrstuvwxyz", stub.anthropicUrl());
			assertEquals(Outcome.BAD_KEY, r.outcome(), r.message());
			assertTrue(r.hint().startsWith("This looks like an OpenRouter key"), r.hint());
		}
	}

	// ---------------- No provider at all ----------------

	@Test
	void aUrlTheVenueRefusesIsAFailureNotASuccess() throws Exception {
		// The default venue (no loopback allowance) refuses before calling out,
		// returning a failure status as its result: that must never read as Works.
		EmbeddedVenue strict = SHARED.venue();
		try (StubProvider stub = StubProvider.replying(200, StubProvider.ANTHROPIC_OK)) {
			Result r = KeyCheck.check(strict.clientAs(Identity.of("e2e-ssrf").userDID(strict.did())),
				SONNET, ANTHROPIC_KEY, stub.anthropicUrl());
			assertFalse(r.ok(), r.message());
			assertEquals(0, stub.received().size(), "nothing left the machine");
			assertTrue(r.message().contains("private/internal address"), r.message());
		}
	}

	@Test
	void anUnreachableProviderSaysSoQuickly() throws Exception {
		String closed;
		try (StubProvider stub = StubProvider.replying(200, StubProvider.OPENAI_OK)) {
			closed = stub.openAiUrl();
		}
		Result r = KeyCheck.check(client("e2e-unreachable"), GPT, OPENAI_KEY, closed);
		assertEquals(Outcome.UNREACHABLE, r.outcome(), r.message());
		assertTrue(r.millis() < 10_000, "well inside the 10 s target: " + r.millis() + " ms");
	}

	@Test
	void onboardingsStandaloneTestReachesTheProviderToo() throws Exception {
		try (StubProvider stub = StubProvider.replying(200, StubProvider.ANTHROPIC_OK)) {
			Result r = KeyCheck.checkStandalone(SONNET, ANTHROPIC_KEY, stub.anthropicUrl(), LOOPBACK_ALLOWED);
			assertEquals(Outcome.WORKS, r.outcome(), r.message());
			assertEquals(ANTHROPIC_KEY, stub.received().get(0).headers().get("x-api-key"));
		}
	}
}
