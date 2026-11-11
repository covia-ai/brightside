package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.treasury.Rail;
import brightside.treasury.TreasuryAdapter;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * The treasury's mechanism on a real venue, over a rail that moves nothing:
 * who may act on an account or budget, and the accounting that keeps an
 * account's budgets within the limit the owner set, and what a payment draws
 * and records. Payments over a real rail are {@link TreasuryConvexTest}'s.
 */
class TreasuryTest {

	private static final long TIMEOUT_SECONDS = 30;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static RequestContext asVenue;
	private static final TestRail rail = new TestRail();

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		venue.treasury().addRail(rail);
		asVenue = venue.engine().venueContext();
		Gods.ensureAll(venue, "v/test/ops/taskllm");
	}

	@Test
	void budgetsStayWithinTheLimit() throws Exception {
		add("limits", asVenue);
		refused(() -> createBudget("limits-a", "limits", 1, asVenue));
		assertNull(budget("limits-a"), "a new account's limit is nothing");

		run("limit-set", Maps.of("account", "limits", "limit", 100L), asVenue);
		createBudget("limits-a", "limits", 60, asVenue);
		refused(() -> createBudget("limits-b", "limits", 41, asVenue));
		createBudget("limits-b", "limits", 40, asVenue);

		run("budget-lower", Maps.of("id", "limits-a", "amount", 10L), asVenue);
		refused(() -> run("budget-lower", Maps.of("id", "limits-a", "amount", 20L), asVenue));
		assertEquals(10L, number(budget("limits-a"), "amount"), "a budget can only be lowered");
		ACell cancelled = run("budget-cancel", Maps.of("id", "limits-b"), asVenue);
		assertEquals(Strings.create("cancelled"), RT.getIn(cancelled, "status"));
		refused(() -> run("budget-lower", Maps.of("id", "limits-b", "amount", 0L), asVenue));
		assertEquals(Strings.create("cancelled"), RT.getIn(budget("limits-b"), "status"), "a closed budget stays closed");

		// What was lowered and cancelled is the account's to budget again.
		createBudget("limits-c", "limits", 90, asVenue);
		assertEquals(100L, number(account("limits"), "committed"));
	}

	@Test
	void anAgentActsOnlyWithAGrant() throws Exception {
		add("granted", asVenue);
		run("limit-set", Maps.of("account", "granted", "limit", 50L), asVenue);
		add("other", asVenue);
		run("limit-set", Maps.of("account", "other", "limit", 50L), asVenue);

		RequestContext bare = njord();
		refused(() -> createBudget("granted-a", "granted", 5, bare));
		assertNull(budget("granted-a"), "Njord's own context holds nothing in the treasury");
		refused(() -> createBudget("granted-a", "granted", 5, GodContexts.of(venue, Njord.AGENT_ID)));
		assertNull(budget("granted-a"), "nor do his caps: the treasury is reached through his bridge alone");
		refused(() -> run("account-list", Maps.empty(), bare));

		RequestContext budgeting = njord(cap(TreasuryAdapter.account("granted"), TreasuryAdapter.BUDGET));
		createBudget("granted-a", "granted", 5, budgeting);
		refused(() -> createBudget("other-a", "other", 5, budgeting));
		assertNull(budget("other-a"), "only the account granted");
		refused(() -> run("limit-set", Maps.of("account", "granted", "limit", 500L), budgeting));
		assertEquals(50L, number(account("granted"), "limit"), "a budget grant is not the owner's limit");

		RequestContext reading = njord(cap(TreasuryAdapter.ROOT, TreasuryAdapter.READ));
		AVector<ACell> listed = RT.ensureVector(RT.getIn(run("budget-list", Maps.of("account", "granted"), reading), "budgets"));
		assertEquals(Strings.create("granted-a"), RT.getIn(listed.get(0), "id"));
		refused(() -> createBudget("granted-b", "granted", 5, reading));
		assertNull(budget("granted-b"), "reading is not budgeting");
	}

	@Test
	void aPaymentDrawsItsBudgetAndIsLedgered() throws Exception {
		add("paying", asVenue);
		run("limit-set", Maps.of("account", "paying", "limit", 100L), asVenue);
		createBudget("paying-a", "paying", 30, asVenue);

		ACell line = pay("paying-a", "shop", 20);
		assertEquals(Strings.create("paid"), RT.getIn(line, "status"));
		assertEquals(Strings.create("shop"), RT.getIn(line, "recipient"));
		refused(() -> pay("paying-a", "shop", 11));
		assertEquals(20L, drawn("paying-a"), "a budget caps what it pays");

		// The rail is sure nothing moved: the budget has it back.
		refused(() -> pay("paying-a", TestRail.REFUSES, 10));
		assertEquals(20L, drawn("paying-a"));
		// The rail cannot say: it stays drawn, and the ledger says so.
		refused(() -> pay("paying-a", TestRail.LOSES, 10));
		assertEquals(30L, drawn("paying-a"));

		AVector<ACell> payments = payments("paying-a");
		assertEquals(3, payments.count(), "a refused payment leaves no line");
		assertEquals(Strings.create("not-paid"), RT.getIn(payments.get(1), "status"));
		assertEquals(Strings.create("unknown"), RT.getIn(payments.get(2), "status"));
	}

	@Test
	void aPaymentStopsAtALoweredLimit() throws Exception {
		add("lowered", asVenue);
		run("limit-set", Maps.of("account", "lowered", "limit", 100L), asVenue);
		createBudget("lowered-a", "lowered", 100, asVenue);
		pay("lowered-a", "shop", 40);
		run("limit-set", Maps.of("account", "lowered", "limit", 50L), asVenue);
		refused(() -> pay("lowered-a", "shop", 20));
		assertEquals(40L, drawn("lowered-a"), "the owner's limit holds over a budget made before it was lowered");
		pay("lowered-a", "shop", 10);
	}

	@Test
	void aCancelledBudgetGivesBackWhatItDidNotPay() throws Exception {
		add("cancelling", asVenue);
		run("limit-set", Maps.of("account", "cancelling", "limit", 100L), asVenue);
		createBudget("cancelling-a", "cancelling", 30, asVenue);

		// Cancelled while a payment is with the rail, which then says it did not pay.
		CompletableFuture<ACell> answer = rail.hold("held");
		CompletableFuture<ACell> paying = venue.engine().jobs()
			.invokeOperation("v/ops/treasury/pay", payment("cancelling-a", "held", 10), asVenue).future();
		rail.asked("held").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		run("budget-cancel", Maps.of("id", "cancelling-a"), asVenue);
		answer.completeExceptionally(new Rail.NotPaid("refused"));
		assertThrows(ExecutionException.class, () -> paying.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		assertEquals(0L, drawn("cancelling-a"));
		assertEquals(0L, number(account("cancelling"), "committed"), "the whole limit is the account's to budget again");
	}

	@Test
	void spendingNeedsAGrantOnThatBudget() throws Exception {
		add("spending", asVenue);
		run("limit-set", Maps.of("account", "spending", "limit", 10L), asVenue);
		createBudget("spending-a", "spending", 5, asVenue);
		createBudget("spending-b", "spending", 5, asVenue);
		RequestContext onA = njord(cap(TreasuryAdapter.budget("spending-a"), TreasuryAdapter.SPEND));
		run("pay", payment("spending-a", "shop", 1), onA);
		refused(() -> run("pay", payment("spending-b", "shop", 1), onA));
		assertEquals(0, payments("spending-b").count(), "only the budget granted");
		assertEquals(Strings.create(Njord.GOD.did(venue.did())), RT.getIn(payments("spending-a").get(0), "by"), "the ledger says who paid");
	}

	@Test
	void njordWorksTheTreasuryThroughHisBridge() throws Exception {
		ACell added = asNjord(Maps.of("operation", "v/ops/treasury/account-add",
			"input", Maps.of("id", "bridged", "rail", TestRail.NAME, "address", "bridged"), "reason", "The hosting account."));
		assertEquals(0L, number(added, "limit"), "an account he adds can spend nothing yet");
		run("limit-set", Maps.of("account", "bridged", "limit", 100L), asVenue);

		ACell made = asNjord(Maps.of("operation", "v/ops/treasury/budget-create",
			"input", Maps.of("id", "bridged-a", "account", "bridged", "amount", 30L), "reason", "Hosting for the year."));
		assertEquals(Strings.create(Odin.address(venue.did())), RT.getIn(made, "report", "address"), "reported to Odin");
		ACell paid = asNjord(Maps.of("operation", "v/ops/treasury/pay",
			"input", payment("bridged-a", "shop", 10), "reason", "The first month's hosting."));
		assertEquals(Strings.create("paid"), RT.getIn(paid, "status"));
		assertEquals(Strings.create(Njord.GOD.did(venue.did())), RT.getIn(paid, "by"));

		refused(() -> asNjord(Maps.of("operation", "v/ops/treasury/pay",
			"input", payment("bridged-a", "shop", 25), "reason", "More than the budget.")));
		assertEquals(10L, drawn("bridged-a"), "the budget caps what he pays");
		refused(() -> asNjord(Maps.of("operation", "v/ops/treasury/limit-set",
			"input", Maps.of("account", "bridged", "limit", 1000L), "reason", "Raising my own limit.")));
		assertEquals(100L, number(account("bridged"), "limit"), "the limit is the owner's");
		refused(() -> asNjord(Maps.of("operation", "v/ops/treasury/pay", "input", payment("bridged-a", "shop", 1))));
		assertEquals(10L, drawn("bridged-a"), "a payment needs his reason");

		asNjord(Maps.of("operation", "v/ops/treasury/budget-lower",
			"input", Maps.of("id", "bridged-a", "amount", 20L), "reason", "Hosting costs less than I thought."));
		assertEquals(20L, number(budget("bridged-a"), "amount"));
		asNjord(Maps.of("operation", "v/ops/treasury/budget-cancel",
			"input", Maps.of("id", "bridged-a"), "reason", "Hosting is paid for."));
		assertEquals(Strings.create("cancelled"), RT.getIn(budget("bridged-a"), "status"));

		AVector<ACell> ledger = RT.ensureVector(RT.getIn(asNjord(Maps.of("operation", "v/ops/treasury/ledger")), "payments"));
		assertTrue(ledger.count() > 0, "a read needs no reason");
		refused(() -> asGod(Thor.AGENT_ID, Thor.OP_RUN, Maps.of("operation", "v/ops/treasury/ledger")));
	}

	// ------------------------------------------------------------------

	private static ACell asNjord(AMap<AString, ACell> input) throws Exception {
		return asGod(Njord.AGENT_ID, Njord.OP_RUN, input);
	}

	/** Invokes as a god's own sub-principal — exactly the context its run loop gives its tool calls. */
	private static ACell asGod(String god, String op, AMap<AString, ACell> input) throws Exception {
		RequestContext ctx = GodContexts.of(venue, god);
		return venue.engine().jobs().invokeOperation(op, input, ctx).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static ACell pay(String budget, String recipient, long amount) throws Exception {
		return run("pay", payment(budget, recipient, amount), asVenue);
	}

	private static AMap<AString, ACell> payment(String budget, String recipient, long amount) {
		return Maps.of("budget", budget, "recipient", recipient, "amount", amount, "reason", "As the test agreed.");
	}

	private static AVector<ACell> payments(String budget) throws Exception {
		return RT.ensureVector(RT.getIn(run("ledger", Maps.of("budget", budget), asVenue), "payments"));
	}

	private static long drawn(String budget) throws Exception {
		ACell found = budget(budget);
		assertNotNull(found, "No budget " + budget);
		return number(found, "drawn");
	}

	/** The budget as listed, or null when there is none. */
	private static ACell budget(String id) throws Exception {
		return find(RT.ensureVector(RT.getIn(run("budget-list", Maps.empty(), asVenue), "budgets")), id);
	}

	private static ACell account(String id) throws Exception {
		ACell found = find(RT.ensureVector(RT.getIn(run("account-list", Maps.empty(), asVenue), "accounts")), id);
		assertNotNull(found, "No account " + id);
		return found;
	}

	private static ACell find(AVector<ACell> records, String id) {
		for (long i = 0; i < records.count(); i++) {
			if (Strings.create(id).equals(RT.getIn(records.get(i), "id"))) return records.get(i);
		}
		return null;
	}

	private static long number(ACell record, String field) {
		return RT.ensureLong(RT.getIn(record, field)).longValue();
	}

	private static void add(String id, RequestContext ctx) throws Exception {
		run("account-add", Maps.of("id", id, "rail", TestRail.NAME, "address", id), ctx);
	}

	private static ACell createBudget(String id, String account, long amount, RequestContext ctx) throws Exception {
		return run("budget-create", Maps.of("id", id, "account", account, "amount", amount), ctx);
	}

	/** Njord's own context, presenting a venue grant of {@code att} to him for this call. */
	@SafeVarargs
	private static RequestContext njord(AMap<AString, ACell>... att) {
		RequestContext ctx = BrightsideAdapter.asGranted(Strings.create(venue.did()), Njord.AGENT_ID);
		if (att.length == 0) return ctx;
		ACell grant = new VenueGrants(venue.engine()).grant(Strings.create(Njord.GOD.did(venue.did())), Vectors.of((Object[]) att));
		return ctx.withProofs(Vectors.of(grant));
	}

	/** A capability over a venue-relative treasury resource. */
	private static AMap<AString, ACell> cap(String resource, AString ability) {
		return VenueGrants.cap(Strings.create(venue.did() + "/" + resource), ability);
	}

	private static ACell run(String op, AMap<AString, ACell> input, RequestContext ctx) throws Exception {
		// Internal, as the run bridge invokes a granted operation: the adapter's checks decide.
		return venue.engine().jobs().invokeInternal("v/ops/treasury/" + op, input, ctx)
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private interface Call {
		void call() throws Exception;
	}

	/** The call is refused: what it would have changed is for the caller to check. */
	private static void refused(Call call) {
		assertThrows(ExecutionException.class, call::call);
	}

	/**
	 * A rail that names accounts by their {@code address} and moves nothing: it
	 * pays anyone but {@link #REFUSES}, which it is sure it did not pay,
	 * {@link #LOSES}, which it cannot say, and a recipient it was told to
	 * {@link #hold}, which waits for the test's answer.
	 */
	static final class TestRail implements Rail {
		static final String NAME = "test";
		static final String REFUSES = "refuses";
		static final String LOSES = "loses";

		private final Map<String, CompletableFuture<ACell>> held = new ConcurrentHashMap<>();
		private final Map<String, CompletableFuture<Void>> asked = new ConcurrentHashMap<>();

		/** Holds payments to {@code recipient} until the returned future completes. */
		CompletableFuture<ACell> hold(String recipient) {
			return held.computeIfAbsent(recipient, r -> new CompletableFuture<>());
		}

		/** Completes once a payment to {@code recipient} has reached the rail, its draw saved. */
		CompletableFuture<Void> asked(String recipient) {
			return asked.computeIfAbsent(recipient, r -> new CompletableFuture<>());
		}

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public AMap<AString, ACell> account(ACell input) {
			AString address = RT.ensureString(RT.getIn(input, "address"));
			if (address == null) throw new IllegalArgumentException("address is required");
			return Maps.of("account", address, "currency", "test:coin");
		}

		@Override
		public AString recipient(ACell recipient) {
			AString name = RT.ensureString(recipient);
			if (name == null) throw new IllegalArgumentException("recipient is required");
			return name;
		}

		@Override
		public CompletableFuture<Long> balance(AMap<AString, ACell> account) {
			return CompletableFuture.completedFuture(0L);
		}

		@Override
		public CompletableFuture<ACell> pay(AMap<AString, ACell> account, AString recipient, long amount) {
			String to = recipient.toString();
			asked(to).complete(null);
			CompletableFuture<ACell> waiting = held.get(to);
			if (waiting != null) return waiting;
			return switch (to) {
				case REFUSES -> CompletableFuture.failedFuture(new NotPaid("refused"));
				case LOSES -> CompletableFuture.failedFuture(new IllegalStateException("no answer"));
				default -> CompletableFuture.completedFuture(Maps.of("paid", amount));
			};
		}
	}
}
