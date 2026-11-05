package brightside.treasury;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import brightside.CallGrants;
import brightside.VenueGrants;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
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
 * {@code accounts/<id>}, {@code budgets/<id>} and the {@code ledger} of
 * payments. Every change is one atomic update of that state.
 */
public class TreasuryAdapter extends AAdapter implements CallGrants {

	/** Every operation, as installed under {@code v/ops/treasury/}. */
	public static final List<String> OPS = List.of(
		"account-add", "account-list", "limit-set",
		"budget-create", "budget-lower", "budget-cancel", "budget-list",
		"pay", "ledger");

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
	static final AString K_LEDGER = Strings.intern("ledger");
	static final AString K_PAYMENTS = Strings.intern("payments");
	static final AString K_N = Strings.intern("n");
	static final AString K_BUDGET = Strings.intern("budget");
	static final AString K_RECIPIENT = Strings.intern("recipient");
	static final AString K_REASON = Strings.intern("reason");
	static final AString K_BY = Strings.intern("by");
	static final AString K_AT = Strings.intern("at");
	static final AString K_RESULT = Strings.intern("result");
	static final AString K_ERROR = Strings.intern("error");
	static final AString K_BALANCE = Strings.intern("balance");
	static final AString K_BALANCE_ERROR = Strings.intern("balanceError");
	/** A payment drawn and sent to its rail, not yet settled. */
	static final AString PENDING = Strings.intern("pending");
	static final AString PAID = Strings.intern("paid");
	/** The rail is sure nothing moved: the draw went back to the budget. */
	static final AString NOT_PAID = Strings.intern("not-paid");
	/** The rail cannot say whether it moved: it stays drawn until someone finds out. */
	static final AString UNKNOWN = Strings.intern("unknown");

	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final int MAX_PURPOSE = 500;
	private static final int MAX_REASON = 1000;

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
		addRail(new ConvexRail(engine));
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
			return switch (getSubOperation(meta)) {
				case "account-add" -> done(accountAdd(ctx, input));
				case "account-list" -> accountList(ctx);
				case "limit-set" -> done(limitSet(ctx, input));
				case "budget-create" -> done(budgetCreate(ctx, input));
				case "budget-lower" -> done(budgetLower(ctx, input));
				case "budget-cancel" -> done(budgetCancel(ctx, input));
				case "budget-list" -> done(budgetList(ctx, input));
				case "pay" -> pay(ctx, input);
				case "ledger" -> done(ledger(ctx, input));
				default -> throw new IllegalArgumentException("Unknown treasury operation: " + getSubOperation(meta));
			};
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

