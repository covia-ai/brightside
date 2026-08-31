package brightside;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.CoviaAdapter;
import covia.api.Abilities;
import covia.venue.Engine.UserPathTarget;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moltbook as operations on the venue — {@code v/ops/moltbook/<op>} — so the
 * assistant takes part in the social network for AI agents through a typed
 * tool per action rather than by composing HTTP requests. Each operation
 * resolves the owner's API key ({@code s/MOLTBOOK_API_KEY}) from the caller's
 * secret store inside the venue, calls Moltbook, and returns Moltbook's JSON
 * as structured data; the model never handles the credential and cannot send
 * it anywhere else. Input is validated before any request is made.
 *
 * <p>Registered on the embedded engine at launch ({@link EmbeddedVenue}); the
 * account itself is set up in Settings → Integrations ({@link Moltbook}), and
 * the shipped {@code moltbook} skill grants these operations.
 */
public class MoltbookAdapter extends AAdapter {

	/** The secret reference the operations resolve for the caller. */
	public static final String SECRET_REF = "s/" + Moltbook.KEY_SECRET;
	/** The failure an operation reports until the owner has connected an account. */
	public static final String NOT_SET_UP = "Moltbook is not set up: register an account for the owner with the "
		+ "moltbook-setup skill, or the owner connects one in Settings → Integrations → Moltbook";

	private static final Logger log = LoggerFactory.getLogger(MoltbookAdapter.class);

	/** Every operation, as installed under {@code v/ops/moltbook/}. */
	public static final List<String> OPS = List.of(
		"account", "register",
		"home", "feed", "read-post", "create-post", "comment", "vote", "search",
		"profile", "update-profile", "submolts", "subscribe", "follow", "create-submolt",
		"verify", "notifications-read", "delete-post");

	private static final AString K_CONFIGURED = Strings.intern("configured");
	private static final AString K_REGISTERED = Strings.intern("registered");
	private static final AString K_STATUS = Strings.intern("status");
	private static final AString K_CLAIMED = Strings.intern("claimed");
	private static final AString K_CLAIM_URL = Strings.intern("claim_url");
	private static final AString K_VERIFICATION_CODE_OUT = Strings.intern("verification_code");
	private static final AString K_KARMA = Strings.intern("karma");
	private static final AString K_FOLLOWERS = Strings.intern("followers");
	private static final AString K_POSTS = Strings.intern("posts");
	private static final AString K_HOW = Strings.intern("how");
	private static final AString K_NEXT = Strings.intern("next");

	private static final AString K_SCOPE = Strings.intern("scope");
	private static final AString K_SUBMOLT = Strings.intern("submolt");
	private static final AString K_SORT = Strings.intern("sort");
	private static final AString K_LIMIT = Strings.intern("limit");
	private static final AString K_CURSOR = Strings.intern("cursor");
	private static final AString K_ID = Strings.intern("id");
	private static final AString K_TITLE = Strings.intern("title");
	private static final AString K_CONTENT = Strings.intern("content");
	private static final AString K_URL = Strings.intern("url");
	private static final AString K_POST_ID = Strings.intern("post_id");
	private static final AString K_PARENT_ID = Strings.intern("parent_id");
	private static final AString K_TARGET = Strings.intern("target");
	private static final AString K_DIRECTION = Strings.intern("direction");
	private static final AString K_Q = Strings.intern("q");
	private static final AString K_TYPE = Strings.intern("type");
	private static final AString K_NAME = Strings.intern("name");
	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_DISPLAY_NAME = Strings.intern("display_name");
	private static final AString K_ALLOW_CRYPTO = Strings.intern("allow_crypto");
	private static final AString K_UNSUBSCRIBE = Strings.intern("unsubscribe");
	private static final AString K_UNFOLLOW = Strings.intern("unfollow");
	private static final AString K_VERIFICATION_CODE = Strings.intern("verification_code");
	private static final AString K_ANSWER = Strings.intern("answer");
	private static final AString K_POST = Strings.intern("post");
	private static final AString K_COMMENTS = Strings.intern("comments");

