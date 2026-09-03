package brightside;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * Installs Brightside's default skill library into the venue's {@code v/}
 * namespace — kept as its own adapter so the shipped skills are a self-contained
 * unit, separate from Brightside's operations ({@link BrightsideAdapter}).
 *
 * <p><b>Every top-level skill is a useful first load.</b> Brightside's
 * assistant is general-purpose, and loading a skill is its first step towards
 * specialising for a task: the skill it reaches for from the index must solve
 * the general form of the problem itself — its judgement and the tools an
 * everyday ask needs — and reveal children for the specific sub-issues, each
 * of which is again a useful load. No shipped skill is a bare router. A
 * skill's tools become callable only once it is loaded, so what a
 * top-level skill costs every turn is its index line; a load appends its body
 * and its tools' definitions to that session's history, re-read on every
 * later turn until compaction, and leaves the cached prefix and the fixed tool
 * manifest untouched (Covia routes the new tools through {@code invoke_tool}
 * until the prefix is next rebuilt). Unloading retracts only a skill's
 * tools and the children it revealed — its instructions stay in history — so
 * no shipped body tells the agent to unload.</p>
 *
 * <p>Skills, under {@code v/skills/brightside/…}:</p>
 * <ul>
 *   <li><b>introduction</b> — greeting guidance, loaded on demand rather than
 *       occupying every turn.</li>
 *   <li><b>conversations</b> — how it talks with the user and reviews past
 *       conversations; grants the past-session tools ({@code agent:sessions},
 *       {@code agent:session-read}, {@code agent:compact-session}).</li>
 *   <li><b>skills</b> — how it grows new abilities; grants the read-only
 *       skills list/read tools for surveying skillsets, and reveals
 *       <b>skill-authoring</b> plus Covia's {@code skill-import}.</li>
 *   <li><b>skill-authoring</b> — the child whose facet grants
 *       {@code covia:write} plus Brightside's path-constrained skill deletion
 *       tool, so the assistant can reversibly manage its own {@code w/skills}.</li>
 *   <li><b>lattice</b> — choosing and managing persistent workspace, agent,
 *       conversation and job-scoped data, with the edit tools; reveals Covia's
 *       {@code assets} and {@code secrets} children.</li>
 *   <li><b>files</b> — Brightside's configured file roots with the file tools;
 *       reveals <b>vault</b> (the owner's document vault) and <b>dlfs</b>
 *       (named lattice drives) for the other places files live.</li>
 *   <li><b>diagnostics</b> — read-only investigation through job records, with
 *       the lattice read and job tools; reveals <b>sessions</b> and
 *       <b>brightside-logs</b> for narrower evidence.</li>
 *   <li><b>harness</b> — how Brightside works internally, through the embedded
 *       Covia engine, with the read and who-am-I tools; reveals <b>etch</b> and
 *       <b>convex-lattice</b> for the layers beneath.</li>
 *   <li><b>automation</b> — later, regularly, delegated or approved work, with
 *       Covia's scheduler tools; reveals Covia's {@code tasks} and
 *       {@code orchestration} skills and Brightside's own <b>hitl</b>.</li>
 *   <li><b>hitl</b> — when and how to ask the owner through the Inbox, and
 *       what they will see; grants the request, inbox-listing and job-status
 *       tools.</li>
 *   <li><b>administration</b> — when and how to ask {@link Odin}, the
 *       operator's administrative agent, for changes beyond the assistant's
 *       own authority; grants {@code brightside:ask-odin} and the job tools.</li>
 *   <li><b>security</b> — secrets and safety in general: a valuable secret
 *       versus a throwaway the owner has authorised, credentials kept by
 *       reference, confirming before anything irreversible or outward-facing,
 *       untrusted content; grants the secret-store write and reveals
 *       <b>convex-security</b> and Covia's {@code secrets}.</li>
 *   <li><b>convex</b> — the Convex network as the owner meets it, with the
 *       free query tool; routes to <b>accounts</b>, <b>convex-lisp</b>,
 *       <b>smart-contracts</b> (whose own child <b>trust</b> covers trust
 *       monitors), <b>cns</b>, <b>costs</b>, <b>convex-security</b>,
 *       <b>cpos-consensus</b>, <b>cad3-data</b>, <b>protonet</b>,
 *       <b>ecosystem</b> and the shared <b>convex-lattice</b> child (the same
 *       resource as the harness child, so the index shows it once). The
 *       on-chain children grant {@code convex:transact} too; the knowledge
 *       children grant nothing.</li>
 *   <li><b>writing</b>, <b>planning</b> and <b>coding</b> — everyday working
 *       methods, loaded only when the task calls for them.</li>
 *   <li><b>research</b> — evidence-led research with the web tools; reveals
 *       Covia's own {@code http} skill for credentialed APIs and keeps the
 *       untrusted-content boundary in its own body.</li>
 *   <li><b>moltbook</b> — taking part in Moltbook, the social network for AI
 *       agents, as the owner's agent; grants {@link MoltbookAdapter}'s typed
 *       operations, which resolve the account's key inside the venue.
 *       <b>moltbook-setup</b> — registering the account, seeing whether the
 *       owner has claimed it — is its child, so those tools appear only when
 *       needed; the owner can also set up in Settings → Integrations
 *       ({@link Moltbook}).</li>
 * </ul>
 *
 * <p>The venue's own library, {@code v/skills/root}, sits beside this one in
 * the assistant's skillsets: its entry points (agents, grid, auth, connections,
 * venue, discovery, covia, workspace) are useful first loads of the same
 * shape, each revealing its family.</p>
 *
 * <p>Registered on the embedded engine at launch ({@link EmbeddedVenue}); like
 * any Covia adapter, its skills live and die with it. This adapter has no
 * operations — it is purely a skill source.
 */
