package brightside;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.adapter.token.AccessToken;
import covia.adapter.token.AccessTokens;
import covia.adapter.token.TokenAdapter;
import covia.adapter.token.TokenAdapter.Issued;
import covia.api.Fields;
import covia.exception.NotFoundException;
import covia.venue.Engine;

/** The owner's named tokens, worked on in-process against a real venue that keeps keys. */
class TokensTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void aTokenIsTheOwnersAndItsKeyIsKeptRotatedAndRevoked() {
		AString owner = owner("tokens-lifecycle");
		Tokens tokens = new Tokens(engine(), owner);
		Issued issued = tokens.create("claude-code", TokenPreset.READ_ONLY.scope("Tau"));
		AccessToken token = issued.token();
		assertEquals(owner, token.issuer(), "a token the owner makes is issued as the owner");
		assertEquals(java.util.Set.of(MCPAdapter.ACCESS_POINT), token.accessPoints(), "accepted at MCP alone");
		assertNull(token.expires(), "a named token lasts until revoked");
		assertEquals(List.of("claude-code"), names(tokens.list()));
		assertEquals(issued.bearer(), tokens.key("claude-code"), "the venue keeps the key to show again");
		assertEquals(owner, resolves(issued.bearer()));

		Issued rotated = tokens.newKey("claude-code");
		assertNotEquals(issued.bearer(), rotated.bearer());
		assertEquals(rotated.bearer(), tokens.key("claude-code"));
		assertNull(store().lookup(issued.bearer()), "a new key retires the old one");
		assertEquals(owner, resolves(rotated.bearer()));

		tokens.revoke("claude-code");
		assertTrue(tokens.list().isEmpty());
		assertNull(store().lookup(rotated.bearer()), "a revoked token's key stops working");
		assertThrows(NotFoundException.class, () -> tokens.key("claude-code"));
	}

	@Test
	void everyPresetMakesATokenWithinTheOwnersAuthority() {
		AString owner = owner("tokens-presets");
		Tokens tokens = new Tokens(engine(), owner);
		for (TokenPreset preset : TokenPreset.values()) {
			String name = preset.name().toLowerCase().replace('_', '-');
			Tokens.Scope scope = preset.scope("Tau");
			AccessToken token = tokens.create(name, scope).token();
			assertEquals(scope, Tokens.scope(token), preset + " reads back as it was made");
			assertEquals(preset, TokenPreset.matching(Tokens.scope(token), "Tau"), "the list names its preset");
		}
		assertEquals(TokenPreset.values().length, tokens.list().size());
	}

	@Test
	void aCustomScopeMatchesNoPreset() {
		Tokens tokens = new Tokens(engine(), owner("tokens-custom"));
		Tokens.Scope custom = new Tokens.Scope(Vectors.of(Strings.create("v/ops/covia/read")), Strings.create("unrestricted"));
		AccessToken token = tokens.create("custom", custom).token();
		assertEquals(custom.tools(), Tokens.scope(token).tools());
		assertNull(TokenPreset.matching(Tokens.scope(token), "Tau"));
		assertNull(TokenPreset.matching(TokenPreset.ASK_ASSISTANT.scope("Tau"), "Another"),
			"asking the assistant names the chat agent");
	}

	/** The namespace a key resolves to, or null when no token stands for it. */
	private static AString resolves(String bearer) {
		var reference = store().lookup(bearer);
		return (reference == null) ? null : RT.ensureString(reference.get(Fields.OWNER));
	}

	private static List<String> names(List<AccessToken> tokens) {
		return tokens.stream().map(t -> t.name().toString()).toList();
	}

	private static AccessTokens store() {
		return ((TokenAdapter) engine().getAdapter(TokenAdapter.NAME)).tokens();
	}

	private static Engine engine() {
		return shared.venue().engine();
	}

	private static AString owner(String name) {
		AString did = Strings.create(Identity.of(name).userDID(shared.venue().did()));
		engine().getVenueState().users().ensure(did.toString());
		return did;
	}
}