	/**
	 * Every account, with its limit, what its budgets hold and have drawn, and
	 * its balance as its rail reads it — or why the rail could not.
	 */
	private CompletableFuture<ACell> accountList(RequestContext ctx) {
		require(ctx, ROOT, READ);
		ACell state = state();
		List<CompletableFuture<AMap<AString, ACell>>> views = new ArrayList<>();
		AMap<AString, ACell> accounts = RT.ensureMap(RT.getIn(state, K_ACCOUNTS));
		if (accounts != null) {
			for (long i = 0; i < accounts.count(); i++) {
				AMap<AString, ACell> account = RT.ensureMap(accounts.entryAt(i).getValue());
				AMap<AString, ACell> view = accountView(state, account);
				views.add(rail(account.get(K_RAIL).toString()).balance(account).handle((balance, failure) -> (failure == null)
					? view.assoc(K_BALANCE, CVMLong.create(balance))
					: view.assoc(K_BALANCE_ERROR, Strings.create(rootMessage(failure)))));
			}
		}
		return CompletableFuture.allOf(views.toArray(CompletableFuture[]::new)).thenApply(all -> {
			AVector<ACell> out = Vectors.empty();
			for (CompletableFuture<AMap<AString, ACell>> view : views) out = out.conj(view.join());
			return Maps.of(K_ACCOUNTS, out);
		});
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
	// Paying
	// ------------------------------------------------------------------

	/**
	 * Pays from a budget. One update draws the budget and its account's limit
	 * and writes the ledger line, saved before the rail is asked; a second
	 * settles the line: paid, not paid (the draw returns to the budget), or
	 * unknown (it stays drawn).
	 */
	private CompletableFuture<ACell> pay(RequestContext ctx, ACell input) {
		String budgetId = requiredId(input, K_BUDGET);
		require(ctx, budget(budgetId), SPEND);
		long amount = amount(input, K_AMOUNT, false);
		String reason = requiredText(input, K_REASON, MAX_REASON);
		ACell before = state();
		AMap<AString, ACell> known = budgetOf(before, budgetId);
		if (known == null) throw new IllegalArgumentException("No budget " + budgetId);
		Rail rail = rail(existingAccount(before, known.get(K_ACCOUNT).toString()).get(K_RAIL).toString());
		AString recipient = rail.recipient(RT.getIn(input, K_RECIPIENT));
		AString by = ctx.getCallerDID();

		AtomicLong line = new AtomicLong();
		ACell drawn = update(s -> {
			AMap<AString, ACell> budget = openBudget(s, budgetId);
			String accountId = budget.get(K_ACCOUNT).toString();
			AMap<AString, ACell> account = existingAccount(s, accountId);
			long budgetDrawn = Math.addExact(longOf(budget, K_DRAWN), amount);
			if (budgetDrawn > longOf(budget, K_AMOUNT)) {
				throw new IllegalStateException("Budget " + budgetId + " has " + (longOf(budget, K_AMOUNT) - longOf(budget, K_DRAWN)) + " left");
			}
			if (Math.addExact(drawn(s, accountId), amount) > longOf(account, K_LIMIT)) {
				throw new IllegalStateException("That would take account " + accountId + " past its limit of "
					+ longOf(account, K_LIMIT) + "; the owner raises a limit");
			}
			AVector<ACell> ledger = ledgerOf(s);
			line.set(ledger.count());
			AMap<AString, ACell> entry = Maps.of(
				K_N, CVMLong.create(ledger.count()),
				K_BUDGET, Strings.create(budgetId),
				K_ACCOUNT, Strings.create(accountId),
				K_RECIPIENT, recipient,
				K_CURRENCY, account.get(K_CURRENCY),
				K_AMOUNT, CVMLong.create(amount),
				K_REASON, Strings.create(reason),
				K_AT, CVMLong.create(Utils.getCurrentTimestamp()),
				K_STATUS, PENDING);
			if (by != null) entry = entry.assoc(K_BY, by);
			s = RT.assocIn(s, CVMLong.create(budgetDrawn), K_BUDGETS, Strings.create(budgetId), K_DRAWN);
			return RT.assocIn(s, ledger.conj(entry), K_LEDGER);
		});
		long n = line.get();
		AMap<AString, ACell> account = accountOf(drawn, budgetOf(drawn, budgetId).get(K_ACCOUNT).toString());
		CompletableFuture<ACell> paying;
		try {
			paying = rail.pay(account, recipient, amount);
		} catch (RuntimeException e) {
			paying = CompletableFuture.failedFuture(e);
		}
		return paying.handle((result, failure) -> settle(budgetId, amount, n, result, failure));
	}

	/** Settles ledger line {@code n} with what the rail said, returning the line or failing as the payment did. */
	private ACell settle(String budgetId, long amount, long n, ACell result, Throwable failure) {
		Throwable cause = (failure == null) ? null : root(failure);
		AString status = (cause == null) ? PAID : (cause instanceof Rail.NotPaid) ? NOT_PAID : UNKNOWN;
		ACell state = update(s -> {
			ACell settled = RT.assocIn(s, status, K_LEDGER, CVMLong.create(n), K_STATUS);
			if (result != null) settled = RT.assocIn(settled, result, K_LEDGER, CVMLong.create(n), K_RESULT);
			if (cause != null) settled = RT.assocIn(settled, Strings.create(rootMessage(cause)), K_LEDGER, CVMLong.create(n), K_ERROR);
			if (status == NOT_PAID) {
				long drawn = longOf(budgetOf(s, budgetId), K_DRAWN) - amount;
				settled = RT.assocIn(settled, CVMLong.create(drawn), K_BUDGETS, Strings.create(budgetId), K_DRAWN);
			}
			return settled;
		});
		ACell entry = ledgerOf(state).get(n);
		if (status == PAID) return entry;
		String what = (status == NOT_PAID) ? "Not paid, and the budget has it back: " : "Whether it was paid is not known, so it stays drawn (ledger line " + n + "): ";
		throw new CompletionException(new IllegalStateException(what + rootMessage(cause)));
	}

	/** The payments made, oldest first, or one budget's. */
	private ACell ledger(RequestContext ctx, ACell input) {
		require(ctx, ROOT, READ);
		String budgetId = optionalText(input, K_BUDGET, 64);
		AVector<ACell> ledger = ledgerOf(state());
		if (budgetId == null) return Maps.of(K_PAYMENTS, ledger);
		AString budget = Strings.create(budgetId);
		AVector<ACell> out = Vectors.empty();
		for (long i = 0; i < ledger.count(); i++) {
			if (budget.equals(RT.getIn(ledger.get(i), K_BUDGET))) out = out.conj(ledger.get(i));
		}
		return Maps.of(K_PAYMENTS, out);
	}

	static AVector<ACell> ledgerOf(ACell state) {
		AVector<ACell> ledger = RT.ensureVector(RT.getIn(state, K_LEDGER));
		return (ledger == null) ? Vectors.empty() : ledger;
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

	private static CompletableFuture<ACell> done(ACell value) {
		return CompletableFuture.completedFuture(value);
	}

	private static Throwable root(Throwable failure) {
		Throwable t = failure;
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
		return t;
	}

	private static String rootMessage(Throwable failure) {
		Throwable t = root(failure);
		String m = t.getMessage();
		return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
	}

	/**
	 * What one call needs: its ability on the account or budget it names, or on
	 * the treasury for a read. Setting a limit is never granted this way.
	 */
	@Override
	public AVector<ACell> capabilitiesFor(String operation, ACell input) {
		String op = operation.substring(operation.lastIndexOf('/') + 1);
		return Vectors.of(switch (op) {
			case "account-add" -> cap(ACCOUNTS, BUDGET);
			case "account-list", "budget-list", "ledger" -> cap(ROOT, READ);
			case "budget-create" -> cap(account(requiredId(input, K_ACCOUNT)), BUDGET);
			case "budget-lower", "budget-cancel" -> {
				String id = requiredId(input, K_ID);
				AMap<AString, ACell> budget = budgetOf(state(), id);
				if (budget == null) throw new IllegalArgumentException("No budget " + id);
				yield cap(account(budget.get(K_ACCOUNT).toString()), BUDGET);
			}
			case "pay" -> cap(budget(requiredId(input, K_BUDGET)), SPEND);
			case "limit-set" -> throw new IllegalArgumentException("An account's limit is the owner's to set");
			default -> throw new IllegalArgumentException("Not a treasury operation: " + operation);
		});
	}

	/** A capability over a venue-relative treasury resource. */
	private AMap<AString, ACell> cap(String resource, AString ability) {
		return VenueGrants.cap(Strings.create(engine.getDIDString() + "/" + resource), ability);
	}

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
