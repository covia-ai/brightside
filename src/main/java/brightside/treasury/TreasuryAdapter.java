package brightside.treasury;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * The treasury, as operations on the venue — {@code v/ops/treasury/<op>}: the
 * accounts Njord manages, the spend limit the owner has approved on each, the
 * budgets drawn from those limits, and the payments made from them. Nothing in
 * it belongs to one network: each account is on a {@link Rail}. See
 * {@code docs/TREASURY.md}.
 *
 * <p>Authority is Covia's ordinary grants. Accounts and budgets are resources
 * of the venue's own ({@code <venueDID>/treasury/accounts/<id>},
 * {@code <venueDID>/treasury/budgets/<id>}); every operation demands its
 * ability on the resource it touches, which the venue itself holds and anyone
 * else must present a venue-signed grant for. The limits are the treasury's own
 * accounting on those resources: an account's budgets never add up to more
 * than its limit.
 *
 * <p>State is installation-wide, in this adapter's state in the venue:
 * {@code accounts/<id>} and {@code budgets/<id>}. Every change is one atomic
 * update of that state.
 */
public class TreasuryAdapter extends AAdapter {

	/** Every operation, as installed under {@code v/ops/treasury/}. */
	public static final List<String> OPS = List.of(
		"account-add", "account-list", "limit-set",
		"budget-create", "budget-lower", "budget-cancel", "budget-list");

	/** See accounts, budgets and the ledger. */
	public static final AString READ = Strings.intern("treasury/read");
	/** Pay from a budget. */
	public static final AString SPEND = Strings.intern("treasury/spend");
	/** Bring accounts in, and create, lower and cancel the budgets on an account. */
	public static final AString BUDGET = Strings.intern("treasury/budget");
	/** Set an account's spend limit: the owner's decision. */
	public static final AString LIMIT = Strings.intern("treasury/limit");

	/** The treasury as a venue-relative resource. */
	public static final String ROOT = "treasury";
	/** The accounts, as a venue-relative resource. */
	public static final String ACCOUNTS = ROOT + "/accounts";
	/** The budgets, as a venue-relative resource. */
	public static final String BUDGETS = ROOT + "/budgets";

	static final AString K_ACCOUNTS = Strings.intern("accounts");
	static final AString K_BUDGETS = Strings.intern("budgets");
	static final AString K_ID = Strings.intern("id");
	static final AString K_RAIL = Strings.intern("rail");
	static final AString K_ACCOUNT = Strings.intern("account");
	static final AString K_CURRENCY = Strings.intern("currency");
	static final AString K_LIMIT = Strings.intern("limit");
	static final AString K_AMOUNT = Strings.intern("amount");
	static final AString K_DRAWN = Strings.intern("drawn");
	static final AString K_COMMITTED = Strings.intern("committed");
	static final AString K_REMAINING = Strings.intern("remaining");
	static final AString K_PURPOSE = Strings.intern("purpose");
	static final AString K_STATUS = Strings.intern("status");
	static final AString OPEN = Strings.intern("open");
	static final AString CANCELLED = Strings.intern("cancelled");

	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final int MAX_PURPOSE = 500;

	private final Map<String, Rail> rails = new ConcurrentHashMap<>();

	@Override
	public String getName() {
		return "treasury";
	}

	@Override
	public String getDescription() {
		return "The treasury: the accounts Njord manages on each payment rail, the spend limit the owner has "
			+ "approved on each, the budgets drawn from those limits, and payments from them.";
	}

	@Override
	protected void installAssets() {
		for (String op : OPS) installAsset("treasury/" + op, "/adapters/treasury/" + op + ".json");
	}

	/** Makes a rail's accounts possible: an account names its rail by {@link Rail#name()}. */
	public void addRail(Rail rail) {
		rails.put(rail.name(), rail);
	}

	/** An account as a venue-relative resource. */
	public static String account(String id) {
		return ACCOUNTS + "/" + id;
	}

