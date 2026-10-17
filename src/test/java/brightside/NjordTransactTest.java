package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.peer.API;
import convex.peer.Server;
import covia.venue.RequestContext;

/**
 * Njord moves funds for real: a key he makes in his treasury signs a transfer
 * on a Convex network through his bridge, the network applies it, and Odin is
 * told. The network is one peer in this JVM on loopback with a genesis of its
 * own, so nothing leaves the machine and the funds are the test's to give.
 */
class NjordTransactTest {

	private static final long TIMEOUT_SECONDS = 30;
	/** Completes any task it is given — the venue's own test model. */
	private static final String TASK_LLM = "v/test/ops/taskllm";
	private static final String KEY = "NJORD_PAYMENTS";
	/** What the treasury's account is opened with, and what Njord pays from it, in copper. */
	private static final long FLOAT = 100_000_000L;
	private static final long PAYMENT = 1_000_000L;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static Server peer;
	private static String endpoint;
	/** The genesis account, which holds the network's funds: test setup only, never Njord's. */
	private static Convex genesis;

	@BeforeAll
	static void boot() throws Exception {
		AKeyPair genesisKey = AKeyPair.generate();
		HashMap<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.KEYPAIR, genesisKey);
		config.put(Keywords.STATE, Migrations.applyAll(Init.createState(List.of(genesisKey.getAccountKey()))));
		config.put(Keywords.PORT, 0);
		peer = API.launchPeer(config);
		endpoint = "localhost:" + peer.getPort();
		genesis = Convex.connect(endpoint);
		genesis.setAddress(Init.GENESIS_ADDRESS);
		genesis.setKeyPair(genesisKey);

		venue = shared.venue();
		Gods.ensureAll(venue, TASK_LLM);
	}

	@AfterAll
	static void close() {
		if (genesis != null) genesis.close();
		if (peer != null) peer.close();
	}

	@Test
	void njordPaysFromAnAccountHisTreasuryKeyControls() throws Exception {
		ACell made = asNjord(Maps.of("operation", "v/ops/convex/generate-key", "user", Njord.TREASURY,
			"input", Maps.of("name", KEY, "overwrite", true), "reason", "A key for the treasury's payment account."));
		AString accountKey = RT.ensureString(RT.getIn(made, "accountKey"));
		assertNotNull(accountKey, String.valueOf(made));
		Address account = fromGenesis("(let [a (create-account 0x" + accountKey + ")] (transfer a " + FLOAT + ") a)");
		Address payee = fromGenesis("(create-account 0x" + AKeyPair.generate().getAccountKey().toHexString() + ")");
		assertEquals(0L, balance(payee, account));

		ACell paid = asNjord(Maps.of("operation", "v/ops/convex/transact", "user", Njord.TREASURY,
			"input", Maps.of("peer", endpoint, "address", account.toString(), "seed", "s/" + KEY,
				"source", "(transfer " + payee + " " + PAYMENT + ")"),
			"reason", "Paying " + payee + " " + PAYMENT + " copper, as the test agreed."));
		assertNull(RT.getIn(paid, "errorCode"), "the network applied it: " + paid);
		assertEquals(Strings.create(Odin.address(venue.did())), RT.getIn(paid, "report", "address"),
			"every transaction is reported to Odin");
		assertNotNull(RT.getIn(paid, "report", "id"), "with the task that carries the report");

		assertEquals(PAYMENT, balance(payee, account), "the payee has the payment");
		assertTrue(balance(account, account) <= FLOAT - PAYMENT, "and the treasury paid it, with the fee");
	}

	/**
	 * A balance as Njord reads it: a free query through his bridge, which needs
	 * no reason and tells nobody, made as {@code as} — a query from an account
	 * with nothing in it runs out of juice.
	 */
	private static long balance(Address of, Address as) throws Exception {
		ACell read = asNjord(Maps.of("operation", "v/ops/convex/query", "user", Njord.TREASURY,
			"input", Maps.of("peer", endpoint, "address", as.toString(), "source", "(balance " + of + ")")));
		assertFalse(read instanceof AMap<?, ?> map && map.containsKey(Strings.create("report")), "a read is not reported");
		CVMLong value = RT.ensureLong(RT.getIn(read, "value"));
		assertNotNull(value, String.valueOf(read));
		return value.longValue();
	}

	/** Test setup straight from the genesis account, outside the venue: the funds Njord is given. */
	private static Address fromGenesis(String source) throws Exception {
		Result result = genesis.transact(source).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertFalse(result.isError(), String.valueOf(result));
		return RT.ensureAddress(result.getValue());
	}

	/** Invokes as Njord's own sub-principal — exactly the context his run loop gives his tool calls. */
	private static ACell asNjord(AMap<AString, ACell> input) throws Exception {
		RequestContext ctx = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Njord.AGENT_ID));
		return venue.engine().jobs().invokeOperation(Njord.OP_RUN, input, ctx).future()
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
