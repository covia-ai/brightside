package brightside.chat;

import static brightside.BrightSide.APP_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import brightside.AppConfig;
import brightside.BrightsideSkillsAdapter;
import brightside.model.Providers;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.venue.Config;

/**
 * The setup Tau gives every agent it makes for the owner — the main assistant
 * and those made from a template — in one place, so creating an agent and
 * bringing one up to date agree ({@link ChatSession}, {@code brightside:install-agent}).
 *
 * <p>An agent's configuration has two kinds of part. Tau's {@link #STANDARD}
 * parts — the operation, tools, memory pins, skill sources and the Tau context
 * load — are Tau's to keep current. The rest — prompt, model, name, caps and
 * the owner's own pins — is written when the agent is created, and afterwards
 * only when a caller passes it, so an agent the owner has shaped keeps its own.
 * The gods are not made here ({@code God}).
 */
public final class AgentSetup {

	/** The configuration keys that are Tau's standard parts, kept current on an existing agent. */
	public static final Set<AString> STANDARD = Set.of(
		Fields.OPERATION, Strings.intern("defaultTools"), Strings.intern("tools"), Strings.intern("toolCallTimeoutMs"),
		Strings.intern("context"), Fields.LOADS, Strings.intern("skills"), Strings.intern("skillsets"),
		Strings.intern("acceptTasks"));

	private static final AString K_INPUT = Strings.intern("input");
	private static final AString K_MODEL_OPERATION = Strings.intern("modelOperation");
	private static final AString K_USER_NAME = Strings.intern("userName");

	private AgentSetup() {
	}

