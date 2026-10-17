package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * Njord's custody on a real venue: his treasury is a user only his bridge can
 * act as, so a key made there signs for him and for no one else — not the
 * owner's assistants, not the other gods. Every signature carries his reason
 * and is reported to Odin. Nothing here reaches a Convex peer: keys and
 * signatures are local, a transaction is not exercised.
 */
class NjordTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given — the venue's own test model. */
	private static final String TASK_LLM = "v/test/ops/taskllm";
	private static final String KEY = "NJORD_TEST_KEY";

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;
	private static String treasury;

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		userDID = Identity.of("njord-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
		treasury = Njord.treasury(venue.did());
		Gods.ensureAll(venue, TASK_LLM);
		// The test's own key, made once through the bridge as the god himself would.
		asNjord(Maps.of("operation", "v/ops/convex/generate-key", "user", Njord.TREASURY,
			"input", Maps.of("name", KEY, "overwrite", true), "reason", "A key for the test treasury."));
	}

	@Test
	void njordKeepsATreasuryNoPersonCanBe() {
		assertNotNull(venue.agentRecord(venue.did(), Njord.AGENT_ID), "one of the venue's gods");
		assertNotNull(venue.engine().getVenueState().users().get(Strings.create(treasury)), "his treasury is registered");
		assertNotEquals(Njord.TREASURY, Identity.of(Njord.TREASURY).slug(), "no name yields the treasury's slug");
	}

	@Test
	void aKeyHeMakesIsInTheTreasuryAndSignsForHim() throws Exception {
		ACell names = asNjord(Maps.of("operation", "v/ops/secret/list", "user", Njord.TREASURY));
		assertTrue(String.valueOf(names).contains(KEY), "in the treasury: " + names);
		assertFalse(String.valueOf(run(user, "v/ops/secret/list", Maps.empty())).contains(KEY), "not in the owner's store");

		ACell signed = asNjord(Maps.of("operation", "v/ops/convex/sign", "user", Njord.TREASURY,
			"input", Maps.of("seed", "s/" + KEY, "message", "pay #42 1 CVM"), "reason", "Signing the agreed payment."));
		assertNotNull(RT.getIn(signed, "signature"), String.valueOf(signed));
		assertEquals(Strings.create(Odin.address(venue.did())), RT.getIn(signed, "report", "address"),
			"every signature is reported to Odin");
	}

	@Test
	void nobodyElseCanSignWithATreasuryKey() throws Exception {
		// The owner signs with a key of their own; a treasury key is not theirs to use.
		run(user, "v/ops/convex/generate-key", Maps.of("name", "OWN_KEY", "overwrite", true));
		assertNotNull(RT.getIn(run(user, "v/ops/convex/sign", Maps.of("seed", "s/OWN_KEY", "message", "mine")), "signature"));
		assertThrows(Exception.class, () -> run(user, "v/ops/convex/sign",
			Maps.of("seed", treasury + "/s/" + KEY, "message", "pay me")), "not the owner's assistants");
		assertThrows(Exception.class, () -> asGod(Thor.AGENT_ID, Thor.OP_RUN, Maps.of("operation", "v/ops/secret/list",
			"user", treasury)), "not Thor");
		assertThrows(Exception.class, () -> asGod(Odin.AGENT_ID, Odin.OP_RUN, Maps.of("operation", "v/ops/covia/delete",
			"input", Maps.of("path", "s/" + KEY), "user", treasury)), "not Odin");
		assertThrows(Exception.class, () -> run(user, Njord.OP_RUN, Maps.of("operation", "v/ops/secret/list",
			"user", Njord.TREASURY)), "and his bridge is his alone");
	}

	@Test
	void keysGoByReferenceAndAreMadeOnlyInTheTreasury() throws Exception {
		String literal = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		assertThrows(Exception.class, () -> asNjord(Maps.of("operation", "v/ops/convex/transact", "user", Njord.TREASURY,
			"input", Maps.of("address", "#13", "source", "(transfer #42 1)", "seed", literal),
			"reason", "Paying with a pasted seed.")), "a literal seed never reaches an operation or a report");
		assertThrows(Exception.class, () -> asNjord(Maps.of("operation", "v/ops/convex/generate-key", "user", userDID,
			"input", Maps.of("name", "OUTSIDE_CUSTODY"), "reason", "A key in the owner's store.")),
			"his keys are made in his treasury");
		assertFalse(String.valueOf(run(user, "v/ops/secret/list", Maps.empty())).contains("OUTSIDE_CUSTODY"));
	}

	@Test
	void signingNeedsAReason() {
		assertThrows(Exception.class, () -> asNjord(Maps.of("operation", "v/ops/convex/sign", "user", Njord.TREASURY,
			"input", Maps.of("seed", "s/" + KEY, "message", "pay #42 1 CVM"))));
	}

	@Test
	void anAssistantAsksNjordAsTheOwner() throws Exception {
		ACell snapshot = run(user, Njord.OP_ASK, Maps.of(
			"request", "Pay #42 1 CVM for the hosting the owner agreed on Tuesday.", "timeout", 0L));
		assertEquals(Strings.create(Njord.GOD.address(venue.did())), RT.getIn(snapshot, "address"));
		AString id = RT.ensureString(RT.getIn(snapshot, "id"));
		assertNotNull(run(user, "v/ops/grid/job-result", Maps.of("id", id, "timeout", 20_000L)),
			"the request is the user's own job, so the user's job tools reach its outcome");
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static ACell asNjord(AMap<AString, ACell> input) throws Exception {
		return asGod(Njord.AGENT_ID, Njord.OP_RUN, input);
	}

	/** Invokes as a god's own sub-principal — exactly the context its run loop gives its tool calls. */
	private static ACell asGod(String god, String op, AMap<AString, ACell> input) throws Exception {
		RequestContext ctx = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(god));
		return venue.engine().jobs().invokeOperation(op, input, ctx).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
