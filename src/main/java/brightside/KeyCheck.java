package brightside;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import brightside.model.ProviderHints;
import brightside.model.Providers;

/**
 * The <b>Test</b> action beside an API key: one tiny call to the chosen model
 * operation, answered in plain words — it works and how fast, or why not (a key
 * the provider refuses, no credit, rate-limited, a model the key cannot use,
 * the provider out of reach).
 *
 * <p>The call goes through the venue's own {@code v/models/<provider>/<id>}
 * operation, so Brightside holds no provider endpoints or auth rules of its
 * own: what the venue's adapters can call is what this tests. A key typed but
 * not yet saved is passed as the operation's {@code apiKey} input, which the
 * operation declares secret; with none, the operation reads the caller's
 * stored key as a chat would. Onboarding runs before the home venue exists, so
 * it tests on a temporary in-memory engine that is closed straight after
 * ({@link #checkStandalone}).</p>
 *
 * <p>Beside the answer goes a {@link ProviderHints hint} when there's a likely
 * cause worth naming: the key's own shape (a stray space, another provider's
 * key, an admin token) or, after a failure, where on that provider's site to
 * look next.</p>
 *
 * <p>The key never appears in a result: any copy of it in a provider's error
 * text is masked before the text is shown.</p>
 */
public final class KeyCheck {

	/** How long a test waits for the model's reply. */
	static final long TIMEOUT_SECONDS = 20;

	/** Enough room for a reply even on models that always think first. */
	static final long MAX_TOKENS = 256;

	static final String PROMPT = "Reply with the single word OK.";

	/** What the test found. */
	public enum Outcome {
		WORKS, NO_KEY, BAD_KEY, NO_CREDIT, RATE_LIMITED, MODEL_UNAVAILABLE, UNREACHABLE, PROVIDER_ERROR, FAILED;

		public boolean ok() {
			return this == WORKS;
		}
	}

	/** The outcome, a sentence for the owner, a likely cause or next step (or null), and how long it took. */
	public record Result(Outcome outcome, String message, String hint, long millis) {
		public boolean ok() {
			return outcome.ok();
		}
	}

	private KeyCheck() {
	}

	/**
	 * Tests {@code modelOp} through {@code client}, as the client's principal.
	 * {@code apiKey} is a key typed but not saved, or null to use the stored one.
	 */
	public static Result check(Venue client, String modelOp, String apiKey) {
		return check(client, modelOp, apiKey, null);
	}