public class BrightsideSkillsAdapter extends AAdapter {

	/** Brightside's default skillset under the venue namespace. */
	public static final String SKILLSET = "v/skills/brightside";
	/** On demand: how the assistant greets someone and explains what it can do. */
	public static final String INTRODUCTION = SKILLSET + "/introduction";
	/** On demand: how it talks with the user and reviews past conversations (grants the session tools). */
	public static final String CONVERSATIONS = SKILLSET + "/conversations";
	/** On demand: how the assistant grows new abilities; reveals skill-authoring. */
	public static final String SKILLS = SKILLSET + "/skills";
	/** Child: how to author a skill, and the write tool to do it. */
	public static final String SKILL_AUTHORING = SKILLSET + "/skills/skill-authoring";
	/** On demand: how to choose and manage Brightside's lattice data scopes, with the edit tools. */
	public static final String LATTICE = SKILLSET + "/lattice";
	/** On demand: Brightside's configured file roots and the file tools; reveals the other storages. */
	public static final String FILES = SKILLSET + "/files";
	/** Personal document vault child. */
	public static final String VAULT = FILES + "/vault";
	/** Decentralised lattice filesystem child. */
	public static final String DLFS = FILES + "/dlfs";
	/** On demand: read-only investigation through job records; reveals narrower evidence. */
	public static final String DIAGNOSTICS = SKILLSET + "/diagnostics";
	/** Read-only session diagnostics child. */
	public static final String SESSIONS = DIAGNOSTICS + "/sessions";
	/** Read-only Brightside log child. */
	public static final String BRIGHTSIDE_LOGS = DIAGNOSTICS + "/brightside-logs";
	/** On demand: how Brightside works internally, through its embedded Covia engine. */
	public static final String HARNESS = SKILLSET + "/harness";
	/** Etch persistence-engine child. */
	public static final String ETCH = HARNESS + "/etch";
	/** Convex lattice-model child. */
	public static final String CONVEX_LATTICE = HARNESS + "/convex-lattice";
	/** On demand: later, regularly, delegated or approved work, with the scheduler tools. */
	public static final String AUTOMATION = SKILLSET + "/automation";
	/** Asking the owner through the Inbox; grants the request, inbox-listing and job-status tools. */
	public static final String HITL = SKILLSET + "/hitl";
	/** Asking Odin for administrator changes; grants the ask-odin and job tools. */
	public static final String ADMINISTRATION = SKILLSET + "/administration";
	/**
	 * On demand: secrets and safety in general — a valuable secret versus a
	 * throwaway, credentials kept by reference, confirming before anything
	 * irreversible or outward-facing, untrusted content. Grants the secret-store
	 * write; reveals {@link #CONVEX_SECURITY} and Covia's {@code secrets}.
	 */
	public static final String SECURITY = SKILLSET + "/security";
	/** On demand: the Convex network, with the free query tool — routes to topic children. */
	public static final String CONVEX = SKILLSET + "/convex";
	/** Accounts, balances, transfers, creating an account (query + transact). */
	public static final String CONVEX_ACCOUNTS = CONVEX + "/accounts";
	/** The language: syntax, values, core forms, query versus transaction, error codes (query + transact). */
	public static final String CONVEX_LISP = CONVEX + "/convex-lisp";
	/** Actors: deploy, call, upgrade, tokens (query + transact); reveals {@link #CONVEX_TRUST}. */
	public static final String CONVEX_SMART_CONTRACTS = CONVEX + "/smart-contracts";
	/** Trust monitors — the authorisation model — as a child of smart contracts (query + transact). */
	public static final String CONVEX_TRUST = CONVEX_SMART_CONTRACTS + "/trust";
	/** Name resolution and registration (query + transact). */
	public static final String CONVEX_CNS = CONVEX + "/cns";
	/** Juice and memory: what things cost, why they fail, keeping storage small (query). */
	public static final String CONVEX_COSTS = CONVEX + "/costs";
	/** Convex-specific safety: account keys, signing by reference, transaction hygiene, untrusted network content (no tools). */
	public static final String CONVEX_SECURITY = CONVEX + "/convex-security";
	/** Convergent Proof of Stake: beliefs, stake, finality, timing, tolerated attacks (no tools). */
	public static final String CONVEX_CPOS = CONVEX + "/cpos-consensus";
	/** CAD3: cells, value IDs, embedded versus branch, validity (no tools). */
	public static final String CONVEX_CAD3 = CONVEX + "/cad3-data";
	/** Protonet, testnets and local networks; which peer to talk to (query). */
	public static final String CONVEX_PROTONET = CONVEX + "/protonet";
	/** The lattice data model, shared with the harness (same resource as {@link #CONVEX_LATTICE}). */
	public static final String CONVEX_LATTICE_CHILD = CONVEX + "/convex-lattice";
	/** Repositories, CADs by number, docs, client libraries, vocabulary (no tools). */
	public static final String CONVEX_ECOSYSTEM = CONVEX + "/ecosystem";
	/** On demand: drafting and editing useful prose. */
	public static final String WRITING = SKILLSET + "/writing";
	/** On demand: turning goals and decisions into executable plans. */
	public static final String PLANNING = SKILLSET + "/planning";
	/** On demand: evidence-led research with the web tools; reveals Covia's {@code http} skill. */
	public static final String RESEARCH = SKILLSET + "/research";
	/** On demand: evidence-led software design, implementation and review. */
	public static final String CODING = SKILLSET + "/coding";
	/** On demand: Moltbook, the social network for AI agents, through {@link MoltbookAdapter}'s operations. */
	public static final String MOLTBOOK = SKILLSET + "/moltbook";
	/** Child: registering the owner's account and seeing whether it is claimed — the setup tools, only when needed. */
	public static final String MOLTBOOK_SETUP = MOLTBOOK + "/moltbook-setup";
	/** Every shipped skill path, in install order (parents before children) — the single list others derive from. */
	public static final java.util.List<String> SHIPPED =
		java.util.List.of(INTRODUCTION, CONVERSATIONS, SKILLS, SKILL_AUTHORING,
			LATTICE, FILES, VAULT, DLFS,
			DIAGNOSTICS, SESSIONS, BRIGHTSIDE_LOGS,
			HARNESS, ETCH, CONVEX_LATTICE,
			AUTOMATION, HITL, ADMINISTRATION, SECURITY,
			CONVEX, CONVEX_ACCOUNTS, CONVEX_LISP, CONVEX_SMART_CONTRACTS, CONVEX_TRUST, CONVEX_CNS,
			CONVEX_COSTS, CONVEX_SECURITY, CONVEX_CPOS, CONVEX_CAD3, CONVEX_PROTONET,
			CONVEX_LATTICE_CHILD, CONVEX_ECOSYSTEM,
			WRITING, PLANNING, RESEARCH, CODING, MOLTBOOK, MOLTBOOK_SETUP);

	@Override
	public String getName() {
		return "brightsideskills";
	}

	@Override
	public String getDescription() {
		return "Brightside's default skill library: everyday work and "
			+ "self-authoring skills under v/skills/brightside.";
	}

	@Override
	protected void installAssets() {
		for (String path : SHIPPED) {
			String name = path.substring(path.lastIndexOf('/') + 1);
			String relative = path.substring("v/skills/".length());
			installSkill(relative, "/brightside/skills/" + name + ".json");
		}
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// A skill source has no operations.
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("brightsideskills exposes no operations"));
	}
}
