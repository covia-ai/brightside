package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.peer.API;
import convex.peer.Server;
import covia.venue.RequestContext;

/**
 * The treasury paying over Convex on a local network of one peer: an account
 * whose key is in Njord's treasury, a budget within the owner's limit, and a
 * payment that reaches the recipient and the ledger. A payment the network
 * refuses gives the budget its draw back.
 */
class TreasuryConvexTest {

	private static final long TIMEOUT_SECONDS = 30;
	private static final String TASK_LLM = "v/test/ops/taskllm";
	private static final String KEY = "TREASURY_CONVEX_KEY";
	private static final long FLOAT = 1_000_000_000L;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static Server peer;
	private static String endpoint;
	private static Convex genesis;
	private static RequestContext asVenue;
	private static Address account;

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
		asVenue = venue.engine().venueContext();
		Gods.ensureAll(venue, TASK_LLM);
		// A key in Njord's treasury, made as he would make it, for an account the test funds.
		RequestContext njord = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Njord.AGENT_ID));
		ACell made = venue.engine().jobs().invokeOperation(Njord.OP_RUN, Maps.of("operation", "v/ops/convex/generate-key",
			"user", Njord.TREASURY, "input", Maps.of("name", KEY, "overwrite", true), "reason", "A key for the test treasury."),
			njord).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		AString accountKey = RT.ensureString(RT.getIn(made, "accountKey"));
		account = fromGenesis("(let [a (create-account 0x" + accountKey + ")] (transfer a " + FLOAT + ") a)");

		run("account-add", Maps.of("id", "convex-main", "rail", "convex", "network", "convex:local", "peer", endpoint,
			"address", account.toString(), "credential", "s/" + KEY, "custody", Njord.treasury(venue.did())));
		run("limit-set", Maps.of("account", "convex-main", "limit", 2 * FLOAT));
	}

	@AfterAll
	static void close() {
		if (genesis != null) genesis.close();
		if (peer != null) peer.close();
	}

	@Test
	void aPaymentReachesTheRecipientAndTheLedger() throws Exception {
		run("budget-create", Maps.of("id", "hosting", "account", "convex-main", "amount", 5_000L));
		Address payee = fromGenesis("(create-account 0x" + AKeyPair.generate().getAccountKey().toHexString() + ")");

		ACell line = run("pay", Maps.of("budget", "hosting", "recipient", payee.toString(), "amount", 1_234L,
			"reason", "Hosting, as the owner agreed."));
		assertEquals(Strings.create("paid"), RT.getIn(line, "status"), String.valueOf(line));
		Result held = genesis.query("(balance " + payee + ")").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertEquals(1_234L, RT.ensureLong(held.getValue()).longValue(), "the payee has it");

		ACell listed = run("account-list", Maps.empty());
		assertTrue(String.valueOf(listed).contains("convex:local/slip44:864"), "spent in CVM: " + listed);
		ACell balance = RT.getIn(RT.ensureVector(RT.getIn(listed, "accounts")).get(0), "balance");
		assertNotNull(balance, "the rail reads the balance: " + listed);
	}

	@Test
	void aPaymentTheNetworkRefusesReturnsItsDraw() throws Exception {
		run("budget-create", Maps.of("id", "too-much", "account", "convex-main", "amount", 2 * FLOAT - 10_000L));
		Address payee = fromGenesis("(create-account 0x" + AKeyPair.generate().getAccountKey().toHexString() + ")");
		ExecutionException e = assertThrows(ExecutionException.class, () -> run("pay", Maps.of("budget", "too-much",
			"recipient", payee.toString(), "amount", FLOAT + 1, "reason", "More than the account holds.")));
		assertTrue(e.getCause().getMessage().contains("Not paid"), e.getCause().getMessage());
		ACell budget = RT.ensureVector(RT.getIn(run("budget-list", Maps.of("account", "convex-main")), "budgets")).stream()
			.filter(b -> Strings.create("too-much").equals(RT.getIn(b, "id"))).findFirst().orElseThrow();
		assertEquals(0L, RT.ensureLong(RT.getIn(budget, "drawn")).longValue(), "the budget has it back");
	}

	private static ACell run(String op, AMap<AString, ACell> input) throws Exception {
		return venue.engine().jobs().invokeOperation("v/ops/treasury/" + op, input, asVenue).future()
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static Address fromGenesis(String source) throws Exception {
		Result result = genesis.transact(source).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertFalse(result.isError(), String.valueOf(result));
		return RT.ensureAddress(result.getValue());
	}
}
