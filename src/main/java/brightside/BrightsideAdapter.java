package brightside;

import java.util.concurrent.CompletableFuture;

import brightside.model.Providers;

import java.util.Set;
import java.util.UUID;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.adapter.AAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.Engine.UserPathTarget;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;

/**
 * The Brightside venue adapter: Brightside-specific operations.
 *
 * <p>Registered on the embedded engine at launch ({@link EmbeddedVenue}). This
 * is the home for app-specific operations; the shipped skill library lives in
 * its own {@link BrightsideSkillsAdapter}.</p>
 *
 * <ul>
 *   <li>{@code brightside:info} — app and venue diagnostics.</li>
 *   <li>{@code brightside:context} — read-only, dynamically assembled product
 *       and owner context for the chat agent.</li>
 *   <li>{@code brightside:delete-skill} — remove one skill from the caller's
 *       own {@code w/skills}; the operation cannot address any other path.</li>
 *   <li>{@code brightside:report-skill-feedback} — append one structured
 *       runtime miss under the caller's own {@code w/skill-feedback}.</li>
 *   <li>{@code brightside:shutdown} — clean process shutdown, venue-operator
 *       only, so a newly launched instance can take over (see
 *       {@link brightside.Takeover}).</li>
 *   <li>Two bridges for each of the {@link Gods}: its ask bridge
 *       ({@code brightside:ask-odin}, {@code brightside:alert-thor}) — an
 *       assistant's request to that god, submitted as the calling user with a
 *       short-lived venue-rooted delegation, so the task is the user's own job
 *       and the god sees who asked — and its run bridge
 *       ({@code brightside:odin-run}, {@code brightside:thor-run}) — the god's
 *       hands: one operation from its allowlists, under the venue's authority
 *       or inside a user's namespace via {@code user:sudo}. Only that god may
 *       call its run bridge.</li>
 * </ul>
 *
 * <p>Add further Brightside operations by adding a {@code brightside/<op>.json}
 * resource and a case in {@link #invokeFuture}.</p>
 */
