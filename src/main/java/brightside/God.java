package brightside;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * A god: one of the venue's own agents, specialised in one job on the owner's
 * behalf. A god is owned by the venue principal, not by any user — address
 * {@code <venueDID>/g/<id>}, DID {@code <venueDID>:g:<id>} — and answers to the
 * owner alone, asking them through the Inbox.
 *
 * <p>Each god has two Brightside bridges, both handled once for every god in
 * {@link BrightsideAdapter}: one by which the owner's assistants reach it
 * ({@link #ask}), submitted as the calling user so the god sees who asked, and
 * one through which it acts ({@link #run}), callable by that god alone. An agent
 * cannot hold venue authority itself, so the run bridge lends it — for one of
 * the god's {@link #venueOperations} under the venue's authority, or one of its
 * {@link #sudoOperations} inside a user's namespace on their behalf. The
 * allowlists, not the god's judgement, bound what it can do; its system prompt
 * carries the judgement. The gods are listed in {@link Gods}; see
 * {@code docs/GODS.md}.
 *
 * @param id              the agent id, also the god's name
 * @param ask             the sub-operation of {@code brightside:} by which assistants reach it
 * @param run             the sub-operation of {@code brightside:} through which it acts
 * @param systemPrompt    who it is and the policy it applies
 * @param remembers       what its pinned memory holds, for the pin's label
 * @param tools           its tools besides its run bridge and memory
 * @param venueOperations what it may run under the venue's authority
 * @param sudoOperations  what it may run inside a user's namespace, on their behalf
 */
public record God(String id, String ask, String run, String systemPrompt, String remembers,
		List<String> tools, Set<String> venueOperations, Set<String> sudoOperations) {

	private static final Logger log = LoggerFactory.getLogger(God.class);

	private static final String OPS = "v/ops/brightside/";
	private static final String MEMORY_OP = "v/ops/memory";
	/** The read-only render a context entry may run; {@link #MEMORY_OP} is the tool that edits. */
	static final String MEMORY_RECALL_OP = "v/ops/memory-recall";
	private static final String MEMORY_PATH = "n/memory";
	private static final long ADMIN_TIMEOUT_SECONDS = 30;

	/** The god's DID on {@code venueDID}: a sub-principal of the venue. */
	public String did(String venueDID) {
		return venueDID + ":g:" + id;
	}

	/** The god's canonical agent address on {@code venueDID}, as agent operations take it. */
	public String address(String venueDID) {
		return venueDID + "/g/" + id;
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
	 * failed turn is resumed; one stopped on purpose is left stopped.
	 */
	public void ensure(EmbeddedVenue venue, String llmOperation) throws Exception {
		Venue operator = venue.operator();
		AMap<AString, ACell> config = config(llmOperation);
		ACell record = venue.agentRecord(venue.did(), id);
		if (record == null) {
			try {
				run(operator, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config));
				log.info("Created {} at {}", id, address(venue.did()));
				return;
			} catch (Exception e) {
				// Another ensure may have created it meanwhile; carry on as for an
				// existing god, or fail if it is still not there.
				record = venue.agentRecord(venue.did(), id);
				if (record == null) throw e;
			}
		}
		if (!configured(record, config)) {
			try {
				run(operator, "v/ops/agent/update", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config));
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
		return Maps.of(
			Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
			"llmOperation", llmOperation,
			"systemPrompt", systemPrompt,
			// Read-only venue-namespace reads (covia read/list) on top of the tools.
			"defaultTools", true,
			"tools", Vectors.of(all.stream().map(Strings::create).toArray(ACell[]::new)),
			// Durable decisions live in its memory, pinned into every turn through
			// the read-only recall op (a context entry may run nothing else).
			"context", Vectors.of(Maps.of(
				"op", MEMORY_RECALL_OP,
				"input", Maps.of("path", MEMORY_PATH),
				"label", "Your memory of " + remembers + " — edit with path " + MEMORY_PATH)),
			// The venue's own library: venue, auth, adapters, agents.
			"skillsets", Vectors.of(Strings.create("v/skills/root")),
			// Multi-party by nature: record who sent each turn.
			"recordCaller", true);
	}

	/** Whether the record already holds every value {@code config} sets. Package-visible for tests. */
	static boolean configured(ACell record, AMap<AString, ACell> config) {
		ACell current = RT.getIn(record, Fields.CONFIG);
		for (Map.Entry<AString, ACell> e : config.entrySet()) {
			if (!java.util.Objects.equals(e.getValue(), RT.getIn(current, e.getKey()))) return false;
		}
		return true;
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
