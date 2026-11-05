package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
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

	@BeforeAll
	static void boot() {
		venue = shared.venue();
		venue.treasury().addRail(new TestRail());
		asVenue = venue.engine().venueContext();
	}

	@Test
	void budgetsStayWithinTheLimit() throws Exception {
		add("limits", asVenue);
		assertTrue(failure(() -> createBudget("limits-a", "limits", 1, asVenue)).contains("past its limit"),
			"a new account's limit is nothing");

		run("limit-set", Maps.of("account", "limits", "limit", 100L), asVenue);
		createBudget("limits-a", "limits", 60, asVenue);
		assertTrue(failure(() -> createBudget("limits-b", "limits", 41, asVenue)).contains("past its limit"));
		createBudget("limits-b", "limits", 40, asVenue);

		run("budget-lower", Maps.of("id", "limits-a", "amount", 10L), asVenue);
		assertTrue(failure(() -> run("budget-lower", Maps.of("id", "limits-a", "amount", 20L), asVenue))
			.contains("only be lowered"));
		ACell cancelled = run("budget-cancel", Maps.of("id", "limits-b"), asVenue);
		assertEquals(Strings.create("cancelled"), RT.getIn(cancelled, "status"));
		assertTrue(failure(() -> run("budget-lower", Maps.of("id", "limits-b", "amount", 0L), asVenue))
			.contains("cancelled"), "a closed budget stays closed");

		// What was lowered and cancelled is the account's to budget again.
		createBudget("limits-c", "limits", 90, asVenue);
		assertEquals(100L, RT.ensureLong(RT.getIn(account("limits"), "committed")).longValue());
	}

	@Test
	void anAgentActsOnlyWithAGrant() throws Exception {
		add("granted", asVenue);
		run("limit-set", Maps.of("account", "granted", "limit", 50L), asVenue);
		add("other", asVenue);
		run("limit-set", Maps.of("account", "other", "limit", 50L), asVenue);

		RequestContext bare = njord();
		assertTrue(failure(() -> createBudget("granted-a", "granted", 5, bare)).contains("treasury/budget"),
			"Njord's own context holds nothing in the treasury");
		assertThrows(Exception.class, () -> run("account-list", Maps.empty(), bare));

		RequestContext budgeting = njord(cap(TreasuryAdapter.account("granted"), TreasuryAdapter.BUDGET));
		createBudget("granted-a", "granted", 5, budgeting);
		assertThrows(Exception.class, () -> createBudget("other-a", "other", 5, budgeting), "only the account granted");
		assertThrows(Exception.class, () -> run("limit-set", Maps.of("account", "granted", "limit", 500L), budgeting),
			"a budget grant is not the owner's limit");

		RequestContext reading = njord(cap(TreasuryAdapter.ROOT, TreasuryAdapter.READ));
		assertTrue(String.valueOf(run("budget-list", Maps.of("account", "granted"), reading)).contains("granted-a"));
		assertThrows(Exception.class, () -> createBudget("granted-b", "granted", 5, reading), "reading is not budgeting");
	}

	@Test
	void aPaymentDrawsItsBudgetAndIsLedgered() throws Exception {
		add("paying", asVenue);
		run("limit-set", Maps.of("account", "paying", "limit", 100L), asVenue);
		createBudget("paying-a", "paying", 30, asVenue);

		ACell line = pay("paying-a", "shop", 20);
		assertEquals(Strings.create("paid"), RT.getIn(line, "status"));
		assertEquals(Strings.create("shop"), RT.getIn(line, "recipient"));
		assertTrue(failure(() -> pay("paying-a", "shop", 11)).contains("has 10 left"), "a budget caps what it pays");

		// The rail is sure nothing moved: the budget has it back.
		assertTrue(failure(() -> pay("paying-a", TestRail.REFUSES, 10)).contains("Not paid"));
		assertEquals(20L, drawn("paying-a"));
		// The rail cannot say: it stays drawn, and the ledger says so.
		assertTrue(failure(() -> pay("paying-a", TestRail.LOSES, 10)).contains("not known"));
		assertEquals(30L, drawn("paying-a"));

		AVector<ACell> payments = RT.ensureVector(RT.getIn(run("ledger", Maps.of("budget", "paying-a"), asVenue), "payments"));
		assertEquals(3, payments.count());
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
		assertTrue(failure(() -> pay("lowered-a", "shop", 20)).contains("past its limit"),
			"the owner's limit holds over a budget made before it was lowered");
		pay("lowered-a", "shop", 10);
	}

	@Test
	void spendingNeedsAGrantOnThatBudget() throws Exception {
		add("spending", asVenue);
		run("limit-set", Maps.of("account", "spending", "limit", 10L), asVenue);
		createBudget("spending-a", "spending", 5, asVenue);
		createBudget("spending-b", "spending", 5, asVenue);
		RequestContext onA = njord(cap(TreasuryAdapter.budget("spending-a"), TreasuryAdapter.SPEND));
		run("pay", payment("spending-a", "shop", 1), onA);
		assertTrue(failure(() -> run("pay", payment("spending-b", "shop", 1), onA)).contains("treasury/spend"));
		AVector<ACell> payments = RT.ensureVector(RT.getIn(run("ledger", Maps.of("budget", "spending-a"), asVenue), "payments"));
		assertEquals(Strings.create(Njord.GOD.did(venue.did())), RT.getIn(payments.get(0), "by"), "the ledger says who paid");
	}

	// ------------------------------------------------------------------

	private static ACell pay(String budget, String recipient, long amount) throws Exception {
		return run("pay", payment(budget, recipient, amount), asVenue);
	}

	private static AMap<AString, ACell> payment(String budget, String recipient, long amount) {
		return Maps.of("budget", budget, "recipient", recipient, "amount", amount, "reason", "As the test agreed.");
	}

	private static long drawn(String budget) throws Exception {
		AVector<ACell> budgets = RT.ensureVector(RT.getIn(run("budget-list", Maps.empty(), asVenue), "budgets"));
		for (long i = 0; i < budgets.count(); i++) {
			if (Strings.create(budget).equals(RT.getIn(budgets.get(i), "id"))) {
				return RT.ensureLong(RT.getIn(budgets.get(i), "drawn")).longValue();
			}
		}
		throw new AssertionError("No budget " + budget);
	}

	private static void add(String id, RequestContext ctx) throws Exception {
		run("account-add", Maps.of("id", id, "rail", TestRail.NAME, "address", id), ctx);
	}

	private static ACell createBudget(String id, String account, long amount, RequestContext ctx) throws Exception {
		return run("budget-create", Maps.of("id", id, "account", account, "amount", amount), ctx);
	}

	private static ACell account(String id) throws Exception {
		AVector<ACell> accounts = RT.ensureVector(RT.getIn(run("account-list", Maps.empty(), asVenue), "accounts"));
		for (long i = 0; i < accounts.count(); i++) {
			if (Strings.create(id).equals(RT.getIn(accounts.get(i), "id"))) return accounts.get(i);
		}
		throw new AssertionError("No account " + id);
	}

	/** Njord's own context, presenting a venue grant of {@code att} to him for this call. */
	@SafeVarargs
	private static RequestContext njord(AMap<AString, ACell>... att) {
		RequestContext ctx = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Njord.AGENT_ID));
		if (att.length == 0) return ctx;
		ACell grant = new VenueGrants(venue.engine()).grant(Strings.create(Njord.GOD.did(venue.did())), Vectors.of((Object[]) att));
		return ctx.withProofs(Vectors.of(grant));
	}

	/** A capability over a venue-relative treasury resource. */
	private static AMap<AString, ACell> cap(String resource, AString ability) {
		return VenueGrants.cap(Strings.create(venue.did() + "/" + resource), ability);
	}

	private static ACell run(String op, AMap<AString, ACell> input, RequestContext ctx) throws Exception {
		return venue.engine().jobs().invokeOperation("v/ops/treasury/" + op, input, ctx).future()
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private interface Call {
		void call() throws Exception;
	}

	/** The message a refused call fails with. */
	private static String failure(Call call) {
		ExecutionException e = assertThrows(ExecutionException.class, call::call);
		return String.valueOf(e.getCause().getMessage());
	}

	/**
	 * A rail that names accounts by their {@code address} and moves nothing: it
	 * pays anyone but {@link #REFUSES}, which it is sure it did not pay, and
	 * {@link #LOSES}, which it cannot say.
	 */
	static final class TestRail implements Rail {
		static final String NAME = "test";
		static final String REFUSES = "refuses";
		static final String LOSES = "loses";

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
			return switch (recipient.toString()) {
				case REFUSES -> CompletableFuture.failedFuture(new NotPaid("refused"));
				case LOSES -> CompletableFuture.failedFuture(new IllegalStateException("no answer"));
				default -> CompletableFuture.completedFuture(Maps.of("paid", amount));
			};
		}
	}
}
