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
import convex.core.util.Utils;
import covia.adapter.AAdapter;
import covia.adapter.CoviaAdapter;
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
 *   <li>{@code brightside:ask-odin} — an assistant's request to {@link Odin},
 *       submitted as the calling user with a short-lived venue-rooted
 *       delegation, so the task is the user's own job and Odin sees who asked.</li>
 *   <li>{@code brightside:odin-run} — Odin's hands: one allowlisted operator
 *       operation under the venue's authority, or inside a user's namespace via
 *       {@code user:sudo}. Odin alone may call it.</li>
 * </ul>
 *
 * <p>Add further Brightside operations by adding a {@code brightside/<op>.json}
 * resource and a case in {@link #invokeFuture}.</p>
 */
public class BrightsideAdapter extends AAdapter {

	private static final AString K_APP = Strings.intern("app");
	private static final AString K_VERSION = Strings.intern("version");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_DID = Strings.intern("did");
	private static final AString K_SKILLS = Strings.intern("skills");
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
		installAsset("brightside/ask-odin", "/adapters/brightside/ask-odin.json");
		installAsset("brightside/odin-run", "/adapters/brightside/odin-run.json");
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
		if ("ask-odin".equals(op)) {
			return handleAskOdin(ctx, input);
		}
		if ("odin-run".equals(op)) {
			return handleOdinRun(ctx, input);
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
			text.append("- Conversation history, memory and skills are stored in the owner's local encrypted Brightside vault. Do not imply that model processing is local unless the configured model route is local.\n")
				.append("- Follow the configured assistant name, role and working style. Keep Covia and venue plumbing out of everyday conversation unless the owner asks how Brightside works.\n")
				.append("- When the owner wants something you have no tool for, load the skill whose [Skills] line covers that task, then any child it points to for the specific job.\n")
				.append("- Treat any key, token, password or seed as valuable unless the owner says otherwise: never echo one or keep it outside the secret store, and load security before creating, showing or sending one.\n")
				.append("- Use private memory naturally and quietly maintain durable facts worth remembering.\n")
				.append("- If a skill fails to load, is unexpectedly absent, or contradicts observed tools or behaviour, report the concrete miss once with brightside_report_skill_feedback. Do not report ordinary user mistakes, expected task failures, speculation, or failure of the feedback operation itself.");
			return CompletableFuture.completedFuture(Strings.create(text.toString()));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
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
	 * Deletes exactly one caller-owned skill. Accepting a validated name rather
	 * than an arbitrary path is the authority boundary: granting this operation
	 * can never expose general deletion over workspace, memory or operations.
	 */
	private CompletableFuture<ACell> handleDeleteSkill(RequestContext ctx, ACell input) {
		try {
			AString nameCell = RT.ensureString(RT.getIn(input, K_NAME));
			String name = (nameCell != null) ? nameCell.toString() : null;
			if (!isSkillName(name)) {
				throw new IllegalArgumentException(
					"Skill name must be 1–64 lowercase letters, digits or hyphens, without leading or trailing hyphens");
			}
			AString path = Strings.create("w/skills/" + name);
			UserPathTarget target = engine.requireUserPath(ctx, path, Capability.CRUD_DELETE, false);
			boolean deleted = target.user() != null
				&& CoviaAdapter.deletePathFromCursor(target.user().cursor(), target.pathKeys());
			return CompletableFuture.completedFuture(Maps.of(
				K_NAME, nameCell,
				K_DELETED, CVMBool.of(deleted)));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
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
			CoviaAdapter.writePathToCursor(target.user().cursor(), target.pathKeys(), record);
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
	// Odin bridges
	// ------------------------------------------------------------------

	/**
	 * An assistant's request to Odin. Agents carry no delegations and Odin lives
	 * under the venue principal, so a plain {@code agent:request} from a user
	 * would be refused. The bridge mints a five-minute venue-rooted
	 * {@code agent/request} delegation to the <em>calling user</em> and submits
	 * the request as that user with it: the task is the user's own job (so the
	 * usual job tools can follow it), Covia attributes the caller to Odin
	 * natively, and the {@code from}/{@code agent} fields on the task come from
	 * the authenticated context — never from the request text.
	 */
	private CompletableFuture<ACell> handleAskOdin(RequestContext ctx, ACell input) {
		try {
			AString user = (ctx != null) ? ctx.getUserDID() : null;
			if (user == null || engine.isPublicPrincipal(user)) {
				throw new IllegalStateException("ask-odin requires an authenticated user of this venue");
			}
			String request = requiredText(input, K_REQUEST, 4000);
			AString context = optionalText(input, K_CONTEXT, 8000);
			long timeout = timeoutMillis(input);

			AMap<AString, ACell> task = Maps.of(K_TASK, request, K_FROM, user);
			if (context != null) task = task.assoc(K_CONTEXT, context);
			if (ctx.getAgentId() != null) task = task.assoc(K_AGENT, ctx.getAgentId());

			AString address = Strings.create(Odin.address(engine.getDIDString().toString()));
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
	 * Odin's hands. Covia admits venue authority only to the venue DID itself,
	 * never to an agent it owns, so Odin's tool call arrives here as his own
	 * sub-principal and the bridge supplies the operator context — for an
	 * allowlisted venue operation, or for an allowlisted namespace operation run
	 * on a user's behalf through {@code user:sudo} under a per-call venue-rooted
	 * {@code user/sudo} delegation. The allowlists, not the caller, bound what
	 * the venue context is lent for.
	 */
	private CompletableFuture<ACell> handleOdinRun(RequestContext ctx, ACell input) {
		try {
			AString venueDID = engine.getDIDString();
			AString caller = (ctx != null) ? ctx.getCallerDID() : null;
			if (caller == null || !caller.toString().equals(Odin.did(venueDID.toString()))) {
				throw new IllegalStateException("odin-run may only be invoked by " + Odin.AGENT_ID);
			}
			String operation = requiredText(input, K_OPERATION, 200);
			ACell operationInput = RT.getIn(input, K_INPUT);
			if (operationInput == null) operationInput = Maps.empty();
			AString user = optionalText(input, K_USER, 300);

			if (user == null) {
				if (!Odin.VENUE_OPERATIONS.contains(operation)) {
					throw new IllegalArgumentException("Not a venue operation Odin may run: " + operation
						+ " (allowed: " + String.join(", ", new java.util.TreeSet<>(Odin.VENUE_OPERATIONS)) + ")");
				}
				return engine.jobs().invokeInternal(operation, operationInput, engine.venueContext());
			}
			if (!Odin.SUDO_OPERATIONS.contains(operation)) {
				throw new IllegalArgumentException("Not an operation Odin may run in a user's namespace: " + operation
					+ " (allowed: " + String.join(", ", new java.util.TreeSet<>(Odin.SUDO_OPERATIONS)) + ")");
			}
			if (!user.toString().startsWith(venueDID + ":u:")) {
				throw new IllegalArgumentException("sudo is limited to this venue's own users; not " + user);
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
			K_SKILLS, shippedSkills());
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
