package brightside;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brightside.model.Providers;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.Engine;

/**
 * A god: one of the venue's own agents, specialised in one job on the owner's
 * behalf. A god is owned by the venue principal, not by any user — address
 * {@code <venueDID>/g/<id>}, DID {@code <venueDID>:g:<id>} — and answers to the
 * owner alone, asking them through the Inbox.
 *
 * <p>A god is a selection of skills plus standing Covia caps. What it may do is
 * its {@code caps}: {@link #BROAD_CAPS}, which every god holds, and its own
 * {@link #powers}, Covia's administrative abilities over the venue's
 * resources. Covia checks them at every point of action, so a tool the god
 * holds or loads does nothing its caps do not cover. Its tools are a few it
 * always needs plus those of the {@link #skills} it loads; its system prompt
 * carries the judgement.
 *
 * <p>Each god also has two Brightside bridges, both handled once for every god
 * in {@link BrightsideAdapter}: one by which the owner's assistants reach it
 * ({@link #ask}), submitted as the calling user so the god sees who asked, and
 * one through which it acts where standing caps cannot yet say what it may
 * do ({@link #run}), callable by that god alone. The run bridge lends the
 * venue's authority for one of the god's {@link #venueOperations}: a change
 * its {@link #overseer} must hear of, or one that needs a check Covia does not
 * make; for one of its {@link #sudoOperations} inside a user's namespace on
 * their behalf, which no standing cap reaches; or one of its
 * {@link #grantedOperations} as itself, under a grant for what that call
 * touches and nothing more. A god with an overseer answers to it after the
 * fact: each change it makes through its run bridge carries a reason and is
 * reported to the overseer as soon as it is done. A god may also keep a
 * principal of its own ({@link #keeps}), a user of the venue that only its run
 * bridge may act as — Njord's treasury, whose secret store holds keys no
 * assistant can use. The gods are listed in {@link Gods}; see
 * {@code docs/GODS.md}.
 *
 * <p>What remains of the run bridge is to give way to Covia as Covia learns to
 * say it (brightside#40).
 *
 * @param id              the agent id, also the god's name
 * @param ask             the sub-operation of {@code brightside:} by which assistants reach it
 * @param run             the sub-operation of {@code brightside:} through which it acts
 * @param overseer        the id of the god its changes are reported to, or null
 * @param keeps           the slug of the user it keeps for itself ({@code <venueDID>:u:<slug>}), or null
 * @param systemPrompt    who it is and the policy it applies
 * @param remembers       what its pinned memory holds, for the pin's label
 * @param tools           the tools it always holds, besides its run bridge and memory
 * @param skills          the skills it is offered by name, beside the venue's library, each bringing its tools
 * @param powers          its caps beyond {@link #BROAD_CAPS}: what it may do as the venue, checked by Covia
 * @param venueOperations what its run bridge lends the venue's authority for
 * @param sudoOperations  what it may run inside a user's namespace, on their behalf
 * @param grantedOperations what it may run as itself, granted per call by the operation's adapter ({@link CallGrants})
 */
