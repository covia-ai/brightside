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
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.LocalVenue;
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
		WORKS, NO_KEY, BAD_KEY, NO_CREDIT, RATE_LIMITED, MODEL_UNAVAILABLE, UNREACHABLE, FAILED;

		public boolean ok() {
			return this == WORKS;
		}
	}

	/** The outcome, a sentence for the owner, and how long the call took. */
	public record Result(Outcome outcome, String message, long millis) {
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
		String key = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
		AMap<AString, ACell> input = Maps.of(
			"messages", Vectors.of(Maps.of("role", "user", "content", PROMPT)),
			"maxTokens", CVMLong.create(MAX_TOKENS));
		if (key != null) input = input.assoc(Strings.create("apiKey"), Strings.create(key));
		long start = System.nanoTime();
		try {
			client.run(modelOp, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			long millis = elapsed(start);
			return new Result(Outcome.WORKS, worksMessage(modelOp, millis), millis);
		} catch (TimeoutException e) {
			return failure(modelOp, Outcome.UNREACHABLE,
				"No reply within " + TIMEOUT_SECONDS + " seconds.", elapsed(start));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return failure(modelOp, Outcome.FAILED, "The test was interrupted.", elapsed(start));
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
		Engine engine = Engine.createTemp(null);
		try {
			// The built-in adapters and their v/ catalogue, model operations included;
			// a temporary engine starts without them.
			Engine.addDemoAssets(engine);
			LocalVenue client = LocalVenue.create(engine);
			client.setUser(engine.getDIDString().toString());
			return check(client, modelOp, apiKey);
		} finally {
			engine.close();
		}
	}

	static Result fromError(String modelOp, String error, String key, long millis) {
		String shown = mask(error, key);
		return failure(modelOp, classify(shown), shown, millis);
	}

	private static Result failure(String modelOp, Outcome outcome, String detail, long millis) {
		return new Result(outcome, failureMessage(outcome, modelOp, detail), millis);
	}

	// Most specific first: a 429 that says "insufficient_quota" is no credit,
	// not a rate limit, and a 401 mentioning billing is still a refused key.
	private static final Pattern NO_KEY = Pattern.compile(
		"(api.?key|secret).{0,40}(missing|required|not (set|found|provided|configured))|no api.?key|missing (api.?key|credentials)");
	private static final Pattern NO_CREDIT = Pattern.compile(
		"\\b402\\b|insufficient[_ ](quota|balance|credits?|funds)|credit balance|out of credits?|billing|payment required|exceeded your current quota");
	private static final Pattern BAD_KEY = Pattern.compile(
		"\\b40[13]\\b|unauthori[sz]ed|invalid.{0,12}(api.?key|x-api-key|token|credentials)|incorrect api.?key|api.?key (is )?(not valid|invalid)|authentication|permission denied|forbidden");
	private static final Pattern RATE_LIMITED = Pattern.compile(
		"\\b429\\b|\\b529\\b|rate.?limit|too many requests|overloaded");
	private static final Pattern MODEL_UNAVAILABLE = Pattern.compile(
		"\\b404\\b|model.{0,30}(not found|does not exist|not available|unknown|unsupported)|not_found_error|no such model|unknown model");
	private static final Pattern UNREACHABLE = Pattern.compile(
		"unknownhost|unknown host|connect(ion)? (refused|reset|timed out)|connectexception|timed? ?out|unreachable|no route to host|network is unreachable|failed to connect");

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

	private static String detail(Throwable t) {
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
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