	private static final int MAX_TITLE = 300;
	private static final int MAX_CONTENT = 40_000;
	private static final int MAX_QUERY = 500;
	private static final int MAX_ID = 200;

	@Override
	public String getName() {
		return "moltbook";
	}

	@Override
	public String getDescription() {
		return "Moltbook, the social network for AI agents, as typed operations for the owner's "
			+ "assistant: check in, read the feed, post, comment, vote, search, follow and join communities.";
	}

	@Override
	protected void installAssets() {
		for (String op : OPS) installAsset("moltbook/" + op, "/adapters/moltbook/" + op + ".json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		long started = System.nanoTime();
		CompletableFuture<ACell> result;
		try {
			result = dispatch(ctx, op, input);
		} catch (Exception e) {
			result = CompletableFuture.failedFuture(e);
		}
		// Outcome and timing only — never the input or what Moltbook said. This
		// is what makes an agent's Moltbook turn readable in the Brightside log.
		return result.whenComplete((value, failure) -> {
			long ms = (System.nanoTime() - started) / 1_000_000;
			if (failure == null) log.info("moltbook:{} ok in {} ms", op, ms);
			else log.info("moltbook:{} failed in {} ms: {}", op, ms, rootMessage(failure));
		});
	}

	private CompletableFuture<ACell> dispatch(RequestContext ctx, String op, ACell input) {
		try {
			// The two that make sense before there is an account.
			if ("account".equals(op)) return account(ctx);
			if ("register".equals(op)) return register(ctx, input);
			String key = apiKey(ctx);
			return switch (op) {
				case "home" -> get(key, "/home");
				case "feed" -> feed(key, input);
				case "read-post" -> readPost(key, input);
				case "create-post" -> createPost(key, input);
				case "comment" -> comment(key, input);
				case "vote" -> vote(key, input);
				case "search" -> search(key, input);
				case "profile" -> profile(key, input);
				case "update-profile" -> Moltbook.call("PATCH", "/agents/me", key,
					Maps.of(K_DESCRIPTION, requiredText(input, K_DESCRIPTION, 2000)));
				case "submolts" -> submolts(key, input);
				case "subscribe" -> toggle(key, "/submolts/" + segment(requiredText(input, K_SUBMOLT, MAX_ID)) + "/subscribe",
					flag(input, K_UNSUBSCRIBE));
				case "follow" -> toggle(key, "/agents/" + segment(requiredText(input, K_NAME, MAX_ID)) + "/follow",
					flag(input, K_UNFOLLOW));
				case "create-submolt" -> createSubmolt(key, input);
				case "verify" -> Moltbook.call("POST", "/verify", key, Maps.of(
					K_VERIFICATION_CODE, requiredText(input, K_VERIFICATION_CODE, MAX_ID),
					K_ANSWER, requiredText(input, K_ANSWER, 64)));
				case "notifications-read" -> notificationsRead(key, input);
				case "delete-post" -> Moltbook.call("DELETE", "/posts/" + segment(requiredText(input, K_ID, MAX_ID)), key, null);
				default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown moltbook operation: " + op));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** The innermost message of a failure, unwrapping the async wrappers. */
	private static String rootMessage(Throwable failure) {
		Throwable t = failure;
		while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
				&& t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
	}

	/** The caller's key, from their own secret store (or the venue's launch-provisioned secrets). */
	private String apiKey(RequestContext ctx) {
		String key = existingKey(ctx);
		if (key == null) throw new IllegalStateException(NOT_SET_UP);
		return key;
	}

	private String existingKey(RequestContext ctx) {
		if (engine == null || ctx == null) throw new IllegalStateException("moltbook operations need a request context");
		String key = engine.resolveSecret(SECRET_REF, ctx);
		return (key == null || key.isBlank()) ? null : key;
	}

	// ------------------------------------------------------------------
	// Setting up
	// ------------------------------------------------------------------

	/**
	 * The state of the owner's account: not configured (and how to set one up),
	 * or its name, claim status and — while the owner still has to claim it —
	 * the claim page from the record.
	 */
	private CompletableFuture<ACell> account(RequestContext ctx) {
		String key = existingKey(ctx);
		if (key == null) {
			return CompletableFuture.completedFuture(Maps.of(K_CONFIGURED, CVMBool.FALSE,
				K_HOW, "Not set up: agree a name with the owner and call register, or the owner connects "
					+ "an account in Settings → Integrations → Moltbook"));
		}
		ACell record = record(ctx);
		return Moltbook.call("GET", "/agents/me", key, null).thenCompose(me ->
			Moltbook.call("GET", "/agents/status", key, null)
				.handle((status, failure) -> Moltbook.parseAccount(me, (failure == null) ? status : null, record))
				.thenApply(a -> {
					AMap<AString, ACell> out = Maps.of(
						K_CONFIGURED, CVMBool.TRUE,
						K_NAME, Strings.create(a.name() != null ? a.name() : ""),
						K_STATUS, Strings.create(a.status()),
						K_CLAIMED, CVMBool.of(a.claimed()),
						K_KARMA, CVMLong.create(a.karma()),
						K_FOLLOWERS, CVMLong.create(a.followers()),
						K_POSTS, CVMLong.create(a.posts()));
					if (a.claimUrl() != null) out = out.assoc(K_CLAIM_URL, Strings.create(a.claimUrl()));
					if (a.verificationCode() != null) out = out.assoc(K_VERIFICATION_CODE_OUT, Strings.create(a.verificationCode()));
					return out;
				}));
	}

	/**
	 * Registers the owner's agent on Moltbook. The key goes straight into the
	 * caller's secret store — it is never returned — and the claim page into
	 * the record; what comes back is what the owner needs: the claim link and
	 * verification code.
	 */
	private CompletableFuture<ACell> register(RequestContext ctx, ACell input) {
		String name = requiredText(input, K_NAME, 64);
		String description = optionalText(input, K_DESCRIPTION, 2000);
		if (existingKey(ctx) != null) {
			throw new IllegalStateException("Moltbook is already connected; the owner can forget it in "
				+ "Settings → Integrations → Moltbook before registering another account");
		}
		// Writing a secret needs the same authority secret:set demands — checked
		// before Moltbook is asked to create anything.
		engine.requireAuthority(ctx, Strings.create(SECRET_REF), Abilities.SECRET_WRITE);
		AMap<AString, ACell> body = Maps.of(K_NAME, Strings.create(name),
			K_DESCRIPTION, Strings.create(description != null ? description : ""));
		return Moltbook.call("POST", "/agents/register", null, body).thenApply(response -> {
			Moltbook.Registration r;
			try {
				r = Moltbook.parseRegistration(response);
			} catch (java.io.IOException e) {
				throw new CompletionException(e);
			}
			User user = engine.getVenueState().users().ensure(ctx.getUserDID());
			user.secrets().store(Moltbook.KEY_SECRET, r.apiKey(), SecretStore.deriveKey(engine.getKeyPair()));
			writeRecord(ctx, Moltbook.record(name, r.claimUrl(), r.verificationCode()));
			AMap<AString, ACell> out = Maps.of(
				K_REGISTERED, CVMBool.TRUE,
				K_NAME, Strings.create(name),
				K_STATUS, Strings.create("pending_claim"),
				K_NEXT, Strings.create("Give the owner the claim link (and the verification code shown there): they verify an "
					+ "email, then post a tweet, and the account is live. Until then Moltbook may refuse actions."));
			if (r.claimUrl() != null) out = out.assoc(K_CLAIM_URL, Strings.create(r.claimUrl()));
			if (r.verificationCode() != null) out = out.assoc(K_VERIFICATION_CODE_OUT, Strings.create(r.verificationCode()));
			return out;
		});
	}

	private ACell record(RequestContext ctx) {
		try {
			return engine.resolvePath(Strings.create(Moltbook.RECORD_PATH), ctx);
		} catch (Exception e) {
			return null;
		}
	}

	private void writeRecord(RequestContext ctx, AMap<AString, ACell> record) {
		UserPathTarget target = engine.requireUserPath(ctx, Strings.create(Moltbook.RECORD_PATH), Capability.CRUD_WRITE, true);
		CoviaAdapter.writePathToCursor(target.user().cursor(), target.pathKeys(), record);
	}

	// ------------------------------------------------------------------
	// The operations
	// ------------------------------------------------------------------

	private static CompletableFuture<ACell> feed(String key, ACell input) {
		String scope = optionalText(input, K_SCOPE, 32);
		if (scope == null) scope = "feed";
		Map<String, String> query = new LinkedHashMap<>();
		String path;
		switch (scope) {
			case "feed" -> path = "/feed";
			case "following" -> {
				path = "/feed";
				query.put("filter", "following");
			}
			case "all" -> path = "/posts";
			case "submolt" -> {
				path = "/posts";
				query.put("submolt", requiredText(input, K_SUBMOLT, MAX_ID));
			}
			default -> throw new IllegalArgumentException("scope must be feed, following, all or submolt");
		}
		putOptional(query, "sort", optionalText(input, K_SORT, 16));
		putOptional(query, "limit", optionalCount(input, K_LIMIT, 100));
		putOptional(query, "cursor", optionalText(input, K_CURSOR, 500));
		return get(key, path + query(query));
	}

	/** The post and its comments together, as a reader wants them. */
	private static CompletableFuture<ACell> readPost(String key, ACell input) {
		String id = segment(requiredText(input, K_ID, MAX_ID));
		Map<String, String> query = new LinkedHashMap<>();
		putOptional(query, "sort", optionalText(input, K_SORT, 16));
		putOptional(query, "limit", optionalCount(input, K_LIMIT, 100));
		putOptional(query, "cursor", optionalText(input, K_CURSOR, 500));
		return get(key, "/posts/" + id).thenCompose(post ->
			get(key, "/posts/" + id + "/comments" + query(query)).thenApply(comments ->
				Maps.of(K_POST, post, K_COMMENTS, comments)));
	}

	private static CompletableFuture<ACell> createPost(String key, ACell input) {
		AMap<AString, ACell> body = Maps.of(
			Strings.create("submolt_name"), Strings.create(requiredText(input, K_SUBMOLT, MAX_ID)),
			K_TITLE, Strings.create(requiredText(input, K_TITLE, MAX_TITLE)));
		String content = optionalText(input, K_CONTENT, MAX_CONTENT);
		String url = optionalText(input, K_URL, 2000);
		if (content == null && url == null) throw new IllegalArgumentException("A post needs content or a url");
		if (content != null) body = body.assoc(K_CONTENT, Strings.create(content));
		if (url != null) body = body.assoc(K_URL, Strings.create(url)).assoc(K_TYPE, Strings.create("link"));
		return Moltbook.call("POST", "/posts", key, body);
	}

	private static CompletableFuture<ACell> comment(String key, ACell input) {
		String postId = segment(requiredText(input, K_POST_ID, MAX_ID));
		AMap<AString, ACell> body = Maps.of(K_CONTENT, Strings.create(requiredText(input, K_CONTENT, MAX_CONTENT)));
		String parent = optionalText(input, K_PARENT_ID, MAX_ID);
		if (parent != null) body = body.assoc(K_PARENT_ID, Strings.create(parent));
		return Moltbook.call("POST", "/posts/" + postId + "/comments", key, body);
	}

	private static CompletableFuture<ACell> vote(String key, ACell input) {
		String target = requiredText(input, K_TARGET, 16);
		String id = segment(requiredText(input, K_ID, MAX_ID));
		String direction = optionalText(input, K_DIRECTION, 8);
		boolean down = "down".equals(direction);
		if (direction != null && !down && !"up".equals(direction)) {
			throw new IllegalArgumentException("direction must be up or down");
		}
		String path = switch (target) {
			case "post" -> "/posts/" + id + (down ? "/downvote" : "/upvote");
			case "comment" -> {
				if (down) throw new IllegalArgumentException("Comments can only be upvoted");
				yield "/comments/" + id + "/upvote";
			}
			default -> throw new IllegalArgumentException("target must be post or comment");
		};
		return Moltbook.call("POST", path, key, null);
	}

	private static CompletableFuture<ACell> search(String key, ACell input) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("q", requiredText(input, K_Q, MAX_QUERY));
		String type = optionalText(input, K_TYPE, 16);
		if (type != null && !List.of("posts", "comments", "all").contains(type)) {
			throw new IllegalArgumentException("type must be posts, comments or all");
		}
		putOptional(query, "type", type);
		putOptional(query, "limit", optionalCount(input, K_LIMIT, 50));
		putOptional(query, "cursor", optionalText(input, K_CURSOR, 500));
		return get(key, "/search" + query(query));
	}

	private static CompletableFuture<ACell> profile(String key, ACell input) {
		String name = optionalText(input, K_NAME, MAX_ID);
		if (name == null) return get(key, "/agents/me");
		return get(key, "/agents/profile" + query(Map.of("name", name)));
	}

	private static CompletableFuture<ACell> submolts(String key, ACell input) {
		String name = optionalText(input, K_NAME, MAX_ID);
		return get(key, (name == null) ? "/submolts" : "/submolts/" + segment(name));
	}

	private static CompletableFuture<ACell> createSubmolt(String key, ACell input) {
		AMap<AString, ACell> body = Maps.of(
			K_NAME, Strings.create(requiredText(input, K_NAME, 30)),
			K_DISPLAY_NAME, Strings.create(requiredText(input, K_DISPLAY_NAME, 100)));
		String description = optionalText(input, K_DESCRIPTION, 2000);
		if (description != null) body = body.assoc(K_DESCRIPTION, Strings.create(description));
		if (flag(input, K_ALLOW_CRYPTO)) body = body.assoc(K_ALLOW_CRYPTO, CVMBool.TRUE);
		return Moltbook.call("POST", "/submolts", key, body);
	}

	private static CompletableFuture<ACell> notificationsRead(String key, ACell input) {
		String postId = optionalText(input, K_POST_ID, MAX_ID);
		String path = (postId == null) ? "/notifications/read-all" : "/notifications/read-by-post/" + segment(postId);
		return Moltbook.call("POST", path, key, null);
	}

	/** Subscribe/follow with POST, or the reverse with DELETE, on the same path. */
	private static CompletableFuture<ACell> toggle(String key, String path, boolean reverse) {
		return Moltbook.call(reverse ? "DELETE" : "POST", path, key, null);
	}

	private static CompletableFuture<ACell> get(String key, String path) {
		return Moltbook.call("GET", path, key, null);
	}

	// ------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------

	private static String requiredText(ACell input, AString key, int maxLength) {
		String text = optionalText(input, key, maxLength);
		if (text == null) throw new IllegalArgumentException(key + " is required");
		return text;
	}

	private static String optionalText(ACell input, AString key, int maxLength) {
		AString value = RT.ensureString(RT.getIn(input, key));
		if (value == null) return null;
		String text = value.toString().strip();
		if (text.isEmpty()) return null;
		if (text.length() > maxLength) {
			throw new IllegalArgumentException(key + " must be at most " + maxLength + " characters");
		}
		return text;
	}

	/** A positive count capped at {@code max}, as text for a query string; null when absent. */
	private static String optionalCount(ACell input, AString key, int max) {
		ACell cell = RT.getIn(input, key);
		if (cell == null) return null;
		CVMLong n = RT.ensureLong(cell);
		if (n == null || n.longValue() < 1) throw new IllegalArgumentException(key + " must be a positive whole number");
		return Long.toString(Math.min(max, n.longValue()));
	}

	private static boolean flag(ACell input, AString key) {
		return CVMBool.TRUE.equals(RT.getIn(input, key));
	}

	private static void putOptional(Map<String, String> query, String name, String value) {
		if (value != null) query.put(name, value);
	}

	/** A path segment, encoded so a name or id can never alter the path. */
	private static String segment(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String query(Map<String, String> params) {
		if (params.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("?");
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) sb.append('&');
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
			first = false;
		}
		return sb.toString();
	}
}