public class BrightsideAdapter extends AAdapter {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrightsideAdapter.class);

	private static final AString K_APP = Strings.intern("app");
	private static final AString K_VERSION = Strings.intern("version");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_DID = Strings.intern("did");
	private static final AString K_SKILLS = Strings.intern("skills");
	private static final AString K_STORE = Strings.intern("store");
	private static final AString K_PID = Strings.intern("pid");
	private static final AString K_ACCEPTED = Strings.intern("accepted");
	private static final AString K_NAME = Strings.intern("name");
	private static final AString K_DELETED = Strings.intern("deleted");
	private static final AString K_ID = Strings.intern("id");
	private static final AString K_PATH = Strings.intern("path");
	private static final AString K_RECORDED = Strings.intern("recorded");
	private static final AString K_CREATED = Strings.intern("created");
	private static final AString K_CATEGORY = Strings.intern("category");
	private static final AString K_SUMMARY = Strings.intern("summary");
	private static final AString K_DETAILS = Strings.intern("details");
	private static final AString K_FAILED_ACTION = Strings.intern("failedAction");
	private static final AString K_ERROR = Strings.intern("error");
	private static final AString K_REPORTER = Strings.intern("reporter");
	private static final AString K_AGENT_ID = Strings.intern("agentId");
	private static final AString K_SESSION_ID = Strings.intern("sessionId");
	private static final AString K_USER_NAME = Strings.intern("userName");
	private static final AString K_MODEL_OPERATION = Strings.intern("modelOperation");
	private static final AString K_REQUEST = Strings.intern("request");
	private static final AString K_CONTEXT = Strings.intern("context");
	private static final AString K_TIMEOUT = Strings.intern("timeout");
	private static final AString K_TASK = Strings.intern("task");
	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_AGENT = Strings.intern("agent");
	private static final AString K_INPUT = Strings.intern("input");
	private static final AString K_OPERATION = Strings.intern("operation");
	private static final AString K_USER = Strings.intern("user");
	private static final AString K_DID_FIELD = Strings.intern("did");
	private static final AString K_REASON = Strings.intern("reason");
	private static final AString K_REPORT = Strings.intern("report");
	private static final AString K_ADDRESS = Strings.intern("address");
	private static final AString K_SEED = Strings.intern("seed");
	private static final String OP_GENERATE_KEY = "v/ops/convex/generate-key";
	private static final String OP_AGENT_REQUEST = "v/ops/agent/request";
	private static final String OP_USER_SUDO = "v/ops/user/sudo";
	private static final long DEFAULT_ASK_TIMEOUT_MS = 30_000;
	private static final long MAX_ASK_TIMEOUT_MS = 300_000;
	/** Lifetime of the per-call delegations the bridges mint: long enough to submit, never to keep. */
	private static final long BRIDGE_GRANT_SECONDS = 300;
	private static final int MAX_SKILL_NAME = 64;
	private static final Set<String> FEEDBACK_CATEGORIES = Set.of(
		"failed-load", "missing-skill", "instruction-conflict", "missing-capability", "other");

	/** Runs to shut the app down cleanly (flush venue + exit); null off the desktop. */
	private final Runnable onShutdown;

	public BrightsideAdapter() {
		this(null);
	}

	public BrightsideAdapter(Runnable onShutdown) {
		this.onShutdown = onShutdown;
	}

	/** Where a person's Peers map settings sit in this adapter's workspace, below their DID. */
	static final String MAP_SETTINGS = "peers-map";

	/**
	 * A person's Peers map settings, as stored ({@link brightside.peers.MapSettings#toCell}),
	 * or null. They live in this adapter's venue-private workspace rather than
	 * the person's own namespace: they are how this installation draws the
	 * map, not data anyone else is meant to read, and the desktop app reads and
	 * writes them in-process, so arranging the map leaves no job records.
	 */
	public ACell mapSettings(AString userDID) {
		return adapterWorkspace().read(adapterWorkspace().userPath(userDID, MAP_SETTINGS));
	}

	/** Stores a person's Peers map settings, replacing any. */
	public void saveMapSettings(AString userDID, ACell settings) {
		adapterWorkspace().write(adapterWorkspace().userPath(userDID, MAP_SETTINGS), settings);
	}

	@Override
	public String getName() {
		return "brightside";
	}

	@Override
	public String getDescription() {
		return "Brightside desktop app operations. brightside:info reports app and "
			+ "venue diagnostics; further app-specific operations live here.";
	}

	@Override
	protected void installAssets() {
		installAsset("brightside/info", "/adapters/brightside/info.json");
		installAsset("brightside/context", "/adapters/brightside/context.json");
		installAsset("brightside/delete-skill", "/adapters/brightside/delete-skill.json");
		installAsset("brightside/report-skill-feedback", "/adapters/brightside/report-skill-feedback.json");
		installAsset("brightside/shutdown", "/adapters/brightside/shutdown.json");
		for (God god : Gods.ALL) {
			installAsset("brightside/" + god.ask(), "/adapters/brightside/" + god.ask() + ".json");
			installAsset("brightside/" + god.run(), "/adapters/brightside/" + god.run() + ".json");
		}
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("info".equals(op)) {
			return CompletableFuture.completedFuture(buildInfo());
		}
		if ("context".equals(op)) {
			return handleContext(ctx, input);
		}
		if ("delete-skill".equals(op)) {
			return handleDeleteSkill(ctx, input);
		}
		if ("report-skill-feedback".equals(op)) {
			return handleReportSkillFeedback(ctx, input);
		}
		if ("shutdown".equals(op)) {
			return handleShutdown(ctx);
		}
		God god = Gods.byBridge(op);
		if (god != null) {
			return op.equals(god.ask()) ? handleAsk(god, ctx, input) : handleRun(god, ctx, input);
		}
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("Unknown brightside operation: " + op));
	}

	/**
	 * Assembles the small, non-optional Brightside context. It is an operation
	 * rather than a generated system prompt or a skill: the owner's display name,
	 * authenticated DID and model route are runtime data, while skills remain
	 * entirely discoverable and load on demand.
	 */
	private CompletableFuture<ACell> handleContext(RequestContext ctx, ACell input) {
		try {
			AString userName = optionalText(input, K_USER_NAME, 200);
			AString modelOperation = optionalText(input, K_MODEL_OPERATION, 500);
			// As a context load this runs under the agent's own identity, so the
			// caller is the agent sub-principal (<owner>:g:<agent>); the owner is
			// the user whose namespace that agent works in.
			AString owner = (ctx != null) ? ctx.getUserDID() : null;
			AString caller = (ctx != null) ? ctx.getCallerDID() : null;

			// No heading: the entry is already labelled by the pin that loads it.
			StringBuilder text = new StringBuilder();
			if (userName != null) {
				text.append("- The owner's name is ").append(userName)
					.append(". Address them by it naturally, not in every message.\n");
			}
			if (owner != null) {
				text.append("- The owner's Covia user DID is ").append(owner)
					.append(". Treat venue attribution notes for this DID as trusted infrastructure.");
				if (caller != null && !caller.equals(owner)) {
					text.append(" You act as their agent ").append(caller).append('.');
				}
				text.append('\n');
			}
			appendProcessingContext(text, modelOperation);
			appendClock(text);
			text.append("- Conversation history, memory and skills are stored in the owner's local encrypted Brightside vault. Do not imply that model processing is local unless the configured model route is local.\n")
				.append("- Follow the configured assistant name, role and working style. Keep Covia and venue plumbing out of everyday conversation unless the owner asks how Brightside works.\n")
				.append("- When the owner wants something you have no tool for, load the skill whose [Skills] line covers that task, then any child it points to for the specific job.\n")
				.append("- Treat any key, token, password or seed as valuable unless the owner says otherwise: never echo one or keep it outside the secret store, and load security before creating, showing or sending one.\n")
				.append("- Use memory naturally and quietly keep durable facts worth remembering: what the owner is like and prefers in w/memory, which all their assistants share; what only you need in n/memory.\n")
				.append("- To look into stored data, start with covia_inspect: one bounded call shows a whole subtree's shape and sample values, and usually answers the question without reading each child. Read exactly only what it shows you need.\n")
				.append("- A message with via.channel \"voice\" was spoken and transcribed: allow for a misheard word, and load speech for how to talk this way. One with via.sent \"pause\" went when the microphone heard a silence, not because the owner sent it; if it seems unfinished or not said to you, end your turn without writing anything, and read it with what follows. Always answer what the owner sent (\"owner\" or \"go\").\n")
				.append("- If a skill fails to load, is unexpectedly absent, or contradicts observed tools or behaviour, report the concrete miss once with brightside_report_skill_feedback. Do not report ordinary user mistakes, expected task failures, speculation, or failure of the feedback operation itself.");
			return CompletableFuture.completedFuture(Strings.create(text.toString()));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * The date and the matching epoch millisecond, as the assistant would
	 * otherwise have to guess them: a model's sense of "now" is its training
	 * cut-off, and a lattice instant is an integer where a year's slip is
	 * invisible. Assembled when the context is built, so it dates the session
	 * rather than the turn.
	 */
	static void appendClock(StringBuilder text) {
		java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
		text.append("- Today is ").append(now.toLocalDate()).append(" (").append(now.getZone().getId())
			.append("). Timestamp ").append(now.toInstant().toEpochMilli()).append(".\n");
	}

	private static void appendProcessingContext(StringBuilder text, AString modelOperation) {
		if (modelOperation == null) {
			text.append("- The model-processing route is not identified; do not assume it is local.\n");
			return;
		}
		String operation = modelOperation.toString();
		String providerId = Providers.providerOf(operation);
		Providers.Provider provider = Providers.byId(providerId);
		if (provider != null && "ollama".equals(provider.id())) {
			text.append("- Model processing uses the local ").append(provider.label())
				.append(" route (").append(operation).append(").\n");
		} else if (provider != null) {
			text.append("- Model processing sends the context needed for a reply to ")
				.append(provider.label()).append(" via ").append(operation).append(".\n");
		} else {
			text.append("- Model processing uses ").append(operation)
				.append("; do not assume that operation runs locally.\n");
		}
	}

	/**
	 * Deletes a caller-owned skill subtree. Validating every relative name and
	 * fixing the root at w/skills is the authority boundary: granting this operation
	 * can never expose general deletion over workspace, memory or operations.
	 */
	private CompletableFuture<ACell> handleDeleteSkill(RequestContext ctx, ACell input) {
		try {
			AString nameCell = RT.ensureString(RT.getIn(input, K_NAME));
			String name = (nameCell != null) ? nameCell.toString() : null;
			if (!isSkillPath(name)) {
				throw new IllegalArgumentException(
					"Skill name must be relative to w/skills (for example parent/child), at most 1024 characters; "
					+ "each segment must be 1–64 lowercase letters, digits or hyphens, without leading or trailing hyphens");
			}
			AString path = Strings.create("w/skills/" + name);
			UserPathTarget target = engine.requireUserPath(ctx, path, Capability.CRUD_DELETE, false);
			boolean deleted = target.user() != null
				&& target.user().workspaceRoot().delete(target.pathKeys());
			return CompletableFuture.completedFuture(Maps.of(
				K_NAME, nameCell,
				K_DELETED, CVMBool.of(deleted)));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private static boolean isSkillPath(String name) {
		if (name == null || name.length() > 1024) return false;
		for (String segment : name.split("/", -1)) {
			if (!isSkillName(segment)) return false;
		}
		return true;
	}

	private static boolean isSkillName(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_SKILL_NAME) return false;
		if (name.charAt(0) == '-' || name.charAt(name.length() - 1) == '-') return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-') return false;
		}
		return true;
	}

	/**
	 * Appends one immutable feedback record beneath a generated path. The caller
	 * supplies facts, never a path or identity; the current Job provides the
	 * stable id and the request context provides provenance. This operation has
	 * no write surface outside {@code w/skill-feedback/<id>}.
	 */
	private CompletableFuture<ACell> handleReportSkillFeedback(RequestContext ctx, ACell input) {
		try {
			String category = requiredText(input, K_CATEGORY, 64);
			if (!FEEDBACK_CATEGORIES.contains(category)) {
				throw new IllegalArgumentException("Unknown skill-feedback category: " + category);
			}
			AString summary = Strings.create(requiredText(input, K_SUMMARY, 500));
			AString details = optionalText(input, K_DETAILS, 4000);
			AString failedAction = optionalText(input, K_FAILED_ACTION, 256);
			AString error = optionalText(input, K_ERROR, 2000);

			Blob jobId = (ctx != null && ctx.getJob() != null) ? ctx.getJob().getID() : null;
			String id = (jobId != null)
				? jobId.toHexString()
				: UUID.randomUUID().toString().replace("-", "");
			AString path = Strings.create("w/skill-feedback/" + id);
			UserPathTarget target = engine.requireUserPath(ctx, path, Capability.CRUD_WRITE, true);

			AMap<AString, ACell> record = Maps.of(
				K_ID, id,
				K_CREATED, CVMLong.create(Utils.getCurrentTimestamp()),
				K_CATEGORY, category,
				K_SUMMARY, summary,
				K_REPORTER, (ctx != null) ? ctx.getCallerDID() : null);
			if (details != null) record = record.assoc(K_DETAILS, details);
			if (failedAction != null) record = record.assoc(K_FAILED_ACTION, failedAction);
			if (error != null) record = record.assoc(K_ERROR, error);
			if (ctx != null && ctx.getAgentId() != null) {
				record = record.assoc(K_AGENT_ID, ctx.getAgentId());
			}
			if (ctx != null && ctx.getSessionId() != null) {
				record = record.assoc(K_SESSION_ID, Strings.create(ctx.getSessionId().toHexString()));
			}
			target.user().workspaceRoot().write(target.pathKeys(), record);
			return CompletableFuture.completedFuture(Maps.of(
				K_ID, id,
				K_PATH, path,
				K_RECORDED, CVMBool.TRUE));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private static String requiredText(ACell input, AString key, int maxLength) {
		AString value = RT.ensureString(RT.getIn(input, key));
		String text = (value != null) ? value.toString().strip() : null;
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException(key + " is required");
		}
		if (text.length() > maxLength) {
			throw new IllegalArgumentException(key + " must be at most " + maxLength + " characters");
		}
		return text;
	}

	private static AString optionalText(ACell input, AString key, int maxLength) {
		AString value = RT.ensureString(RT.getIn(input, key));
		if (value == null) return null;
		String text = value.toString().strip();
		if (text.isEmpty()) return null;
		if (text.length() > maxLength) {
			throw new IllegalArgumentException(key + " must be at most " + maxLength + " characters");
		}
		return Strings.create(text);
	}

	/**
	 * Clean process shutdown, restricted to the venue operator: the caller must
	 * authenticate as the venue's own DID (a venue-signed token — the same trust
	 * path {@code auth:whoami} reports as "internal"). Schedules the shutdown just
	 * after acknowledging, so the caller gets a clean response before we exit.
	 */
	private CompletableFuture<ACell> handleShutdown(RequestContext ctx) {
		AString caller = (ctx != null) ? ctx.getCallerDID() : null;
		AString venueDID = engine.getDIDString();
		if (caller == null || venueDID == null || !caller.equals(venueDID)) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("brightside:shutdown requires venue operator authority"));
		}
		if (onShutdown != null) {
			Thread t = new Thread(() -> {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				onShutdown.run();
			}, "brightside-shutdown");
			t.setDaemon(true);
			t.start();
		}
		return CompletableFuture.completedFuture(Maps.of(K_ACCEPTED, CVMBool.TRUE));
	}

	// ------------------------------------------------------------------
	// God bridges
	// ------------------------------------------------------------------

	/**
	 * An assistant's request to a god. Agents carry no delegations and the gods
	 * live under the venue principal, so a plain {@code agent:request} from a
	 * user would be refused. The bridge mints a five-minute venue-rooted
	 * {@code agent/request} delegation to the <em>calling user</em> and submits
	 * the request as that user with it: the task is the user's own job (so the
	 * usual job tools can follow it), Covia attributes the caller to the god
	 * natively, and the {@code from}/{@code agent} fields on the task come from
	 * the authenticated context — never from the request text.
	 */
	private CompletableFuture<ACell> handleAsk(God god, RequestContext ctx, ACell input) {
		try {
			AString user = (ctx != null) ? ctx.getUserDID() : null;
			if (user == null || engine.isPublicPrincipal(user)) {
				throw new IllegalStateException(god.ask() + " requires an authenticated user of this venue");
			}
			String request = requiredText(input, K_REQUEST, 4000);
			AString context = optionalText(input, K_CONTEXT, 8000);
			long timeout = timeoutMillis(input);

			AMap<AString, ACell> task = Maps.of(K_TASK, request, K_FROM, user);
			if (context != null) task = task.assoc(K_CONTEXT, context);
			if (ctx.getAgentId() != null) task = task.assoc(K_AGENT, ctx.getAgentId());

			AString address = Strings.create(god.address(engine.getDIDString().toString()));
			ACell proof = venueGrant(user, Vectors.of(cap(address, Abilities.AGENT_REQUEST)));
			RequestContext asUser = RequestContext.of(user, Vectors.of(proof));
			AMap<AString, ACell> requestInput = Maps.of(
				Fields.AGENT_ID, address,
				K_INPUT, task,
				K_TIMEOUT, CVMLong.create(timeout));
			return engine.jobs().invokeInternal(OP_AGENT_REQUEST, requestInput, asUser);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * A god's hands. Covia admits venue authority only to the venue DID itself,
	 * never to an agent it owns, so the god's tool call arrives here as its own
	 * sub-principal and the bridge supplies the operator context — for one of
	 * the god's venue operations, or for one of its namespace operations run on
	 * a user's behalf through {@code user:sudo} under a per-call venue-rooted
	 * {@code user/sudo} delegation. The god's allowlists, not the caller, bound
	 * what the venue context is lent for. For a god with an overseer, anything
	 * that changes state needs a {@code reason}, and is reported once it is done.
	 */
	private CompletableFuture<ACell> handleRun(God god, RequestContext ctx, ACell input) {
		try {
			AString venueDID = engine.getDIDString();
			AString caller = (ctx != null) ? ctx.getCallerDID() : null;
			if (caller == null || !caller.toString().equals(god.did(venueDID.toString()))) {
				throw new IllegalStateException(god.run() + " may only be invoked by " + god.id());
			}
			String operation = requiredText(input, K_OPERATION, 200);
			ACell operationInput = RT.getIn(input, K_INPUT);
			if (operationInput == null) operationInput = Maps.empty();
			// A god signs by reference only: a literal seed would sit in its job
			// input and in the report to its overseer, where nothing redacts it.
			AString seed = RT.ensureString(RT.getIn(operationInput, K_SEED));
			if (seed != null && !covia.venue.Engine.isSecretRef(seed.toString())) {
				throw new IllegalArgumentException("A seed goes by secret reference (s/NAME), never as a literal");
			}
			AString named = optionalText(input, K_USER, 300);
			// A god names the user it keeps by its slug.
			AString user = (named != null && named.toString().equals(god.keeps()))
				? Strings.create(god.kept(venueDID.toString())) : named;
			boolean overseen = god.overseer() != null && changesState(operation);
			AString reason = overseen
				? Strings.create(requiredText(input, K_REASON, 2000))
				: optionalText(input, K_REASON, 2000);

			CompletableFuture<ACell> done = run(god, operation, operationInput, user);
			if (!overseen) return done;
			ACell ranWith = operationInput;
			return done.thenCompose(outcome -> report(god, ctx, operation, ranWith, user, reason, outcome));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Runs one of {@code god}'s operations: as the venue without a user, else inside that user's namespace. */
	private CompletableFuture<ACell> run(God god, String operation, ACell operationInput, AString user) {
		try {
			AString venueDID = engine.getDIDString();
			if (user == null) {
				if (!god.venueOperations().contains(operation)) {
					throw new IllegalArgumentException("Not a venue operation " + god.id() + " may run: " + operation
						+ " (allowed: " + String.join(", ", new java.util.TreeSet<>(god.venueOperations())) + ")");
				}
				return engine.jobs().invokeInternal(operation, operationInput, engine.venueContext());
			}
			if (!god.sudoOperations().contains(operation)) {
				throw new IllegalArgumentException("Not an operation " + god.id() + " may run in a user's namespace: "
					+ operation + " (allowed: " + String.join(", ", new java.util.TreeSet<>(god.sudoOperations())) + ")");
			}
			if (!user.toString().startsWith(venueDID + ":u:")) {
				throw new IllegalArgumentException("sudo is limited to this venue's own users; not " + user);
			}
			// A user a god keeps for itself is that god's alone: its secrets are
			// usable only as that user, so this is what keeps Njord's treasury his.
			for (God keeper : Gods.ALL) {
				if (keeper != god && user.toString().equals(keeper.kept(venueDID.toString()))) {
					throw new IllegalArgumentException(user + " is kept by " + keeper.id() + " alone");
				}
			}
			// And a god that keeps a user makes keys only there, in its own custody.
			if (god.keeps() != null && OP_GENERATE_KEY.equals(operation)
					&& !user.toString().equals(god.kept(venueDID.toString()))) {
				throw new IllegalArgumentException(god.id() + " makes keys only in " + god.keeps());
			}
			// Covia bounds a sudo context to the presented proofs — the venue's own
			// ambient authority does not leak in — so the delegation carries the
			// namespace scope as well as the sudo right. The allowlist above is what
			// bounds the operation; the scope only lets its points of action pass.
			ACell proof = venueGrant(venueDID, Vectors.of(
				cap(user, Abilities.USER_SUDO),
				cap(Strings.create(user + "/"), Strings.create("*"))));
			RequestContext operator = RequestContext.of(venueDID, Vectors.of(proof));
			AMap<AString, ACell> sudo = Maps.of(K_DID_FIELD, user, K_OPERATION, operation, K_INPUT, operationInput);
			return engine.jobs().invokeInternal(OP_USER_SUDO, sudo, operator);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Whether {@code operation} can change anything: whatever the venue does
	 * not declare read-only, including an operation it cannot resolve.
	 */
	private boolean changesState(String operation) {
		try {
			covia.grid.Asset asset = engine.resolveAsset(Strings.create(operation), engine.venueContext());
			return asset == null || !CVMBool.TRUE.equals(RT.getIn(asset.meta(), Fields.OPERATION, Fields.READ_ONLY));
		} catch (Exception e) {
			return true;
		}
	}

	/**
	 * Tells {@code god}'s overseer what it has just done, through the
	 * overseer's ask bridge as the god itself, without waiting for a verdict.
	 * The change has happened either way, so a report that cannot be sent is
	 * logged rather than failing it. A map outcome gains {@code report}: where
	 * it went and the job that carries it.
	 */
	@SuppressWarnings("unchecked")
	private CompletableFuture<ACell> report(God god, RequestContext ctx, String operation, ACell operationInput,
			AString user, AString reason, ACell outcome) {
		God overseer = Gods.byId(god.overseer());
		if (overseer == null) {
			log.warn("{} has no overseer named {} to report to", god.id(), god.overseer());
			return CompletableFuture.completedFuture(outcome);
		}
		String where = (user != null) ? " in the namespace of " + user : " as the venue";
		AMap<AString, ACell> ask = Maps.of(
			K_REQUEST, clip(god.id() + " has just run " + operation + where + ". Reason given: " + reason, 4000),
			K_CONTEXT, clip("Input:\n" + JSON.toStringPretty(operationInput)
				+ "\n\nOutcome:\n" + ((outcome != null) ? JSON.toStringPretty(outcome) : "(none)"), 8000),
			K_TIMEOUT, CVMLong.create(0));
		return handleAsk(overseer, ctx, ask).handle((snapshot, e) -> {
			if (e != null) {
				log.warn("Could not report {}'s {} to {}: {}", god.id(), operation, overseer.id(), e.toString());
				return outcome;
			}
			if (!(outcome instanceof AMap<?, ?> map)) return outcome;
			return ((AMap<AString, ACell>) map).assoc(K_REPORT, Maps.of(
				K_ADDRESS, RT.getIn(snapshot, K_ADDRESS),
				K_ID, RT.getIn(snapshot, K_ID)));
		});
	}

	private static String clip(String text, int max) {
		return (text.length() <= max) ? text : text.substring(0, max - 1) + "…";
	}

	/**
	 * A venue-signed UCAN — the same claims {@code ucan:issue} mints — granting
	 * {@code aud} the single capability {@code can} over {@code with} for
	 * {@link #BRIDGE_GRANT_SECONDS}, as the verified token map a request context
	 * presents. It passes through Covia's own JWT trust boundary rather than
	 * being trusted by construction. The venue is root authority for its own
	 * resources and for the users it issued, which is exactly what the bridges
	 * lend: it never signs for a foreign DID.
	 */
	private ACell venueGrant(AString aud, AVector<ACell> att) {
		long now = Utils.getCurrentTimestamp() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			UCAN.ISS, engine.getDIDString(),
			UCAN.AUD, aud,
			UCAN.UCV, UCAN.VERSION,
			UCAN.ATT, att,
			UCAN.PRF, Vectors.empty());
		claims = claims.assoc(UCAN.EXP, CVMLong.create(now + BRIDGE_GRANT_SECONDS));
		AString jwt = JWT.signPublic(claims, engine.getKeyPair());
		UCAN token = UcanJwtValidator.validateJWT(jwt, now, engine.didVerifier());
		if (token == null) throw new IllegalStateException("The venue could not verify its own delegation");
		return token.toMap();
	}

	private static AMap<AString, ACell> cap(AString with, AString can) {
		return Maps.of(Capability.WITH, with, Capability.CAN, can);
	}

	/** {@code timeout} in milliseconds: the default when absent, clamped to [0, MAX]. */
	private static long timeoutMillis(ACell input) {
		ACell cell = RT.getIn(input, K_TIMEOUT);
		if (cell == null) return DEFAULT_ASK_TIMEOUT_MS;
		CVMLong n = RT.ensureLong(cell);
		if (n == null) throw new IllegalArgumentException("timeout must be an integer number of milliseconds");
		return Math.max(0, Math.min(MAX_ASK_TIMEOUT_MS, n.longValue()));
	}

	private AMap<AString, ACell> buildInfo() {
		String version = getClass().getPackage().getImplementationVersion();
		AString venueName = engine.getName();
		AString did = engine.getDIDString();
		return Maps.of(
			K_APP, BrightSide.APP_NAME,
			K_VERSION, (version != null) ? version : "dev",
			K_VENUE, (venueName != null) ? venueName.toString() : null,
			K_DID, (did != null) ? did.toString() : null,
			K_SKILLS, shippedSkills(),
			// Which instance this is: the store it holds and the process holding
			// it. A newcomer taking over waits on both (Takeover).
			K_STORE, storeName(),
			K_PID, CVMLong.create(ProcessHandle.current().pid()));
	}

	/** The store this venue runs on, as configured: a path, or {@code temp}. */
	private String storeName() {
		try {
			return engine.config().getStore();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * The shipped skill names, from the skills adapter's own list (one source
	 * of truth). A resource installed at more than one path (a child shared by
	 * two routers) is listed once.
	 */
	private static AVector<ACell> shippedSkills() {
		AVector<ACell> out = Vectors.empty();
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		for (String path : BrightsideSkillsAdapter.SHIPPED) {
			String name = path.substring(path.lastIndexOf('/') + 1);
			if (seen.add(name)) out = out.conj(Strings.create(name));
		}
		return out;
	}
}