	/** As {@link #check(Venue, String, String)}, against {@code url} instead of the provider's own endpoint. */
	static Result check(Venue client, String modelOp, String apiKey, String url) {
		String key = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
		AMap<AString, ACell> input = Maps.of(
			"messages", Vectors.of(Maps.of("role", "user", "content", PROMPT)),
			"maxTokens", CVMLong.create(MAX_TOKENS));
		if (key != null) input = input.assoc(Strings.create("apiKey"), Strings.create(key));
		if (url != null) input = input.assoc(Strings.create("url"), Strings.create(url));
		long start = System.nanoTime();
		try {
			ACell out = client.run(modelOp, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			long millis = elapsed(start);
			// An operation may refuse before calling anyone (a URL the venue's
			// SSRF guard blocks, say) by returning a failure status as its
			// result rather than failing the job.
			String refused = inBandFailure(out);
			if (refused != null) return fromError(modelOp, refused, key, millis);
			return new Result(Outcome.WORKS, worksMessage(modelOp, millis), null, millis);
		} catch (TimeoutException e) {
			return failure(modelOp, Outcome.UNREACHABLE,
				"No reply within " + TIMEOUT_SECONDS + " seconds.", key, elapsed(start));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return failure(modelOp, Outcome.FAILED, "The test was interrupted.", key, elapsed(start));
		} catch (ExecutionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			return fromError(modelOp, detail(cause), key, elapsed(start));
		} catch (RuntimeException e) {
			return fromError(modelOp, detail(e), key, elapsed(start));
		}
	}

	/**
	 * As {@link #check}, on a temporary in-memory engine — for onboarding, before
	 * the home venue exists. Nothing is stored and the engine is closed after.
	 */
	public static Result checkStandalone(String modelOp, String apiKey) {
		return checkStandalone(modelOp, apiKey, null, null);
	}

	/** As {@link #checkStandalone(String, String)}, against {@code url}, on an engine with {@code config}. */
	static Result checkStandalone(String modelOp, String apiKey, String url, AMap<AString, ACell> config) {
		Engine engine = Engine.createTemp(config);
		try {
			// The built-in adapters and their v/ catalogue, model operations included;
			// a temporary engine starts without them.
			Engine.addDemoAssets(engine);
			LocalVenue client = LocalVenue.create(engine);
			client.setUser(engine.getDIDString().toString());
			return check(client, modelOp, apiKey, url);
		} finally {
			engine.close();
		}
	}

	/** The message of a {@code Status} failure returned as a result; null for a reply. */
	static String inBandFailure(ACell out) {
		AString status = RT.ensureString(RT.getIn(out, "status"));
		if (status == null) return null;
		String s = status.toString();
		if (!s.equals("FAILED") && !s.equals("REJECTED") && !s.equals("CANCELLED")) return null;
		AString message = RT.ensureString(RT.getIn(out, "message"));
		return (message != null) ? message.toString() : s;
	}

	static Result fromError(String modelOp, String error, String key, long millis) {
		String shown = mask(error, key);
		return failure(modelOp, classify(shown), shown, key, millis);
	}

	private static Result failure(String modelOp, Outcome outcome, String detail, String key, long millis) {
		return new Result(outcome, failureMessage(outcome, modelOp, detail), hint(modelOp, outcome, detail, key), millis);
	}

	/**
	 * The key's own shape first — it explains a refusal better than any page
	 * does — then the provider's next step for this outcome.
	 */
	static String hint(String modelOp, Outcome outcome, String detail, String key) {
		String providerId = Providers.providerOf(modelOp);
		String aboutKey = ProviderHints.forKey(providerId, key);
		if (aboutKey != null && (outcome == Outcome.BAD_KEY || outcome == Outcome.FAILED || outcome == Outcome.NO_KEY)) {
			return mask(aboutKey, key);
		}
		ProviderHints.Problem problem = switch (outcome) {
			case NO_KEY -> ProviderHints.Problem.NO_KEY;
			case BAD_KEY -> ProviderHints.Problem.BAD_KEY;
			case NO_CREDIT -> ProviderHints.Problem.NO_CREDIT;
			case RATE_LIMITED -> ProviderHints.Problem.RATE_LIMITED;
			case MODEL_UNAVAILABLE -> ProviderHints.Problem.MODEL_UNAVAILABLE;
			case UNREACHABLE -> ProviderHints.Problem.UNREACHABLE;
			case WORKS, FAILED, PROVIDER_ERROR -> null;
		};
		String next = (problem == null) ? null : ProviderHints.forProblem(providerId, problem, detail);
		return (next == null) ? null : mask(next, key);
	}

	// Most specific first: a 429 that says "insufficient_quota" is no credit,
	// not a rate limit, and a 401 mentioning billing is still a refused key.
	// The model client's exception names count too: with no network the
	// venue may report nothing but "UnresolvedModelServerException".
	private static final Pattern NO_KEY = Pattern.compile(
		"(api.?key|secret).{0,40}(missing|required|not (set|found|provided|configured))|no api.?key|missing (api.?key|credentials)");
	private static final Pattern NO_CREDIT = Pattern.compile(
		"\\b402\\b|insufficient[_ ](quota|balance|credits?|funds)|credit balance|out of credits?|billing|payment required|exceeded your current quota");
	private static final Pattern BAD_KEY = Pattern.compile(
		"\\b40[13]\\b|authenticationexception|unauthori[sz]ed|invalid.{0,12}(api.?key|x-api-key|token|credentials)|incorrect api.?key|api.?key (is )?(not valid|invalid)|authentication|permission denied|forbidden");
	private static final Pattern RATE_LIMITED = Pattern.compile(
		"\\b429\\b|\\b529\\b|ratelimitexception|rate.?limit|too many requests|overloaded");
	private static final Pattern MODEL_UNAVAILABLE = Pattern.compile(
		"\\b404\\b|modelnotfoundexception|model.{0,30}(not found|does not exist|not available|unknown|unsupported)|not_found_error|no such model|unknown model");
	private static final Pattern UNREACHABLE = Pattern.compile(
		"unknownhost|unknown host|unresolvedmodelserver|(cannot|could not|unable to) resolve (host|hostname|address)|unresolvedaddress|connect(ion)? (refused|reset|timed out)|connectexception|timeoutexception|timed? ?out|unreachable|no route to host|network is unreachable|failed to connect");
	private static final Pattern PROVIDER_ERROR = Pattern.compile(
		"\\b50[0-4]\\b|internalserverexception|internal server error|service unavailable|bad gateway");

	/** Which outcome a provider's error text describes; {@link Outcome#FAILED} when unclear. */
	static Outcome classify(String error) {
		if (error == null) return Outcome.FAILED;
		String e = error.toLowerCase(Locale.ROOT);
		if (NO_KEY.matcher(e).find()) return Outcome.NO_KEY;
		if (NO_CREDIT.matcher(e).find()) return Outcome.NO_CREDIT;
		if (BAD_KEY.matcher(e).find()) return Outcome.BAD_KEY;
		if (RATE_LIMITED.matcher(e).find()) return Outcome.RATE_LIMITED;
		if (MODEL_UNAVAILABLE.matcher(e).find()) return Outcome.MODEL_UNAVAILABLE;
		if (UNREACHABLE.matcher(e).find()) return Outcome.UNREACHABLE;
		if (PROVIDER_ERROR.matcher(e).find()) return Outcome.PROVIDER_ERROR;
		return Outcome.FAILED;
	}

	private static final Pattern KEY_SHAPED = Pattern.compile("\\b(sk|xai|gsk|AIza)[-_A-Za-z0-9]{12,}");

	/** {@code text} with {@code key}, and anything shaped like a provider key, masked. */
	static String mask(String text, String key) {
		if (text == null) return "";
		String out = text;
		if (key != null && key.length() >= 4) out = out.replace(key, "•••");
		return KEY_SHAPED.matcher(out).replaceAll("•••");
	}

	private static String worksMessage(String modelOp, long millis) {
		return "Works: " + modelLabel(modelOp) + " replied in " + seconds(millis) + ".";
	}

	private static String failureMessage(Outcome outcome, String modelOp, String detail) {
		String provider = providerLabel(modelOp);
		boolean local = "ollama".equals(Providers.providerOf(modelOp));
		return switch (outcome) {
			case NO_KEY -> "No key for " + provider + " yet. Paste one, then test it.";
			case BAD_KEY -> provider + " didn't accept this key. Check it was copied in full.";
			case NO_CREDIT -> "The key works, but the " + provider + " account has no credit or billing set up.";
			case RATE_LIMITED -> provider + " is busy or limiting this key. Try again in a minute.";
			case MODEL_UNAVAILABLE -> "The key works, but " + modelLabel(modelOp) + " isn't available to it. Try another model.";
			case UNREACHABLE -> local
				? "Couldn't reach Ollama on this computer. Is it running?"
				: "Couldn't reach " + provider + ". Check your internet connection.";
			case PROVIDER_ERROR -> provider + " had a problem on its side. This isn't about your key; try again shortly.";
			case FAILED, WORKS -> "The test call failed: " + shorten(detail);
		};
	}

	private static String modelLabel(String modelOp) {
		String providerId = Providers.providerOf(modelOp);
		Providers.Provider p = Providers.byId(providerId);
		String prefix = "v/models/" + providerId + "/";
		String modelId = modelOp.startsWith(prefix) ? modelOp.substring(prefix.length()) : modelOp;
		if (p != null) {
			for (Providers.Model m : p.models()) {
				if (m.id().equals(modelId)) return m.label();
			}
		}
		return modelId;
	}

	private static String providerLabel(String modelOp) {
		Providers.Provider p = Providers.byId(Providers.providerOf(modelOp));
		return (p != null) ? p.label() : "The provider";
	}

	/**
	 * Everything the failure says, outermost first: each cause's type and
	 * message (the type can be all there is, or say more than the message:
	 * an UnknownHostException's message is just the host).
	 */
	static String detail(Throwable t) {
		java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
		for (Throwable c = t; c != null && parts.size() < 6; c = (c.getCause() == c) ? null : c.getCause()) {
			String m = c.getMessage();
			String type = c.getClass().getSimpleName();
			parts.add((m == null || m.isBlank()) ? type : (m.contains(type) ? m : type + ": " + m));
		}
		return String.join(" — ", parts);
	}

	private static String shorten(String s) {
		String one = (s == null) ? "" : s.replaceAll("\\s+", " ").trim();
		return (one.length() > 160) ? one.substring(0, 157) + "…" : one;
	}

	private static String seconds(long millis) {
		return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
	}

	private static long elapsed(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}
}