public record God(String id, String ask, String run, String overseer, String keeps, String systemPrompt,
		String remembers, List<String> tools, List<String> skills, AVector<ACell> powers,
		Set<String> venueOperations, Set<String> sudoOperations, Set<String> grantedOperations) {

	private static final Logger log = LoggerFactory.getLogger(God.class);

	private static final String OPS = "v/ops/brightside/";
	private static final String MEMORY_OP = "v/ops/memory";
	/** The read-only render a context entry may run; {@link #MEMORY_OP} is the tool that edits. */
	static final String MEMORY_RECALL_OP = "v/ops/memory-recall";
	private static final String MEMORY_PATH = "n/memory";
	private static final long ADMIN_TIMEOUT_SECONDS = 30;

	private static final AString K_CAPS = Strings.intern("caps");

	/**
	 * The caps every god holds, in the venue's namespace: call any operation
	 * (each still checks what it touches), read the venue's data, assets and
	 * jobs, keep its own notes (its memory) and ask the owner through the
	 * venue's Inbox. Nothing here writes the venue's state or the treasury.
	 */
	static final AVector<ACell> BROAD_CAPS = Vectors.of(
		cap("v/ops/", "invoke"),
		cap("", "crud/read"),
		cap("", "asset/read"),
		cap("j/", "job/read"),
		cap("n/", "crud/write"),
		cap("h/", "hitl/request"));

	/** A cap: {@code can} over {@code with}, a resource in the venue's namespace ({@code users}, {@code s/}). */
	static AMap<AString, ACell> cap(String with, String can) {
		return Maps.of("with", with, "can", can);
	}

	/** A god that keeps no user of its own and is granted no operations per call. */
	public God(String id, String ask, String run, String overseer, String systemPrompt, String remembers,
			List<String> tools, List<String> skills, AVector<ACell> powers, Set<String> venueOperations,
			Set<String> sudoOperations) {
		this(id, ask, run, overseer, null, systemPrompt, remembers, tools, skills, powers, venueOperations,
			sudoOperations, Set.of());
	}

	/** Its {@code config.caps}: {@link #BROAD_CAPS} and its {@link #powers}. */
	AVector<ACell> caps() {
		return BROAD_CAPS.concat(powers);
	}

	/** The god's DID on {@code venueDID}: a sub-principal of the venue. */
	public String did(String venueDID) {
		return venueDID + ":g:" + id;
	}

	/** The god's canonical agent address on {@code venueDID}, as agent operations take it. */
	public String address(String venueDID) {
		return venueDID + "/g/" + id;
	}

	/** The DID of the user this god keeps for itself on {@code venueDID}, or null. */
	public String kept(String venueDID) {
		return (keeps != null) ? venueDID + ":u:" + keeps : null;
	}

	/** The operation by which assistants reach this god. */
	public String askOp() {
		return OPS + ask;
	}

	/** The operation through which this god acts. */
	public String runOp() {
		return OPS + run;
	}

	/**
	 * Creates the god, or brings its configuration up to date, as the venue
	 * operator. {@code llmOperation} is the model it replies with — Brightside
	 * uses the chat's. The record is read in-process, so a god already
	 * configured costs no job. Best-effort on an existing god: an update refused
	 * mid-cycle is logged and stands until the next ensure. A god suspended by a
	 * failed turn is resumed; one stopped on purpose is left stopped. The user
	 * it keeps, if any, is registered first.
	 */
	public void ensure(EmbeddedVenue venue, String llmOperation) throws Exception {
		ensure(venue.engine(), llmOperation);
	}

	/** As {@link #ensure(EmbeddedVenue, String)}, on {@code engine} — as the launch hook does ({@link Startup}). */
	void ensure(Engine engine, String llmOperation) throws Exception {
		String venueDID = engine.getDIDString().toString();
		Venue operator = EmbeddedVenue.clientAs(engine, venueDID);
		String kept = kept(venueDID);
		if (kept != null && engine.getVenueState().users().user(Strings.create(kept)) == null) {
			run(operator, "v/ops/user/create", Maps.of(Fields.DID, kept));
			log.info("Registered {}'s own user {}", id, kept);
		}
		AMap<AString, ACell> config = config(llmOperation);
		ACell record = EmbeddedVenue.agentRecord(engine, venueDID, id);
		if (record == null) {
			try {
				run(operator, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config));
				log.info("Created {} at {}", id, address(venueDID));
				return;
			} catch (Exception e) {
				// Another ensure may have created it meanwhile; carry on as for an
				// existing god, or fail if it is still not there.
				record = EmbeddedVenue.agentRecord(engine, venueDID, id);
				if (record == null) throw e;
			}
		}
		if (!configured(record, config)) {
			try {
				run(operator, "v/ops/agent/update", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config));
				log.info("Brought {}'s configuration up to date", id);
			} catch (Exception e) {
				log.warn("Could not apply configuration to {} (stands until next time): {}", id, e.getMessage());
				return;
			}
		}
		if (Suspension.byFault(record)) {
			run(operator, "v/ops/agent/resume", Maps.of(Fields.AGENT_ID, id));
			log.info("Resumed {}, suspended by a failed turn", id);
		}
	}

	/** The god's agent configuration. Package-visible for tests. */
	AMap<AString, ACell> config(String llmOperation) {
		List<String> all = new java.util.ArrayList<>();
		all.add(runOp());
		all.addAll(tools);
		all.add(MEMORY_OP);
		Providers.Route route = Providers.route(llmOperation);
		return Maps.of(
			Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
			"llmOperation", route.operation(),
			"model", route.model(),
			"systemPrompt", systemPrompt,
			// Read-only venue-namespace reads (covia read/list) on top of the tools.
			"defaultTools", true,
			"tools", Vectors.of(all.stream().map(Strings::create).toArray(ACell[]::new)),
			// Durable decisions live in its memory, pinned into every turn through
			// the read-only recall op (a context entry may run nothing else).
			"context", Vectors.of(Maps.of(
				"op", MEMORY_RECALL_OP,
				"input", Maps.of("path", MEMORY_PATH),
				"label", "Your memory of " + remembers + ", empty until you record something — edit with path "
					+ MEMORY_PATH)),
			// The venue's own library: venue, auth, adapters, agents.
			"skillsets", Vectors.of(Strings.create("v/skills/root")),
			// The skills its job calls for, offered by name so each is one load
			// away; what they bring is bounded by its caps.
			"skills", Vectors.of(skills.stream().map(Strings::create).toArray(ACell[]::new)),
			// Multi-party by nature: record who sent each turn.
			"recordCaller", true,
			K_CAPS, caps());
	}

	/** Whether the record already holds every value {@code config} sets. Package-visible for tests. */
	static boolean configured(ACell record, AMap<AString, ACell> config) {
		ACell current = RT.getIn(record, Fields.CONFIG);
		for (Map.Entry<AString, ACell> e : config.entrySet()) {
			ACell held = RT.getIn(current, e.getKey());
			// Covia stores caps: "unrestricted" as no caps entry.
			if (held == null && K_CAPS.equals(e.getKey())) held = Config.UNRESTRICTED;
			if (!java.util.Objects.equals(e.getValue(), held)) return false;
		}
		return true;
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
