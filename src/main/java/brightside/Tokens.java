package brightside;

import java.util.List;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.MCPAdapter;
import covia.adapter.token.AccessToken;
import covia.adapter.token.TokenAdapter;
import covia.adapter.token.TokenAdapter.Issued;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The owner's named access tokens, worked on in-process as the owner in
 * person: Covia's {@link TokenAdapter} called directly, so a key never passes
 * through an operation's result, a job record or the model. Each token here is
 * accepted at the venue's MCP endpoint alone and lasts until revoked.
 *
 * <p>A named token is not a login: its requests are the owner's, but the agent
 * sees the token named on each turn's From line, and it can do only what its
 * tools and caps allow. The venue keeps each key sealed under its secret store
 * ({@code adapters.token.keepRawTokens}, on by default), so {@link #key} reads
 * it again. Where that is turned off, the key {@link #create} or
 * {@link #newKey} returns is the only copy.</p>
 */
public final class Tokens {

	/** What a token offers at the MCP endpoint: its tools, and the caps its requests act within. */
	public record Scope(AVector<ACell> tools, ACell caps) {
		public Scope {
			Objects.requireNonNull(tools, "tools");
			Objects.requireNonNull(caps, "caps");
		}
	}

	private final Engine engine;
	private final AString owner;

	public Tokens(Engine engine, AString owner) {
		this.engine = Objects.requireNonNull(engine, "engine");
		this.owner = Objects.requireNonNull(owner, "owner");
	}

	/** The owner's tokens, from every access point; none carries its key. */
	public List<AccessToken> list() {
		return adapter().list(inPerson(), token -> true);
	}

	/** Makes token {@code name} with {@code scope}, lasting until revoked, and returns it with its key. */
	public Issued create(String name, Scope scope) {
		return adapter().create(inPerson(), Strings.create(name), access(scope), scope.caps(), null,
			adapter().administration());
	}

	/** Token {@code name}'s key, as the venue keeps it. */
	public String key(String name) {
		return adapter().bearer(inPerson(), Strings.create(name), adapter().administration()).toString();
	}

	/** Gives token {@code name} a new key, which stops the old one working, and returns it with the new key. */
	public Issued newKey(String name) {
		return adapter().update(inPerson(), Strings.create(name), null, null, null, true, adapter().administration());
	}

	/** Revokes token {@code name}: its key stops working, on work it already started too. */
	public void revoke(String name) {
		adapter().delete(inPerson(), Strings.create(name), adapter().administration());
	}

	/** The MCP entry for {@code scope}: the token is accepted there alone, offering its tools. */
	static ACell access(Scope scope) {
		return Maps.of(MCPAdapter.ACCESS_POINT, Maps.of(Fields.TOOLS, scope.tools()));
	}

	/**
	 * The owner in person, as the app vouches for them: making a token, giving
	 * it a new key and reading its key each need that, because a key a user's
	 * token carries stands for the user.
	 */
	private RequestContext inPerson() {
		return RequestContext.of(owner);
	}

	private TokenAdapter adapter() {
		return (TokenAdapter) engine.getAdapter(TokenAdapter.NAME);
	}
}
