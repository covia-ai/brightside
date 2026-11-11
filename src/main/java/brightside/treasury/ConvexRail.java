package brightside.treasury;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import brightside.VenueGrants;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.CAIP;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.api.Abilities;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Convex as a {@link Rail}, in CVM, through Covia's own Convex operations: a
 * balance is a {@code convex/query}, and a payment a {@code (transfer #r n)}
 * built from a parsed address and a whole amount, never from text, sent with
 * {@code convex/transact}. The account's signing key goes by reference: Covia
 * resolves it in the secret store of the user that holds it (its custody),
 * which the payment enters through {@code user:sudo} under a grant the venue
 * signs for that user alone. The treasury never sees the key.
 */
public final class ConvexRail implements Rail {

	public static final String NAME = "convex";

	private static final String OP_QUERY = "v/ops/convex/query";
	private static final String OP_TRANSACT = "v/ops/convex/transact";
	private static final String OP_USER_SUDO = "v/ops/user/sudo";

	private static final AString K_ACCOUNT = Strings.intern("account");
	private static final AString K_CURRENCY = Strings.intern("currency");
	private static final AString K_NETWORK = Strings.intern("network");
	private static final AString K_PEER = Strings.intern("peer");
	private static final AString K_ADDRESS = Strings.intern("address");
	private static final AString K_CREDENTIAL = Strings.intern("credential");
	private static final AString K_CUSTODY = Strings.intern("custody");
	private static final AString K_SOURCE = Strings.intern("source");
	private static final AString K_SEED = Strings.intern("seed");
	private static final AString K_VALUE = Strings.intern("value");
	private static final AString K_ERROR_CODE = Strings.intern("errorCode");
	private static final AString K_DID = Strings.intern("did");
	private static final AString K_OPERATION = Strings.intern("operation");
	private static final AString K_INPUT = Strings.intern("input");

	private static final Pattern NETWORK = Pattern.compile("convex:[-_a-zA-Z0-9]{1,32}");
	private static final Pattern CREDENTIAL = Pattern.compile("s/[A-Za-z0-9_-]{1,64}");

	/**
	 * Error codes in Convex's answer that mean the network refused or rolled back
	 * the transaction, so nothing moved. Any other leaves it unknown: a timeout,
	 * and {@code CONNECT}, which Convex also reports for a request a closing
	 * connection left unanswered.
	 */
	private static final Set<String> NOT_APPLIED = Set.of(
		"FUNDS", "NOBODY", "SEQUENCE", "SIGNATURE", "JUICE", "ARGUMENT", "CAST", "STATE", "TRUST",
		"ASSERT", "COMPILE", "SYNTAX", "UNDECLARED", "BOUNDS", "ARITY", "DEPTH", "MEMORY", "ROLLBACK",
		"LIMIT");

	private final Engine engine;

	public ConvexRail(Engine engine) {
		this.engine = engine;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public AMap<AString, ACell> account(ACell input) {
		String network = TreasuryAdapter.requiredText(input, K_NETWORK, 64);
		if (!NETWORK.matcher(network).matches()) throw new InvalidInputException("network must be a Convex CAIP-2 id, e.g. convex:main");
		Address address = address(RT.getIn(input, K_ADDRESS), "address");
		String credential = TreasuryAdapter.requiredText(input, K_CREDENTIAL, 66);
		if (!CREDENTIAL.matcher(credential).matches()) {
			throw new InvalidInputException("credential must name the signing key's secret, s/NAME");
		}
		String custody = TreasuryAdapter.requiredText(input, K_CUSTODY, 512);
		if (!custody.startsWith(engine.getDIDString() + ":u:")) {
			throw new InvalidInputException("custody must be one of this venue's users, the one holding the key");
		}
		AMap<AString, ACell> account = Maps.of(
			K_ACCOUNT, Strings.create(network + ":" + address.longValue()),
			K_CURRENCY, Strings.create(network + "/" + CAIP.CONVEX_ASSET_ID),
			K_NETWORK, Strings.create(network),
			K_ADDRESS, Strings.create(address.toString()),
			K_CREDENTIAL, Strings.create(credential),
			K_CUSTODY, Strings.create(custody));
		String peer = TreasuryAdapter.optionalText(input, K_PEER, 256);
		return (peer == null) ? account : account.assoc(K_PEER, Strings.create(peer));
	}

	@Override
	public AString recipient(ACell recipient) {
		return Strings.create(address(recipient, "recipient").toString());
	}

	@Override
	public CompletableFuture<Long> balance(AMap<AString, ACell> account) {
		AString address = RT.ensureString(account.get(K_ADDRESS));
		AMap<AString, ACell> input = withPeer(account, Maps.of(K_ADDRESS, address, K_SOURCE, Strings.create("(balance " + address + ")")));
		return engine.jobs().invokeInternal(OP_QUERY, input, engine.venueContext()).thenApply(result -> {
			CVMLong value = RT.ensureLong(RT.getIn(result, K_VALUE));
			if (value == null) throw new ConflictException("Convex did not give a balance: " + RT.getIn(result, K_ERROR_CODE));
			return value.longValue();
		});
	}

	/**
	 * Pays by when a failure happens, not by what it looks like. Anything wrong
	 * before the payment is handed to Covia means nothing was sent. Once it is
	 * handed over, Covia's jobs reword a failure on the way back, so a timeout or
	 * a connection lost after sending cannot be told from one before: only
	 * Convex's own answer that it refused the transaction says nothing moved.
	 */
	@Override
	public CompletableFuture<ACell> pay(AMap<AString, ACell> account, AString recipient, long amount) {
		AMap<AString, ACell> sudo;
		RequestContext asVenue;
		try {
			Address to = address(recipient, "recipient");
			AString custody = RT.ensureString(account.get(K_CUSTODY));
			if (custody == null) throw new ConflictException("The account names no custody");
			AMap<AString, ACell> transfer = withPeer(account, Maps.of(
				K_ADDRESS, account.get(K_ADDRESS),
				K_SEED, account.get(K_CREDENTIAL),
				K_SOURCE, Strings.create("(transfer " + to + " " + amount + ")")));
			// The key is used where it is kept, under a grant for that user alone.
			ACell grant = new VenueGrants(engine).grant(Strings.create(engine.getDIDString()), Vectors.of(
				VenueGrants.cap(custody, Abilities.USER_SUDO),
				VenueGrants.cap(Strings.create(custody + "/"), Strings.create("*"))));
			asVenue = RequestContext.of(engine.getDIDString()).withProofs(Vectors.of(grant));
			sudo = Maps.of(K_DID, custody, K_OPERATION, Strings.create(OP_TRANSACT), K_INPUT, transfer);
		} catch (RuntimeException e) {
			throw new NotPaid("Not sent: " + e.getMessage());
		}
		return engine.jobs().invokeInternal(OP_USER_SUDO, sudo, asVenue).handle((result, failure) -> {
			if (failure != null) {
				throw new CompletionException(new ConflictException(
					"Convex gave no clear answer, so whether it was paid is not known: " + rootMessage(failure)));
			}
			AString error = RT.ensureString(RT.getIn(result, K_ERROR_CODE));
			if (error == null) return result;
			if (NOT_APPLIED.contains(error.toString())) throw new CompletionException(new NotPaid("Convex refused it: " + error));
			throw new CompletionException(new ConflictException("Convex answered " + error + "; whether it was paid is not known"));
		});
	}

	private static String rootMessage(Throwable failure) {
		Throwable t = failure;
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
		return (t.getMessage() == null) ? t.getClass().getSimpleName() : t.getMessage();
	}

	private static AMap<AString, ACell> withPeer(AMap<AString, ACell> account, AMap<AString, ACell> input) {
		ACell peer = account.get(K_PEER);
		return (peer == null) ? input : input.assoc(K_PEER, peer);
	}

	private static Address address(ACell cell, String what) {
		AString text = RT.ensureString(cell);
		Address address = null;
		try {
			if (text != null) address = Address.parse(text.toString().strip());
		} catch (RuntimeException e) {
			address = null;
		}
		if (address == null) throw new InvalidInputException(what + " must be a Convex address, #n");
		return address;
	}
}
