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
 * account's budgets within the limit the owner set. Payments over a real
 * rail are {@link TreasuryConvexTest}'s.
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

	// ------------------------------------------------------------------

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

	/** A rail that names accounts by their {@code address} and moves nothing. */
	static final class TestRail implements Rail {
		static final String NAME = "test";

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
			return CompletableFuture.completedFuture(Maps.of("paid", amount));
		}
	}
}
