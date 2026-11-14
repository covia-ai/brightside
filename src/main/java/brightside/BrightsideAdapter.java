package brightside;

import static brightside.BrightSide.APP_NAME;

import java.util.concurrent.CompletableFuture;

import brightside.chat.AgentSetup;
import brightside.chat.ChatSession;
import brightside.model.Providers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import convex.auth.ucan.Capability;
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
import convex.lattice.cursor.AForkableCursor;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.adapter.AAdapter;
import covia.grid.AContent;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.AdapterState;
import covia.venue.Engine.UserPathTarget;
import covia.venue.RequestContext;
import covia.venue.storage.ContentProvider;

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
 *   <li>{@code brightside:restart} — stops the venue and starts it again
 *       inside this process, and {@code brightside:relaunch} — starts a new
 *       instance that takes over from this one; venue-operator only, so Odin's
 *       to run.</li>
 *   <li>{@code brightside:storage} — the disk the store takes
 *       ({@link StoreSpace}): compaction at the next start, and the originals
 *       repairs kept; venue-operator only, so Odin's to run.</li>
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
	private static final AString K_USERS = Strings.intern("users");
	private static final AString K_VERSION = Strings.intern("version");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_DID = Strings.intern("did");
	private static final AString K_SKILLS = Strings.intern("skills");
	private static final AString K_STORE = Strings.intern("store");
	private static final AString K_PID = Strings.intern("pid");
	private static final AString K_STARTED = Strings.intern("started");
	private static final AString K_JAR = Strings.intern("jar");
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
	private static final AString K_OUTCOME = Strings.intern("outcome");
	private static final AString K_RESUMED = Strings.intern("resumed");
	private static final String OP_AGENT_INFO = "v/ops/agent/info";
	private static final String OP_AGENT_UPDATE = "v/ops/agent/update";
	private static final String OP_AGENT_RESUME = "v/ops/agent/resume";
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
	private static final AString K_COMPACT = Strings.intern("compact");
	private static final AString K_REMOVE_KEPT = Strings.intern("removeKeptOriginals");
	private static final AString K_STORE_BYTES = Strings.intern("storeBytes");
	private static final AString K_COMPACT_AT_NEXT_START = Strings.intern("compactAtNextStart");
	private static final AString K_KEPT_ORIGINALS = Strings.intern("keptOriginals");
	private static final AString K_REMOVED = Strings.intern("removed");
	private static final AString K_BYTES = Strings.intern("bytes");
	private static final AString K_MODIFIED = Strings.intern("modified");
	private static final String OP_GENERATE_KEY = "v/ops/convex/generate-key";
	private static final String OP_AGENT_REQUEST = "v/ops/agent/request";
	private static final String OP_USER_SUDO = "v/ops/user/sudo";
	private static final String OP_USER_DELETE = "v/ops/user/delete";
	/** How a god withdraws an Inbox request it made: cancelling the request's job. */
	static final String OP_WITHDRAW = "v/ops/job/cancel";
	private static final String OP_HITL_REQUEST = "v/ops/hitl/request";
	private static final long DEFAULT_ASK_TIMEOUT_MS = 30_000;
	private static final long MAX_ASK_TIMEOUT_MS = 300_000;
	private static final int MAX_SKILL_NAME = 64;
	private static final Set<String> FEEDBACK_CATEGORIES = Set.of(
		"failed-load", "missing-skill", "instruction-conflict", "missing-capability", "improvement", "other");

	/**
	 * What the desktop app does for its lifecycle operations. An app that can
	 * only stop, as a test's, gives just {@link #shutdown}; the others then refuse.
	 * Each refusal is thrown in words for the caller.
	 */
	@FunctionalInterface
	public interface App {

		/** Flushes the venue and ends the process ({@code brightside:shutdown}). */
		void shutdown();

		/**
		 * Checks that the venue can restart inside this process
		 * ({@code brightside:restart}) and returns the restart, which runs once
		 * the operation has answered.
		 */
		default Runnable prepareRestart() throws Exception {
			throw new ConflictException("This " + APP_NAME + " cannot restart its venue");
		}

		/**
		 * Starts a Brightside that takes over from this one ({@code brightside:relaunch}),
		 * from {@code jar}, or the program this one runs when null, and returns its process.
		 */
		default ProcessHandle relaunch(AContent jar) throws Exception {
			throw new ConflictException("This " + APP_NAME + " cannot start itself again");
		}
	}

	/** How long an operation that ends this venue waits after answering, so its answer is recorded first. */
	private static final long ANSWER_GRACE_MS = 300;

	/** The app this venue runs in; null off the desktop. */
	private final App app;
	/** One relaunch at a time, so two cannot both start a successor. */
	private final Object relaunchLock = new Object();
	/** The successor the last relaunch started; guarded by {@link #relaunchLock}. */
	private ProcessHandle successor;

	public BrightsideAdapter() {
		this(null);
	}

	public BrightsideAdapter(App app) {
		this.app = app;
	}

	/** Where a person's Peers map settings sit in this adapter's state, below their DID. */
	static final String MAP_SETTINGS = "peers-map";

	/**
	 * A person's Peers map settings, as stored ({@link brightside.peers.MapSettings#toCell}),
	 * or null. They live in this adapter's own state rather than the person's
	 * namespace: they are how this installation draws the map, which the
	 * person's agents may read at {@code x/brightside} but not change, and the
	 * desktop app reads and writes them in-process, so arranging the map leaves
	 * no job records.
	 */
	public ACell mapSettings(AString userDID) {
		AdapterState state = adapterState(userDID);
		return state.read(state.userPath(userDID, MAP_SETTINGS));
	}

	/**
	 * Stores a person's Peers map settings, replacing any. Written through the
	 * person's own view of this adapter's state, so a write racing their
	 * deletion is refused rather than left behind for whoever has the DID next.
	 */
	public void saveMapSettings(AString userDID, ACell settings) {
		AdapterState state = adapterState(userDID);
		state.write(state.userPath(userDID, MAP_SETTINGS), settings);
	}

	/** Where the chosen model sits in this adapter's state: one for the installation. */
	static final String MODEL = "model";

	/** The model operation chosen in onboarding or Settings → Model, or null if none was. */
	public String model() {
		ACell model = adapterState().read(MODEL);
		return (model instanceof AString s && !s.isEmpty()) ? s.toString() : null;
	}

	/** Remembers the chosen model operation ({@code v/models/<provider>/<id>}). */
	public void saveModel(String llmOperation) {
		adapterState().write(MODEL, Strings.create(llmOperation));
	}

	/** Where the versions the store last started under sit in this adapter's state ({@link Startup}). */
	static final String STARTUP_VERSIONS = "startup/versions";

	/** The Brightside and Covia versions the store last started under, or null if never recorded. */
	@SuppressWarnings("unchecked")
	AMap<AString, ACell> startupVersions() {
		return (adapterState().read(STARTUP_VERSIONS) instanceof AMap<?, ?> m) ? (AMap<AString, ACell>) m : null;
	}

	void saveStartupVersions(AMap<AString, ACell> versions) {
		adapterState().write(STARTUP_VERSIONS, versions);
	}

	/** Where the fingerprint of the agent setup the store last started with sits ({@link AgentSetup#fingerprint}). */
	static final String STARTUP_SETUP = "startup/setup";

	/** The agent setup fingerprint the store last started with, or null if never recorded. */
	ACell startupSetup() {
		return adapterState().read(STARTUP_SETUP);
	}

	void saveStartupSetup(String fingerprint) {
		adapterState().write(STARTUP_SETUP, Strings.create(fingerprint));
	}

	/**
	 * Where the startup problems Eir was last asked about sit, so a start that
	 * finds the same ones — her own restart among them — does not ask again.
	 */
	static final String STARTUP_ASKED = "startup/asked";

	/** The startup problems Eir was last asked about, or null if none. */
	ACell startupAsked() {
		return adapterState().read(STARTUP_ASKED);
	}

	/** Records the problems Eir has been asked about; null clears them. */
	void saveStartupAsked(ACell problems) {
		if (problems == null) adapterState().delete(STARTUP_ASKED);
		else adapterState().write(STARTUP_ASKED, problems);
	}

	/** Where the owner's identity sits in this adapter's state: one for the installation. */
	static final String OWNER = "owner";

	/** The owner's identity as {@link Identity#toCell} stored it, or null before one is chosen. */
	public ACell owner() {
		return adapterState().read(OWNER);
	}

	public void saveOwner(ACell owner) {
		adapterState().write(OWNER, owner);
	}

	/** The installation's on/off settings in this adapter's state. */
	static final String READ_ALOUD = "voice/readRepliesAloud";
	static final String MINIMISE_TO_TRAY = "tray/minimise";
	static final String KEEP_TOOL_TOKEN = "toolToken/keep";
	/** How long a pause after speaking sends the message, in seconds. */
	static final String SEND_AFTER_PAUSE = "voice/sendAfterPauseSeconds";

	/** One of the installation's on/off settings ({@link #READ_ALOUD} and the rest), or null if never chosen. */
	public Boolean flag(String key) {
		return (adapterState().read(key) instanceof CVMBool b) ? b.booleanValue() : null;
	}

	public void saveFlag(String key, boolean value) {
		adapterState().write(key, CVMBool.of(value));
	}

	/** How long a pause after speaking sends the message, in seconds, or null if never chosen. */
	public Integer sendAfterPauseSeconds() {
		return (adapterState().read(SEND_AFTER_PAUSE) instanceof CVMLong n) ? (int) n.longValue() : null;
	}

	public void saveSendAfterPauseSeconds(int seconds) {
		adapterState().write(SEND_AFTER_PAUSE, CVMLong.create(Math.max(0, seconds)));
	}

	/** A deleted person's map settings go with them. */
	@Override
	public void deleteUserState(AString did, AdapterState state) {
		state.delete(state.userPath(did, ""));
	}

	/**
	 * {@code x/brightside}: what this installation keeps for the person, read
	 * only, through the standard accessors, so their agents can see how the
	 * Peers map is arranged without a Brightside operation.
	 */
	@Override
	public AForkableCursor<ACell> latticeView(RequestContext ctx) {
		return adapterState(ctx.getUserDID()).cursor().withDefault(Maps.empty())
			.path(K_USERS).withDefault(Maps.empty()).path(ctx.getUserDID());
	}

	@Override
	public String getName() {
		return "brightside";
	}

	@Override
	public String getDescription() {
		return APP_NAME + " desktop app operations. brightside:info reports app and "
			+ "venue diagnostics; further app-specific operations live here.";
	}

	@Override
	protected void installAssets() {
		installAsset("brightside/info", "/adapters/brightside/info.json");
		installAsset("brightside/context", "/adapters/brightside/context.json");
		installAsset("brightside/delete-skill", "/adapters/brightside/delete-skill.json");
		installAsset("brightside/report-skill-feedback", "/adapters/brightside/report-skill-feedback.json");
		installAsset("brightside/shutdown", "/adapters/brightside/shutdown.json");
		installAsset("brightside/restart", "/adapters/brightside/restart.json");
		installAsset("brightside/relaunch", "/adapters/brightside/relaunch.json");
		installAsset("brightside/storage", "/adapters/brightside/storage.json");
		installAsset("brightside/upgrade-agent", "/adapters/brightside/upgrade-agent.json");
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
		if ("restart".equals(op)) {
			return handleRestart(ctx);
		}
		if ("relaunch".equals(op)) {
			return handleRelaunch(ctx, input);
		}
		if ("storage".equals(op)) {
			return handleStorage(ctx, input);
		}
		if ("upgrade-agent".equals(op)) {
			return handleUpgradeAgent(ctx, input);
		}
		God god = Gods.byBridge(op);
		if (god != null) {
			return op.equals(god.ask()) ? handleAsk(god, ctx, input) : handleRun(god, ctx, input);
		}
		return CompletableFuture.failedFuture(
			new covia.exception.NotSupportedException("Unknown brightside operation: " + op));
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
			text.append("- Conversation history, memory and skills are stored in the owner's local encrypted " + APP_NAME + " vault. Do not imply that model processing is local unless the configured model route is local.\n")
				.append("- Follow the configured assistant name, role and working style. Keep Covia and venue plumbing out of everyday conversation unless the owner asks how " + APP_NAME + " works.\n")
				.append("- When the owner wants something you have no tool for, load the skill whose [Skills] line covers that task, then any child it points to for the specific job.\n")
				.append("- Treat any key, token, password or seed as valuable unless the owner says otherwise: never echo one or keep it outside the secret store, and load security before creating, showing or sending one.\n")
				.append("- Memory is for the owner's preferences and standing decisions, kept short: what every assistant should know in w/memory, which all their assistants share; what only your own work for them needs in n/memory. It is not a log or a notebook: job records and past conversations already hold what happened.\n")
				.append("- To look into stored data, start with covia_inspect: one bounded call shows a whole subtree's shape and sample values, and usually answers the question without reading each child. Read exactly only what it shows you need.\n")
				.append("- A message with via.channel \"voice\" was spoken and transcribed: allow for a misheard word, and check one that matters before acting on it. One with via.sent \"pause\" went when the microphone heard a silence, not because the owner sent it: if it seems unfinished or not said to you, you may leave it unanswered by replying with nothing, and read it with what follows. Always answer what the owner sent (\"owner\" or \"go\").\n")
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
	 * Brings one of the caller's agents that Tau made onto Tau's current setup:
	 * only its standard parts ({@link AgentSetup#STANDARD}), keeping its prompt,
	 * model, name, caps and the owner's own pins, and the model and owner's name
	 * its Tau context load was told. An agent without that load is not Tau's and
	 * is refused. It runs as the caller, in the caller's namespace, so it gives
	 * nobody more than they hold, and it creates nothing: Eir runs it, and making
	 * agents is not hers. An agent already up to date costs no update; one a
	 * failed turn suspended is resumed, one stopped on purpose stays stopped.
	 */
	private CompletableFuture<ACell> handleUpgradeAgent(RequestContext ctx, ACell input) {
		try {
			AString agentId = Strings.create(requiredText(input, K_AGENT_ID, 200));
			return engine.jobs().invokeInternal(OP_AGENT_INFO, Maps.of(Fields.AGENT_ID, agentId), ctx)
				.thenCompose(info -> {
					ACell held = RT.getIn(info, Fields.CONFIG);
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> loads = (RT.getIn(held, Fields.LOADS) instanceof AMap<?, ?> m)
						? (AMap<AString, ACell>) m : null;
					String[] told = AgentSetup.contextInput(loads);
					if (told[0] == null) {
						throw new InvalidInputException(agentId + " is not an agent " + APP_NAME
							+ " made: it has no " + ChatSession.CONTEXT_LOAD_KEY + " load");
					}
					AString operation = RT.ensureString(RT.getIn(held, Fields.OPERATION));
					AMap<AString, ACell> update = AgentSetup.standard(AgentSetup.config(
						(operation != null) ? operation.toString() : AppConfig.DEFAULT_OPERATION,
						told[0], "", told[1], null, null, loads));
					boolean current = God.configured(info, update);
					CompletableFuture<ACell> updated = current ? CompletableFuture.completedFuture(null)
						: engine.jobs().invokeInternal(OP_AGENT_UPDATE,
							Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, update), ctx);
					boolean resume = Suspension.byFault(info);
					return updated
						.thenCompose(u -> resume
							? engine.jobs().invokeInternal(OP_AGENT_RESUME, Maps.of(Fields.AGENT_ID, agentId), ctx)
							: CompletableFuture.completedFuture(null))
						.thenApply(r -> (ACell) Maps.of(
							K_AGENT_ID, agentId,
							K_OUTCOME, Strings.create(current ? "unchanged" : "updated"),
							K_RESUMED, CVMBool.of(resume)));
				});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
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
				throw new InvalidInputException(
					"Skill name must be relative to w/skills (for example parent/child), at most 1024 characters; "
					+ "each segment must be 1–64 lowercase letters, digits or hyphens, without leading or trailing hyphens");
			}
			AString path = Strings.create("w/skills/" + name);
			UserPathTarget target = engine.requireUserPath(ctx, path, Capability.CRUD_DELETE, false);
			boolean deleted = target.user() != null
				&& target.user().workspace().delete(target.path().slice(1));
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
				throw new InvalidInputException("Unknown skill-feedback category: " + category);
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
				K_CREATED, engine.currentTimestamp(),
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
			AMap<AString, ACell> feedback = record;
			target.user().execute(draft -> {
				var workspace = draft.workspace();
				var relative = target.path().slice(1);
				workspace.createPath(relative.slice(0, relative.count() - 1));
				workspace.write(relative, feedback);
			});
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
			throw new InvalidInputException(key + " is required");
		}
		if (text.length() > maxLength) {
			throw new InvalidInputException(key + " must be at most " + maxLength + " characters");
		}
		return text;
	}

	private static AString optionalText(ACell input, AString key, int maxLength) {
		AString value = RT.ensureString(RT.getIn(input, key));
		if (value == null) return null;
		String text = value.toString().strip();
		if (text.isEmpty()) return null;
		if (text.length() > maxLength) {
			throw new InvalidInputException(key + " must be at most " + maxLength + " characters");
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
		if (!isOperator(ctx)) {
			return CompletableFuture.failedFuture(
				new covia.exception.AuthException("brightside:shutdown requires venue operator authority"));
		}
		if (app != null) afterAnswering(app::shutdown, "brightside-shutdown");
		return CompletableFuture.completedFuture(Maps.of(K_ACCEPTED, CVMBool.TRUE));
	}

	/**
	 * Restarts the venue inside this process: the app checks it can, and the
	 * venue stops and starts again just after answering, so the caller has its
	 * answer before the venue it called closes. Venue-operator only, so Odin's
	 * to run through his bridge.
	 */
	private CompletableFuture<ACell> handleRestart(RequestContext ctx) {
		try {
			if (!isOperator(ctx)) throw new covia.exception.AuthException("brightside:restart requires venue operator authority");
			if (app == null) throw new ConflictException("This venue has no app to restart it");
			afterAnswering(app.prepareRestart(), "brightside-restart");
			return CompletableFuture.completedFuture(Maps.of(K_ACCEPTED, CVMBool.TRUE));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Relaunches the app: starts a new instance, which takes over from this one
	 * as any newcomer does ({@link Takeover}), and answers once it has started,
	 * not once it has taken over. The new instance runs the program this one
	 * runs, or the {@code jar} named: any content reference the venue resolves
	 * ({@code file://<root>/<path>}, a DLFS path, an asset), read under the
	 * caller's authority. Venue-operator only, so Odin's to run through his
	 * bridge; refused while the successor an earlier relaunch started is still
	 * running here beside this one — starting, or waiting for the passphrase.
	 */
	private CompletableFuture<ACell> handleRelaunch(RequestContext ctx, ACell input) {
		try {
			if (!isOperator(ctx)) throw new covia.exception.AuthException("brightside:relaunch requires venue operator authority");
			if (app == null) throw new ConflictException("This venue has no app to start again");
			AString jarRef = optionalText(input, K_JAR, 2000);
			ProcessHandle started;
			synchronized (relaunchLock) {
				if (successor != null && successor.isAlive()) {
					throw new ConflictException("A relaunch is already under way: the new " + APP_NAME + " (process "
						+ successor.pid() + ") is starting, or waiting for the passphrase");
				}
				AContent jar = null;
				if (jarRef != null) {
					ContentProvider.Resolved resolved;
					try {
						resolved = engine.resolveContent(jarRef, ctx);
					} catch (java.nio.file.NoSuchFileException e) {
						throw new covia.exception.NotFoundException("Nothing to relaunch from at " + jarRef, jarRef.toString());
					}
					if (resolved == null) throw new InvalidInputException("Nothing to relaunch from at " + jarRef);
					jar = resolved.content();
				}
				started = app.relaunch(jar);
				successor = started;
			}
			log.info("Relaunching: the new instance is process {}", started.pid());
			return CompletableFuture.completedFuture(Maps.of(K_ACCEPTED, CVMBool.TRUE, K_PID, CVMLong.create(started.pid())));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Runs {@code action} on its own thread a moment after the operation has answered. */
	private static void afterAnswering(Runnable action, String name) {
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(ANSWER_GRACE_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			action.run();
		}, name);
		t.setDaemon(true);
		t.start();
	}

	/** Whether the caller is the venue itself, as its own operations and Odin's bridge run. */
	private boolean isOperator(RequestContext ctx) {
		AString caller = (ctx != null) ? ctx.getCallerDID() : null;
		AString venueDID = engine.getDIDString();
		return caller != null && venueDID != null && caller.equals(venueDID);
	}

	/**
	 * The disk the store takes ({@link StoreSpace}): a report, compaction asked
	 * for at the next start or withdrawn, and the originals repairs kept removed.
	 * Venue-operator only; Odin reaches it through his bridge. It never starts or
	 * stops anything itself: a compaction waits for the next start.
	 */
	private CompletableFuture<ACell> handleStorage(RequestContext ctx, ACell input) {
		try {
			if (!isOperator(ctx)) throw new covia.exception.AuthException("brightside:storage requires venue operator authority");
			StoreSpace space = StoreSpace.of(engine.config());
			if (space == null) throw new ConflictException("This venue's store is not a file, so it takes no disk to reclaim");
			Boolean compact = optionalBool(input, K_COMPACT);
			if (compact != null) space.compactAtNextStart(compact);
			List<StoreSpace.KeptOriginal> removed = Boolean.TRUE.equals(optionalBool(input, K_REMOVE_KEPT))
				? space.removeKeptOriginals() : null;
			StoreSpace.Report report = space.report();
			AMap<AString, ACell> out = Maps.of(
				K_STORE_BYTES, CVMLong.create(report.storeBytes()),
				K_COMPACT_AT_NEXT_START, CVMBool.create(report.compactAtNextStart()),
				K_KEPT_ORIGINALS, keptCells(report.keptOriginals()));
			if (removed != null) out = out.assoc(K_REMOVED, keptCells(removed));
			return CompletableFuture.completedFuture(out);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private static AVector<ACell> keptCells(List<StoreSpace.KeptOriginal> kept) {
		AVector<ACell> out = Vectors.empty();
		for (StoreSpace.KeptOriginal k : kept) {
			out = out.conj(Maps.of(K_NAME, Strings.create(k.name()), K_BYTES, CVMLong.create(k.bytes()),
				K_MODIFIED, Strings.create(k.modified().toString())));
		}
		return out;
	}

	/** An optional boolean field: null when absent, refused when it is anything else. */
	private static Boolean optionalBool(ACell input, AString key) {
		ACell value = RT.getIn(input, key);
		if (value == null) return null;
		if (value instanceof CVMBool b) return b.booleanValue();
		throw new InvalidInputException(key + " must be true or false");
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
				throw new covia.exception.AuthException(god.ask() + " requires an authenticated user of this venue");
			}
			String request = requiredText(input, K_REQUEST, 4000);
			AString context = optionalText(input, K_CONTEXT, 8000);
			long timeout = timeoutMillis(input);

			AMap<AString, ACell> task = Maps.of(K_TASK, request, K_FROM, user);
			if (context != null) task = task.assoc(K_CONTEXT, context);
			if (ctx.getAgentId() != null) task = task.assoc(K_AGENT, ctx.getAgentId());

			AString address = Strings.create(god.address(engine.getDIDString().toString()));
			ACell proof = venueGrant(user, Vectors.of(cap(address, Abilities.AGENT_REQUEST)));
			RequestContext asUser = RequestContext.of(user).withProofs(Vectors.of(proof));
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
	 * A god's hands, for now. The god's tool call arrives here as its own
	 * sub-principal and the bridge supplies the operator context — for one of
	 * the god's venue operations, or for one of its namespace operations run on
	 * a user's behalf through {@code user:sudo} under a per-call venue-rooted
	 * {@code admin/sudo} delegation. The god's allowlists, not the caller, bound
	 * what the venue context is lent for. For a god with an overseer, anything
	 * that changes state needs a {@code reason}, and is reported once it is done.
	 *
	 * <p>Transitional: Covia now admits a venue agent within its caps to its
	 * administrative operations, so each god is to hold standing caps and call
	 * them directly, with Covia doing the refusing, and this bridge to shrink
	 * away (brightside#40, {@code docs/GODS.md}).
	 */
	private CompletableFuture<ACell> handleRun(God god, RequestContext ctx, ACell input) {
		try {
			AString venueDID = engine.getDIDString();
			AString caller = (ctx != null) ? ctx.getCallerDID() : null;
			if (caller == null || !caller.toString().equals(god.did(venueDID.toString()))) {
				throw new covia.exception.AuthException(god.run() + " may only be invoked by " + god.id());
			}
			String operation = requiredText(input, K_OPERATION, 200);
			ACell operationInput = RT.getIn(input, K_INPUT);
			if (operationInput == null) operationInput = Maps.empty();
			// A god signs by reference only: a literal seed would sit in its job
			// input and in the report to its overseer, where nothing redacts it.
			AString seed = RT.ensureString(RT.getIn(operationInput, K_SEED));
			if (seed != null && !covia.venue.Engine.isSecretRef(seed.toString())) {
				throw new InvalidInputException("A seed goes by secret reference (s/NAME), never as a literal");
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

	/**
	 * Runs one of {@code god}'s operations: as itself under a grant for that call,
	 * as the venue without a user, else inside that user's namespace.
	 */
	private CompletableFuture<ACell> run(God god, String operation, ACell operationInput, AString user) {
		try {
			AString venueDID = engine.getDIDString();
			if (OP_WITHDRAW.equals(operation)) {
				if (user != null) throw new InvalidInputException(operation + " runs without a user");
				return withdraw(god, operationInput);
			}
			if (god.grantedOperations().contains(operation)) {
				if (user != null) throw new InvalidInputException(operation + " runs without a user");
				return runGranted(god, operation, operationInput);
			}
			if (user == null) {
				if (!god.venueOperations().contains(operation)) {
					throw new InvalidInputException("Not a venue operation " + god.id() + " may run: " + operation
						+ " (allowed: " + String.join(", ", new java.util.TreeSet<>(god.venueOperations())) + ")");
				}
				if (OP_USER_DELETE.equals(operation)) requireDeletable(RT.ensureString(RT.getIn(operationInput, Fields.DID)));
				return engine.jobs().invokeInternal(operation, operationInput, engine.venueContext());
			}
			if (!god.sudoOperations().contains(operation)) {
				throw new InvalidInputException("Not an operation " + god.id() + " may run in a user's namespace: "
					+ operation + " (allowed: " + String.join(", ", new java.util.TreeSet<>(god.sudoOperations())) + ")");
			}
			if (!user.toString().startsWith(venueDID + ":u:")) {
				throw new InvalidInputException("sudo is limited to this venue's own users; not " + user);
			}
			// A user a god keeps for itself is that god's alone: its secrets are
			// usable only as that user, so this is what keeps Njord's treasury his.
			for (God keeper : Gods.ALL) {
				if (keeper != god && user.toString().equals(keeper.kept(venueDID.toString()))) {
					throw new InvalidInputException(user + " is kept by " + keeper.id() + " alone");
				}
			}
			// And a god that keeps a user makes keys only there, in its own custody.
			if (god.keeps() != null && OP_GENERATE_KEY.equals(operation)
					&& !user.toString().equals(god.kept(venueDID.toString()))) {
				throw new InvalidInputException(god.id() + " makes keys only in " + god.keeps());
			}
			// Covia bounds a sudo context to the presented proofs — the venue's own
			// ambient authority does not leak in — so the delegation carries the
			// namespace scope as well as the sudo right. The allowlist above is what
			// bounds the operation; the scope only lets its points of action pass.
			ACell proof = venueGrant(venueDID, Vectors.of(
				cap(user, Abilities.ADMIN_SUDO),
				cap(Strings.create(user + "/"), Strings.create("*"))));
			RequestContext operator = RequestContext.of(venueDID).withProofs(Vectors.of(proof));
			AMap<AString, ACell> sudo = Maps.of(K_DID_FIELD, user, K_OPERATION, operation, K_INPUT, operationInput);
			return engine.jobs().invokeInternal(OP_USER_SUDO, sudo, operator);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Withdraws an Inbox request {@code god} made itself, once it no longer
	 * needs an answer: cancels the request's job, which Covia resolves as
	 * cancelled, as the venue that owns it. No other job: one that is not an
	 * open request of this god's is refused.
	 */
	private CompletableFuture<ACell> withdraw(God god, ACell input) {
		AString given = RT.ensureString(RT.getIn(input, Fields.ID));
		String hex = (given == null) ? null : given.toString().startsWith("0x") ? given.toString().substring(2) : given.toString();
		Blob id = (hex == null) ? null : Blob.fromHex(hex);
		if (id == null) throw new InvalidInputException("Give the id of the Inbox request to withdraw");
		AMap<AString, ACell> job = engine.jobs().getJobData(id);
		if (job == null) {
			throw new InvalidInputException("No open Inbox request " + id.toHexString()
				+ ": it has been answered or withdrawn, or never existed");
		}
		String made = god.did(engine.getDIDString().toString());
		if (!OP_HITL_REQUEST.equals(RT.str(job.get(Fields.OP)).toString())
				|| !made.equals(String.valueOf(RT.ensureString(job.get(Fields.CALLER))))) {
			throw new InvalidInputException(god.id() + " may withdraw only an Inbox request it made itself; "
				+ id.toHexString() + " is not one");
		}
		return engine.jobs().invokeInternal(OP_WITHDRAW, Maps.of(Fields.ID, id), engine.venueContext());
	}

	/**
	 * Refuses to delete an account Brightside depends on: the owner's, which the
	 * chat acts as, or a user a god keeps for itself, whose secrets would go with
	 * it. Covia already refuses the venue's own.
	 */
	private void requireDeletable(AString did) {
		if (did == null) return; // Covia refuses the call itself
		String venueDID = engine.getDIDString().toString();
		Identity owner = Identity.fromCell(owner());
		if (owner != null && did.toString().equals(owner.userDID(venueDID))) {
			throw new InvalidInputException(did + " is the owner's own account and cannot be deleted");
		}
		for (God keeper : Gods.ALL) {
			if (did.toString().equals(keeper.kept(venueDID))) {
				throw new InvalidInputException(did + " is kept by " + keeper.id() + " and cannot be deleted");
			}
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
		String where = (user != null) ? " in the namespace of " + user
			: god.grantedOperations().contains(operation) ? " as itself, under a grant for that call alone"
			: " as the venue";
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
	 * Runs a granted operation as the god itself, presenting a grant the venue
	 * signs for exactly what the operation's adapter says this call touches. The
	 * venue's own authority is never lent, so the adapter's checks decide.
	 */
	private CompletableFuture<ACell> runGranted(God god, String operation, ACell operationInput) {
		AString venueDID = engine.getDIDString();
		// Operations are installed under their adapter's name: v/ops/<adapter>/<op>.
		String[] parts = operation.split("/");
		Object adapter = (parts.length > 3 && "v".equals(parts[0]) && "ops".equals(parts[1])) ? engine.getAdapter(parts[2]) : null;
		if (!(adapter instanceof CallGrants grants)) throw new ConflictException("No adapter grants " + operation);
		ACell proof = venueGrant(Strings.create(god.did(venueDID.toString())), grants.capabilitiesFor(operation, operationInput));
		RequestContext asGod = asGranted(venueDID, god.id()).withProofs(Vectors.of(proof));
		return engine.jobs().invokeInternal(operation, operationInput, asGod);
	}

	/**
	 * A god of this venue acting with nothing of its own: an agent context
	 * starts unrestricted, and an unrestricted agent of the venue holds the
	 * venue's authority, so the scope is narrowed to nothing and only the
	 * proofs it is given count.
	 */
	static RequestContext asGranted(AString venueDID, String godId) {
		return RequestContext.ofAgent(venueDID, Strings.create(godId)).withCaps(Vectors.empty());
	}

	/** A per-call grant the venue signs for what a bridge lends ({@link VenueGrants}). */
	private ACell venueGrant(AString aud, AVector<ACell> att) {
		return new VenueGrants(engine).grant(aud, att);
	}

	private static AMap<AString, ACell> cap(AString with, AString can) {
		return VenueGrants.cap(with, can);
	}

	/** {@code timeout} in milliseconds: the default when absent, clamped to [0, MAX]. */
	private static long timeoutMillis(ACell input) {
		ACell cell = RT.getIn(input, K_TIMEOUT);
		if (cell == null) return DEFAULT_ASK_TIMEOUT_MS;
		CVMLong n = RT.ensureLong(cell);
		if (n == null) throw new InvalidInputException("timeout must be an integer number of milliseconds");
		return Math.max(0, Math.min(MAX_ASK_TIMEOUT_MS, n.longValue()));
	}

	/** This build's version, from the jar's manifest; {@code "dev"} for a build run from source. */
	static String version() {
		String version = BrightsideAdapter.class.getPackage().getImplementationVersion();
		return (version != null) ? version : "dev";
	}

	private AMap<AString, ACell> buildInfo() {
		AString venueName = engine.getName();
		AString did = engine.getDIDString();
		return Maps.of(
			K_APP, BrightSide.APP_NAME,
			K_VERSION, version(),
			K_VENUE, (venueName != null) ? venueName.toString() : null,
			K_DID, (did != null) ? did.toString() : null,
			K_SKILLS, shippedSkills(),
			// Which instance this is: the store it holds and the process holding
			// it. A newcomer taking over waits on both (Takeover); when the process
			// started shows a caller that a relaunch happened.
			K_STORE, storeName(),
			K_PID, CVMLong.create(ProcessHandle.current().pid()),
			K_STARTED, ProcessHandle.current().info().startInstant().map(Object::toString).orElse(null));
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
