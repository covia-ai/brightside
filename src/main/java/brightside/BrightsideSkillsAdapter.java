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
 * <p>Skills, under {@code v/skills/brightside/…}:</p>
 * <ul>
 *   <li><b>introduction</b> — greeting guidance, loaded on demand rather than
 *       occupying every turn.</li>
 *   <li><b>conversations</b> — how it talks with the user and reviews past
 *       conversations. Loaded on demand, <em>not</em> pinned: its facet grants
 *       the read-only past-session tools ({@code agent:sessions},
 *       {@code agent:session-read}).</li>
 *   <li><b>skills</b> — how it grows new abilities (on demand); grants the
 *       read-only skills list/read tools for surveying skillsets, and its
 *       {@code skill.skills} facet reveals <b>skill-authoring</b> plus Covia's
 *       {@code skill-import} (SKILL.md files).</li>
 *   <li><b>skill-authoring</b> — the gated child skill (on demand) whose facet
 *       grants {@code covia:write} plus Brightside's path-constrained skill
 *       deletion tool, so the assistant can reversibly manage skills in its
 *       own {@code w/skills}.</li>
 *   <li><b>lattice</b> — owner-facing guidance for choosing and managing
 *       persistent workspace, agent, conversation and job-scoped data;
 *       reveals Covia's {@code assets} child for immutable, shareable
 *       snapshots.</li>
 *   <li><b>vault-drives-files</b> — routes file work to separate personal
 *       vault, DLFS-drive and host-filesystem child skills.</li>
 *   <li><b>diagnostics-audit-logs</b> — routes read-only investigation to
 *       separate job, session and Brightside-log child skills.</li>
 *   <li><b>harness</b> — explains Brightside's technical foundation through
 *       separate Covia-engine, Etch and Convex-lattice child skills.</li>
 *   <li><b>tasks-scheduler-automation</b> — routes delegated work, reminders
 *       and repeatable workflows to Covia's task, scheduling and orchestration
 *       skills, and human decisions to Brightside's own <b>hitl</b>.</li>
 *   <li><b>hitl</b> — when and how to ask the owner through the Inbox, and
 *       what they will see. Shadows Covia's skill of the same name in the
 *       index; grants the request, inbox-listing and job-status tools.</li>
 *   <li><b>administration</b> — when and how to ask {@link Odin}, the
 *       operator's administrative agent, for changes beyond the assistant's
 *       own authority; grants {@code brightside:ask-odin} and the job tools.</li>
 *   <li><b>convex</b> — the Convex network as the owner meets it; routes to
 *       <b>accounts</b>, <b>convex-lisp</b>, <b>smart-contracts</b> (whose own
 *       child <b>trust</b> covers trust monitors), <b>cns</b>, <b>costs</b>,
 *       <b>security</b>, <b>cpos-consensus</b>, <b>cad3-data</b>,
 *       <b>protonet</b>, <b>ecosystem</b> and the shared <b>convex-lattice</b>
 *       child (the same resource as the harness child, so the index shows it
 *       once). The on-chain children grant {@code convex:query} /
 *       {@code convex:transact}; the knowledge children (security, consensus,
 *       encoding, ecosystem) grant nothing, and security is loaded before
 *       anything that signs.</li>
 *   <li><b>writing</b>, <b>planning</b>, <b>research</b> and <b>coding</b> —
 *       everyday working methods, loaded only when the task calls for them;
 *       research reveals a guarded HTTP child for external evidence.</li>
 * </ul>
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
	/** On demand: how the assistant grows new abilities; gates skill-authoring. */
	public static final String SKILLS = SKILLSET + "/skills";
	/** Gated child: how to author a skill, and the write tool to do it. */
	public static final String SKILL_AUTHORING = SKILLSET + "/skills/skill-authoring";
	/** On demand: how to choose and manage Brightside's lattice data scopes. */
	public static final String LATTICE = SKILLSET + "/lattice";
	/** On demand: routes file-shaped work to the appropriate storage child. */
	public static final String VAULT_DRIVES_FILES = SKILLSET + "/vault-drives-files";
	/** Personal document vault child. */
	public static final String VAULT = VAULT_DRIVES_FILES + "/vault";
	/** Decentralised lattice filesystem child. */
	public static final String DLFS = VAULT_DRIVES_FILES + "/dlfs";
	/** Configured host and temporary filesystem roots child. */
	public static final String FILES = VAULT_DRIVES_FILES + "/files";
	/** On demand: routes read-only operational investigation to focused children. */
	public static final String DIAGNOSTICS_AUDIT_LOGS = SKILLSET + "/diagnostics-audit-logs";
	/** Read-only job audit child. */
	public static final String JOBS = DIAGNOSTICS_AUDIT_LOGS + "/jobs";
	/** Read-only session diagnostics child. */
	public static final String SESSIONS = DIAGNOSTICS_AUDIT_LOGS + "/sessions";
	/** Read-only Brightside log child. */
	public static final String BRIGHTSIDE_LOGS = DIAGNOSTICS_AUDIT_LOGS + "/brightside-logs";
	/** On demand: routes questions about Brightside's internal harness layers. */
	public static final String HARNESS = SKILLSET + "/harness";
	/** Covia venue-engine child. */
	public static final String COVIA_ENGINE = HARNESS + "/covia-engine";
	/** Etch persistence-engine child. */
	public static final String ETCH = HARNESS + "/etch";
	/** Convex lattice-model child. */
	public static final String CONVEX_LATTICE = HARNESS + "/convex-lattice";
	/** On demand: routes tasks, schedules, automation and human checkpoints. */
	public static final String TASKS_SCHEDULER_AUTOMATION = SKILLSET + "/tasks-scheduler-automation";
	/** Asking the owner through the Inbox; grants the request, inbox-listing and job-status tools. */
	public static final String HITL = SKILLSET + "/hitl";
	/** Asking Odin for administrator changes; grants the ask-odin and job tools. */
	public static final String ADMINISTRATION = SKILLSET + "/administration";
	/** On demand: the Convex network — routes to topic children with their tools. */
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
	/** Keys, what must never be revealed, transaction hygiene, untrusted network content (no tools). */
	public static final String CONVEX_SECURITY = CONVEX + "/security";
	/** Convergent Proof of Stake: beliefs, stake, finality, timing, tolerated attacks (no tools). */
	public static final String CONVEX_CPOS = CONVEX + "/cpos-consensus";
	/** CAD3: cells, value IDs, embedded versus branch, validity (no tools). */
	public static final String CONVEX_CAD3 = CONVEX + "/cad3-data";
	/** Protonet, testnets and local networks; which peer to talk to (query). */
	public static final String CONVEX_PROTONET = CONVEX + "/protonet";
	/** The lattice data model, shared with the harness router (same resource as {@link #CONVEX_LATTICE}). */
	public static final String CONVEX_LATTICE_CHILD = CONVEX + "/convex-lattice";
	/** Repositories, CADs by number, docs, client libraries, vocabulary (no tools). */
	public static final String CONVEX_ECOSYSTEM = CONVEX + "/ecosystem";
	/** On demand: drafting and editing useful prose. */
	public static final String WRITING = SKILLSET + "/writing";
	/** On demand: turning goals and decisions into executable plans. */
	public static final String PLANNING = SKILLSET + "/planning";
	/** On demand: evidence-led research with honest source handling. */
	public static final String RESEARCH = SKILLSET + "/research";
	/** External web and API access child, with an explicit untrusted-content boundary. */
	public static final String RESEARCH_HTTP = RESEARCH + "/http";
	/** On demand: evidence-led software design, implementation and review. */
	public static final String CODING = SKILLSET + "/coding";
	/** On demand: connect a third-party service (token in the vault) and route to
	 *  the venue's connection skills that grant its HTTP tools. */
	public static final String CONNECTIONS = SKILLSET + "/connections";
	/** Every shipped skill path, in install order — the single list others derive from. */
	public static final java.util.List<String> SHIPPED =
		java.util.List.of(INTRODUCTION, CONVERSATIONS, SKILLS, SKILL_AUTHORING,
			LATTICE, VAULT_DRIVES_FILES, VAULT, DLFS, FILES,
			DIAGNOSTICS_AUDIT_LOGS, JOBS, SESSIONS, BRIGHTSIDE_LOGS,
			HARNESS, COVIA_ENGINE, ETCH, CONVEX_LATTICE,
			TASKS_SCHEDULER_AUTOMATION, HITL, ADMINISTRATION,
			CONVEX, CONVEX_ACCOUNTS, CONVEX_LISP, CONVEX_SMART_CONTRACTS, CONVEX_TRUST, CONVEX_CNS,
			CONVEX_COSTS, CONVEX_SECURITY, CONVEX_CPOS, CONVEX_CAD3, CONVEX_PROTONET,
			CONVEX_LATTICE_CHILD, CONVEX_ECOSYSTEM,
			WRITING, PLANNING, RESEARCH, RESEARCH_HTTP, CODING, CONNECTIONS);

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