	/** A budget as a venue-relative resource. */
	public static String budget(String id) {
		return BUDGETS + "/" + id;
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		try {
			ACell out = switch (getSubOperation(meta)) {
				case "account-add" -> accountAdd(ctx, input);
				case "account-list" -> accountList(ctx);
				case "limit-set" -> limitSet(ctx, input);
				case "budget-create" -> budgetCreate(ctx, input);
				case "budget-lower" -> budgetLower(ctx, input);
				case "budget-cancel" -> budgetCancel(ctx, input);
				case "budget-list" -> budgetList(ctx, input);
				default -> throw new IllegalArgumentException("Unknown treasury operation: " + getSubOperation(meta));
			};
			return CompletableFuture.completedFuture(out);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ------------------------------------------------------------------
	// Accounts and limits
	// ------------------------------------------------------------------

	/** Brings an account under the treasury, with a limit of nothing until the owner sets one. */
	private ACell accountAdd(RequestContext ctx, ACell input) {
		String id = requiredId(input, K_ID);
		require(ctx, ACCOUNTS, BUDGET);
		Rail rail = rail(requiredText(input, K_RAIL, 64));
		AMap<AString, ACell> record = rail.account(input)
			.assoc(K_ID, Strings.create(id))
			.assoc(K_RAIL, Strings.create(rail.name()))
			.assoc(K_LIMIT, CVMLong.ZERO);
		if (!(record.get(K_ACCOUNT) instanceof AString) || !(record.get(K_CURRENCY) instanceof AString)) {
			throw new IllegalStateException("Rail " + rail.name() + " did not name the account and its currency");
		}
		update(state -> {
			if (accountOf(state, id) != null) throw new IllegalStateException("Account " + id + " already exists");
			return RT.assocIn(state, record, K_ACCOUNTS, Strings.create(id));
		});
		return accountView(state(), record);
	}

	/** Every account, with its limit and what its budgets hold and have drawn. */
	private ACell accountList(RequestContext ctx) {
		require(ctx, ROOT, READ);
		ACell state = state();
		AVector<ACell> out = Vectors.empty();
		AMap<AString, ACell> accounts = RT.ensureMap(RT.getIn(state, K_ACCOUNTS));
		if (accounts != null) {
			for (long i = 0; i < accounts.count(); i++) {
				out = out.conj(accountView(state, RT.ensureMap(accounts.entryAt(i).getValue())));
			}
		}
		return Maps.of(K_ACCOUNTS, out);
	}

	/**
	 * Sets an account's spend limit: the running total the owner has approved for
	 * spending from it. Lowering it below what its budgets hold stops payments at
	 * the limit rather than cancelling budgets.
	 */
	private ACell limitSet(RequestContext ctx, ACell input) {
		String id = requiredId(input, K_ACCOUNT);
		require(ctx, account(id), LIMIT);
		long limit = amount(input, K_LIMIT, true);
		ACell state = update(s -> {
			existingAccount(s, id);
			return RT.assocIn(s, CVMLong.create(limit), K_ACCOUNTS, Strings.create(id), K_LIMIT);
		});
		return accountView(state, accountOf(state, id));
	}

	// ------------------------------------------------------------------
	// Budgets
	// ------------------------------------------------------------------

	/** An allowance of the account's currency, within what its limit has left. */
	private ACell budgetCreate(RequestContext ctx, ACell input) {
		String id = requiredId(input, K_ID);
		String accountId = requiredId(input, K_ACCOUNT);
		require(ctx, account(accountId), BUDGET);
		long amount = amount(input, K_AMOUNT, false);
		String purpose = optionalText(input, K_PURPOSE, MAX_PURPOSE);
		ACell state = update(s -> {
			AMap<AString, ACell> account = existingAccount(s, accountId);
			if (budgetOf(s, id) != null) throw new IllegalStateException("Budget " + id + " already exists");
			withinLimit(s, account, accountId, amount);
			AMap<AString, ACell> budget = Maps.of(
				K_ID, Strings.create(id),
				K_ACCOUNT, Strings.create(accountId),
				K_CURRENCY, account.get(K_CURRENCY),
				K_AMOUNT, CVMLong.create(amount),
				K_DRAWN, CVMLong.ZERO,
				K_STATUS, OPEN);
			if (purpose != null) budget = budget.assoc(K_PURPOSE, Strings.create(purpose));
			return RT.assocIn(s, budget, K_BUDGETS, Strings.create(id));
		});
		return budgetView(budgetOf(state, id));
	}

	/** Lowers an open budget, never below what it has drawn. */
	private ACell budgetLower(RequestContext ctx, ACell input) {
		String id = requiredId(input, K_ID);
		requireOnAccountOf(ctx, id, BUDGET);
		long amount = amount(input, K_AMOUNT, true);
		ACell state = update(s -> {
			AMap<AString, ACell> budget = openBudget(s, id);
			if (amount > longOf(budget, K_AMOUNT)) throw new IllegalArgumentException("A budget can only be lowered");
			if (amount < longOf(budget, K_DRAWN)) {
				throw new IllegalArgumentException("Budget " + id + " has already drawn " + longOf(budget, K_DRAWN));
			}
			return RT.assocIn(s, CVMLong.create(amount), K_BUDGETS, Strings.create(id), K_AMOUNT);
		});
		return budgetView(budgetOf(state, id));
	}

	/** Closes a budget: what it has not drawn returns to its account's limit. */
	private ACell budgetCancel(RequestContext ctx, ACell input) {
		String id = requiredId(input, K_ID);
		requireOnAccountOf(ctx, id, BUDGET);
		ACell state = update(s -> {
			AMap<AString, ACell> budget = openBudget(s, id);
			budget = budget.assoc(K_AMOUNT, budget.get(K_DRAWN)).assoc(K_STATUS, CANCELLED);
			return RT.assocIn(s, budget, K_BUDGETS, Strings.create(id));
		});
		return budgetView(budgetOf(state, id));
	}

	/** Every budget, or one account's, with what remains of each. */
	private ACell budgetList(RequestContext ctx, ACell input) {
		require(ctx, ROOT, READ);
		String accountId = optionalText(input, K_ACCOUNT, 64);
		AVector<ACell> out = Vectors.empty();
		AMap<AString, ACell> budgets = RT.ensureMap(RT.getIn(state(), K_BUDGETS));
		if (budgets != null) {
			for (long i = 0; i < budgets.count(); i++) {
				AMap<AString, ACell> budget = RT.ensureMap(budgets.entryAt(i).getValue());
				if (accountId == null || Strings.create(accountId).equals(budget.get(K_ACCOUNT))) out = out.conj(budgetView(budget));
			}
		}
		return Maps.of(K_BUDGETS, out);
	}

	// ------------------------------------------------------------------
	// Accounting
	// ------------------------------------------------------------------

	/** Refuses an amount more on {@code account}'s budgets that would take them past its limit. */
	private static void withinLimit(ACell state, AMap<AString, ACell> account, String accountId, long more) {
		long committed = Math.addExact(committed(state, accountId), more);
		long limit = longOf(account, K_LIMIT);
		if (committed > limit) {
			throw new IllegalStateException("That would take account " + accountId + "'s budgets to " + committed
				+ ", past its limit of " + limit + "; the owner raises a limit");
		}
	}

	/** What the account's budgets hold: each open budget's amount, and what each closed one drew. */
	static long committed(ACell state, String accountId) {
		return sumOverBudgets(state, accountId, K_AMOUNT);
	}

	/** What has been drawn from the account, across all its budgets. */
	static long drawn(ACell state, String accountId) {
		return sumOverBudgets(state, accountId, K_DRAWN);
	}

	private static long sumOverBudgets(ACell state, String accountId, AString field) {
		AMap<AString, ACell> budgets = RT.ensureMap(RT.getIn(state, K_BUDGETS));
		if (budgets == null) return 0;
		AString account = Strings.create(accountId);
		long sum = 0;
		for (long i = 0; i < budgets.count(); i++) {
			AMap<AString, ACell> budget = RT.ensureMap(budgets.entryAt(i).getValue());
			if (account.equals(budget.get(K_ACCOUNT))) sum = Math.addExact(sum, longOf(budget, field));
		}
		return sum;
	}

	// ------------------------------------------------------------------
	// State
	// ------------------------------------------------------------------

	/** The treasury's state as it stands. */
	ACell state() {
		return adapterState().get();
	}

	/**
	 * Applies {@code change} atomically and returns the new state, saved before
	 * anyone is told. A throw leaves the state as it was; {@code change} may run
	 * more than once, so it only computes.
	 */
	private ACell update(UnaryOperator<ACell> change) {
		ACell state = adapterState().update(s -> change.apply((s == null) ? Maps.empty() : s));
		engine.flush();
		return state;
	}

	static AMap<AString, ACell> accountOf(ACell state, String id) {
		return RT.ensureMap(RT.getIn(state, K_ACCOUNTS, Strings.create(id)));
	}

	static AMap<AString, ACell> budgetOf(ACell state, String id) {
		return RT.ensureMap(RT.getIn(state, K_BUDGETS, Strings.create(id)));
	}

	private static AMap<AString, ACell> existingAccount(ACell state, String id) {
		AMap<AString, ACell> account = accountOf(state, id);
		if (account == null) throw new IllegalArgumentException("No account " + id);
		return account;
	}

	private static AMap<AString, ACell> openBudget(ACell state, String id) {
		AMap<AString, ACell> budget = budgetOf(state, id);
		if (budget == null) throw new IllegalArgumentException("No budget " + id);
		if (!OPEN.equals(budget.get(K_STATUS))) throw new IllegalStateException("Budget " + id + " is " + budget.get(K_STATUS));
		return budget;
	}

	private static AMap<AString, ACell> accountView(ACell state, AMap<AString, ACell> account) {
		String id = account.get(K_ID).toString();
		return account
			.assoc(K_COMMITTED, CVMLong.create(committed(state, id)))
			.assoc(K_DRAWN, CVMLong.create(drawn(state, id)));
	}

	private static AMap<AString, ACell> budgetView(AMap<AString, ACell> budget) {
		return budget.assoc(K_REMAINING, CVMLong.create(longOf(budget, K_AMOUNT) - longOf(budget, K_DRAWN)));
	}

	static long longOf(AMap<AString, ACell> record, AString field) {
		CVMLong n = RT.ensureLong(record.get(field));
		return (n == null) ? 0 : n.longValue();
	}

	// ------------------------------------------------------------------
	// Authority and input
	// ------------------------------------------------------------------

	private void require(RequestContext ctx, String resource, AString ability) {
		engine.requireVenueAuthority(ctx, resource, ability);
	}

	/** Demands {@code ability} on the account a budget draws on. */
	private void requireOnAccountOf(RequestContext ctx, String budgetId, AString ability) {
		AMap<AString, ACell> budget = budgetOf(state(), budgetId);
		if (budget == null) throw new IllegalArgumentException("No budget " + budgetId);
		require(ctx, account(budget.get(K_ACCOUNT).toString()), ability);
	}

	private Rail rail(String name) {
		Rail rail = rails.get(name);
		if (rail == null) throw new IllegalArgumentException("No rail " + name + "; the treasury has " + rails.keySet());
		return rail;
	}

	/** An id that is safe as a resource path segment. */
	static String requiredId(ACell input, AString key) {
		String id = requiredText(input, key, 64);
		if (!ID.matcher(id).matches()) {
			throw new IllegalArgumentException(key + " must be lower-case letters, digits and hyphens");
		}
		return id;
	}

	static String requiredText(ACell input, AString key, int maxLength) {
		String text = optionalText(input, key, maxLength);
		if (text == null) throw new IllegalArgumentException(key + " is required");
		return text;
	}

	static String optionalText(ACell input, AString key, int maxLength) {
		AString value = RT.ensureString(RT.getIn(input, key));
		if (value == null) return null;
		String text = value.toString().strip();
		if (text.isEmpty()) return null;
		if (text.length() > maxLength) throw new IllegalArgumentException(key + " must be at most " + maxLength + " characters");
		return text;
	}

	/** A whole amount in the currency's smallest unit: positive, or with {@code zeroAllowed} not negative. */
	static long amount(ACell input, AString key, boolean zeroAllowed) {
		CVMLong n = RT.ensureLong(RT.getIn(input, key));
		if (n == null) throw new IllegalArgumentException(key + " is required, as a whole number of the currency's smallest unit");
		long v = n.longValue();
		if (v < 0 || (v == 0 && !zeroAllowed)) {
			throw new IllegalArgumentException(key + " must be " + (zeroAllowed ? "zero or more" : "more than zero"));
		}
		return v;
	}
}