	/**
	 * Tau's whole configuration for an agent: what creating it writes, and what
	 * the main assistant has re-applied at each start.
	 *
	 * @param operation    the transition operation
	 * @param llmOperation the model, as a catalogue path
	 * @param systemPrompt its instructions
	 * @param userName     the owner's display name, told to the agent, or null
	 * @param agentName    the name it goes by ({@code config.name}), or null to leave the record's
	 * @param pinnedSkill  a skill pinned from the start, or null
	 * @param existing     the loads it already has, whose owner pins are kept, or null
	 */
	public static AMap<AString, ACell> config(String operation, String llmOperation, String systemPrompt,
			String userName, String agentName, String pinnedSkill, AMap<AString, ACell> existing) {
		Providers.Route route = Providers.route(llmOperation);
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, operation,
			"llmOperation", route.operation(),
			"model", route.model(),
			"systemPrompt", systemPrompt,
			// Read-only workspace access (covia read/list) on top of the tools
			// below — so it can look into its own namespace out of the box.
			"defaultTools", true,
			// The bounded inspect (the default pack stops at read and list), the
			// memory tool, a narrow append-only channel for concrete skill misses,
			// and the harness's compact control so a long conversation can be
			// summarised in place. None exposes general workspace mutation.
			// Broader capabilities (writes, HTTP, files, agents, telegram/discord…)
			// arrive by discovering and loading the skills that grant them — see
			// skillsets below — so authority stays deliberate rather than always-on.
			"tools", Vectors.of(Strings.create(ChatSession.INSPECT_OP), Strings.create(ChatSession.MEMORY_OP),
				Strings.create(ChatSession.SKILL_FEEDBACK_OP), Strings.create(ChatSession.COMPACT_TOOL)),
			"toolCallTimeoutMs", ChatSession.TOOL_CALL_TIMEOUT_MS,
			// Takes tasks (agent:request) as well as chat: schedules, other agents and the gods hand it work.
			"acceptTasks", true,
			// Pin memory into every turn's context, through the read-only recall op;
			// the memory tool above is for edits. What the owner is like, shared by
			// all their assistants (w/memory), then this one's own (n/memory).
			"context", Vectors.of(
				Maps.of(
					"op", ChatSession.MEMORY_RECALL_OP,
					"input", Maps.of("path", ChatSession.SHARED_MEMORY_PATH),
					"label", "What you know about the user, shared with their other assistants and empty until one "
						+ "records something — edit with path " + ChatSession.SHARED_MEMORY_PATH),
				Maps.of(
					"op", ChatSession.MEMORY_RECALL_OP,
					"input", Maps.of("path", ChatSession.MEMORY_PATH),
					"label", "Your private memory of the user, empty until you record something — edit with path "
						+ ChatSession.MEMORY_PATH)),
			// No Brightside skill is pinned by default. The one app-owned load is a
			// read-only context operation; owner pins are preserved and shipped skills
			// are selected from their descriptions on demand.
			"loads", loads(existing, pinnedSkill, llmOperation, userName),
			// Replace the old two-entry compatibility vector. Brightside's complete
			// skillset below is an explicit source, so lookup does not depend on Covia
			// #415's unresolved operator-pin child expansion.
			"skills", Vectors.empty(),
			// Discovery surface: the user's own skills plus the venue's shipped
			// library and Brightside's everyday-work skills. The agent loads only the
			// body (and any tools) relevant to the current task.
			"skillsets", Vectors.of(
				Strings.create(ChatSession.USER_SKILLSET),
				Strings.create(BrightsideSkillsAdapter.SKILLSET),
				Strings.create(ChatSession.VENUE_SKILLSET)),
			// The owner's own assistant may do whatever the owner can in their
			// namespace; an agent left without caps could call no tool at all.
			"caps", Config.UNRESTRICTED);
		if (agentName != null) config = config.assoc(Strings.create("name"), Strings.create(agentName));
		return config;
	}

	/** Owner pins plus the Tau context load, which tells the agent its model and the owner's name. */
	static AMap<AString, ACell> loads(AMap<AString, ACell> existing, String pinnedSkill, String modelOperation,
			String userName) {
		AMap<AString, ACell> loads = (existing != null) ? existing : Maps.empty();
		if (pinnedSkill != null && !loads.containsKey(Strings.create(pinnedSkill))) {
			loads = loads.assoc(Strings.create(pinnedSkill), Maps.of(
				"skill", CVMBool.TRUE,
				"budget", 8000L,
				"label", pinnedSkill.substring(pinnedSkill.lastIndexOf('/') + 1)));
		}
		AMap<AString, ACell> input = Maps.of(K_MODEL_OPERATION, modelOperation);
		if (userName != null && !userName.isBlank()) input = input.assoc(K_USER_NAME, Strings.create(userName));
		return loads.assoc(Strings.create(ChatSession.CONTEXT_LOAD_KEY), Maps.of(
			"op", ChatSession.CONTEXT_OP,
			"input", input,
			"label", APP_NAME + " application context",
			"budget", 4000L,
			"volatile", CVMBool.FALSE));
	}

	/** {@code config}'s standard parts only: what bringing an existing agent up to date writes. */
	public static AMap<AString, ACell> standard(AMap<AString, ACell> config) {
		AMap<AString, ACell> parts = Maps.empty();
		for (Map.Entry<AString, ACell> e : config.entrySet()) {
			if (STANDARD.contains(e.getKey())) parts = parts.assoc(e.getKey(), e.getValue());
		}
		return parts;
	}

	/**
	 * What an existing agent's Tau context load was told — its model and the
	 * owner's name — so bringing it up to date keeps them: {@code [modelOperation, userName]},
	 * each null where the load is missing or does not say.
	 */
	public static String[] contextInput(AMap<AString, ACell> loads) {
		ACell input = (loads != null) ? convex.core.lang.RT.getIn(loads, ChatSession.CONTEXT_LOAD_KEY, K_INPUT) : null;
		ACell model = convex.core.lang.RT.getIn(input, K_MODEL_OPERATION);
		ACell user = convex.core.lang.RT.getIn(input, K_USER_NAME);
		return new String[] { (model != null) ? model.toString() : null, (user != null) ? user.toString() : null };
	}

	/**
	 * A fingerprint of what Tau would bring an agent up to date with: the
	 * standard parts and every shipped skill. A start that finds it changed has
	 * something new for the owner's agents ({@code Eir}).
	 */
	public static String fingerprint() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			AMap<AString, ACell> sample = standard(config(AppConfig.DEFAULT_OPERATION, AppConfig.DEFAULT_LLM_OPERATION,
				"", null, null, null, null));
			digest.update(sample.getEncoding().getBytes());
			for (String path : BrightsideSkillsAdapter.SHIPPED) {
				String resource = "/brightside/skills/" + path.substring(path.lastIndexOf('/') + 1) + ".json";
				try (InputStream in = AgentSetup.class.getResourceAsStream(resource)) {
					if (in == null) throw new IllegalStateException("Shipped skill missing: " + resource);
					digest.update(in.readAllBytes());
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new IllegalStateException("Could not fingerprint Tau's agent setup", e);
		}
	}
}
