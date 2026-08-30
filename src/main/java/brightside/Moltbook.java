package brightside;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The owner's assistant on <a href="https://www.moltbook.com">Moltbook</a>, the
 * social network for AI agents: one agent account, registered by Brightside
 * and claimed by the owner (email, then a tweet) on Moltbook's claim page.
 *
 * <p>Brightside keeps the API key in the vault (as {@link #KEY_SECRET},
 * provisioned into the venue's secrets at launch) and in the owner's own
 * encrypted secret store on the venue, and remembers the claim page under the
 * owner's workspace ({@link #RECORD_PATH}) until it has been used. The
 * assistant takes part through {@link MoltbookAdapter}'s operations, which
 * resolve the key inside the venue — the model never composes a request or
 * sees the credential. Nothing is written to {@code config.json}.
 *
 * <p>This class is the HTTP client both sides share: the app's registration
 * and status reads, and the adapter's calls on the agent's behalf.
 */
public final class Moltbook {

	/** The vault/secret name of the API key. */
	public static final String KEY_SECRET = "MOLTBOOK_API_KEY";
	/** Always with {@code www}: the bare domain redirects and drops the credential. */
	public static final String SITE = "https://www.moltbook.com";
	public static final String API = SITE + "/api/v1";
	/** The owner's workspace record: the agent's name and, until claimed, the claim page. */
	public static final String RECORD_PATH = "w/moltbook";
	/** The owner's dashboard, where a lost key is rotated. */
	public static final String OWNER_LOGIN = SITE + "/login";

	private static final String USER_AGENT = "Brightside/1.0 (+https://github.com/covia-ai/brightside)";
	private static final String OP_SECRET_SET = "v/ops/secret/set";
	private static final String OP_WRITE = "v/ops/covia/write";
	private static final String OP_DELETE = "v/ops/covia/delete";
	private static final long TIMEOUT_SECONDS = 30;

	private static final AString K_NAME = Strings.intern("name");
	private static final AString K_CLAIM_URL = Strings.intern("claimUrl");
	private static final AString K_VERIFICATION = Strings.intern("verificationCode");
	private static final AString K_REGISTERED = Strings.intern("registered");
	private static final AString K_SUCCESS = Strings.intern("success");

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** What registering returns: the key to keep, and the page the owner claims the agent on. */
	public record Registration(String apiKey, String claimUrl, String verificationCode) {
	}

	/**
	 * The account as Brightside knows it. {@code status} is Moltbook's
	 * {@code pending_claim} or {@code claimed}; {@code error} names a failure to
	 * reach Moltbook, in which case the rest is what the record remembers.
	 */
	public record Account(String name, String status, String claimUrl, String verificationCode,
			long karma, long followers, long posts, String error) {

		public boolean claimed() {
			return "claimed".equals(status);
		}

		public boolean pending() {
			return "pending_claim".equals(status);
		}
	}

	private Moltbook() {
	}

	// ------------------------------------------------------------------
	// Moltbook's API
	// ------------------------------------------------------------------

	/**
	 * One call to Moltbook: {@code path} is relative to the API root, with any
	 * query string already on it; {@code body} (a map) is sent as JSON; the key
	 * is sent as the bearer, or nothing when null (registration). Completes
	 * with the parsed JSON response, or fails with Moltbook's own error and
	 * hint — a non-2xx status, or a {@code success: false} body.
	 */
	public static CompletableFuture<ACell> call(String method, String path, String apiKey, ACell body) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
			.timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json");
		if (apiKey != null) b.header("Authorization", "Bearer " + apiKey.trim());
		if (body != null) {
			b.header("Content-Type", "application/json");
			b.method(method, HttpRequest.BodyPublishers.ofString(JSON.printPretty(body).toString()));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int status = response.statusCode();
			String text = response.body();
			if (status < 200 || status >= 300) throw new CompletionException(new IOException(errorOf(status, text)));
			ACell parsed;
			try {
				parsed = parse(text);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
			if (CVMBool.FALSE.equals(RT.getIn(parsed, K_SUCCESS))) {
				throw new CompletionException(new IOException(errorOf(status, text)));
			}
			return parsed;
		});
	}

	/** {@link #call}, waited for; the failure is Moltbook's message. */
	static ACell callNow(String method, String path, String apiKey, ACell body) throws IOException {
		try {
			return call(method, path, apiKey, body).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			throw (cause instanceof IOException io) ? io : new IOException(cause.getMessage(), cause);
		} catch (TimeoutException e) {
			throw new IOException("Moltbook did not answer in time");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while talking to Moltbook");
		}
	}

	/** Registers a new agent. The key comes back exactly once. */
	public static Registration register(String name, String description) throws IOException {
		AMap<AString, ACell> body = Maps.of("name", name.trim(), "description", (description != null) ? description.trim() : "");
		return parseRegistration(callNow("POST", "/agents/register", null, body));
	}

	/**
	 * The account behind {@code apiKey}: its profile and claim status, with the
	 * claim page from {@code record} while it is still pending. Throws if the
	 * key is refused or Moltbook cannot be reached.
	 */
	public static Account lookup(String apiKey, ACell record) throws IOException {
		ACell me = callNow("GET", "/agents/me", apiKey, null);
		ACell status;
		try {
			status = callNow("GET", "/agents/status", apiKey, null);
		} catch (IOException e) {
			status = null;
		}
		return parseAccount(me, status, record);
	}

	/** {@code register}'s response: {@code agent.api_key}, {@code agent.claim_url}, {@code agent.verification_code}. */
	static Registration parseRegistration(ACell parsed) throws IOException {
		String key = str(RT.getIn(parsed, "agent", "api_key"));
		if (key == null || key.isBlank()) {
			throw new IOException("Moltbook returned no API key: " + errorOf(200, JSON.printPretty(parsed).toString()));
		}
		return new Registration(key, str(RT.getIn(parsed, "agent", "claim_url")), str(RT.getIn(parsed, "agent", "verification_code")));
	}

	static Registration parseRegistration(String json) throws IOException {
		return parseRegistration(parse(json));
	}

	/**
	 * The account from {@code /agents/me} (and {@code /agents/status} when it
	 * answered), keeping the claim page the record remembers until claimed.
	 */
	static Account parseAccount(ACell me, ACell statusResponse, ACell record) {
		ACell agent = RT.getIn(me, "agent");
		if (agent == null) agent = me;
		String name = str(RT.getIn(agent, "name"));
		String status = (statusResponse != null) ? str(RT.getIn(statusResponse, "status")) : null;
		if (status == null) {
			ACell claimed = RT.getIn(agent, "is_claimed");
			status = (claimed instanceof CVMBool b && b.booleanValue()) ? "claimed" : "pending_claim";
		}
		boolean pending = "pending_claim".equals(status);
		return new Account(
			(name != null) ? name : str(RT.getIn(record, K_NAME)),
			status,
			pending ? str(RT.getIn(record, K_CLAIM_URL)) : null,
			pending ? str(RT.getIn(record, K_VERIFICATION)) : null,
			lng(RT.getIn(agent, "karma")),
			lng(RT.getIn(agent, "follower_count")),
			lng(RT.getIn(agent, "posts_count")),
			null);
	}

	static Account parseAccount(String meJson, String statusJson, ACell record) throws IOException {
		return parseAccount(parse(meJson), (statusJson != null) ? parse(statusJson) : null, record);
	}

	/** What the record alone can say, when Moltbook could not be asked. */
	public static Account fromRecord(ACell record, String error) {
		return new Account(str(RT.getIn(record, K_NAME)), null, str(RT.getIn(record, K_CLAIM_URL)),
			str(RT.getIn(record, K_VERIFICATION)), 0, 0, 0, error);
	}

	/** Moltbook's {@code error} and {@code hint} from a failed response, else the status. */
	public static String errorOf(int status, String body) {
		String error = null;
		String hint = null;
		try {
			ACell parsed = parse(body);
			error = str(RT.getIn(parsed, "error"));
			if (error == null) error = str(RT.getIn(parsed, "message"));
			hint = str(RT.getIn(parsed, "hint"));
		} catch (IOException ignored) {
			// not JSON; fall through to the status
		}
		if (error == null || error.isBlank()) return "Moltbook answered HTTP " + status;
		return (hint != null && !hint.isBlank()) ? error + " (" + hint + ")" : error;
	}

	// ------------------------------------------------------------------
	// What the venue keeps for the owner
	// ------------------------------------------------------------------

	/** Stores the key in the owner's encrypted secret store, where the adapter resolves it. */
	public static void storeKey(Venue user, String apiKey) throws Exception {
		run(user, OP_SECRET_SET, Maps.of("name", KEY_SECRET, "value", apiKey.trim()));
	}

	/** Removes the key from the owner's secret store. */
	public static void forgetKey(Venue user) throws Exception {
		run(user, OP_DELETE, Maps.of("path", "s/" + KEY_SECRET));
	}

	/** Remembers the agent's name and, for a fresh registration, its claim page. */
	public static void saveRecord(Venue user, String name, String claimUrl, String verificationCode) throws Exception {
		run(user, OP_WRITE, Maps.of("path", RECORD_PATH, "value", record(name, claimUrl, verificationCode)));
	}

	/** The workspace record: the agent's name, when it was registered, and the claim page while it matters. */
	public static AMap<AString, ACell> record(String name, String claimUrl, String verificationCode) {
		AMap<AString, ACell> record = Maps.of(K_NAME, name, K_REGISTERED, CVMLong.create(Utils.getCurrentTimestamp()));
		if (claimUrl != null) record = record.assoc(K_CLAIM_URL, Strings.create(claimUrl));
		if (verificationCode != null) record = record.assoc(K_VERIFICATION, Strings.create(verificationCode));
		return record;
	}

	public static void clearRecord(Venue user) throws Exception {
		run(user, OP_DELETE, Maps.of("path", RECORD_PATH));
	}

	// ------------------------------------------------------------------
	// Plumbing
	// ------------------------------------------------------------------

	private static ACell parse(String json) throws IOException {
		try {
			return JSON.parseJSON5(json);
		} catch (Exception e) {
			throw new IOException("Moltbook answered with something other than JSON");
		}
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	private static long lng(ACell cell) {
		if (cell instanceof CVMLong l) return l.longValue();
		if (cell instanceof CVMDouble d) return Math.round(d.doubleValue());
		return 0L;
	}
}
