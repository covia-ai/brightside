package brightside.treasury;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/**
 * How money moves on one network or payment system, for the treasury. The
 * treasury keeps accounts, limits, budgets and the ledger and knows nothing of
 * any network; a rail knows how its accounts and recipients are named, how to
 * read a balance and how to pay. A rail that fits an existing Covia operation
 * delegates to it. See {@code docs/TREASURY.md}.
 *
 * <p>An account reaches a rail as the record the treasury keeps for it: what
 * {@link #account} returned, plus the treasury's own fields.
 */
public interface Rail {

	/** The rail's name, as an account names its rail ({@code convex}). */
	String name();

	/**
	 * Checks an account as {@code account-add} was given it and returns what the
	 * treasury keeps for it: at least {@code account}, its name on this rail, and
	 * {@code currency}, what it is spent in; anything else is the rail's own.
	 *
	 * @throws IllegalArgumentException when the input does not name a usable account
	 */
	AMap<AString, ACell> account(ACell input);

	/**
	 * A recipient's name on this rail, in the form the rail pays to.
	 *
	 * @throws IllegalArgumentException when it is not a recipient this rail can pay
	 */
	AString recipient(ACell recipient);

	/** The account's balance in its currency, in the currency's smallest unit. */
	CompletableFuture<Long> balance(AMap<AString, ACell> account);

	/**
	 * Pays {@code amount}, in the account's currency's smallest unit, from the
	 * account to {@code recipient}. Completes with what the rail reports of the
	 * payment, kept on its ledger line, or fails if nothing was paid.
	 */
	CompletableFuture<ACell> pay(AMap<AString, ACell> account, AString recipient, long amount);
}
