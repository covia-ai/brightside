package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.BrightSide;
import brightside.model.AgentRef;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;

final class BrightSideTest {

	@Test
	void agentOrderDoesNotDependOnWhichAgentWasInsertedAsCurrent() {
		List<AgentRef> bobCurrent = List.of(
			new AgentRef("brightside", "Brightside"),
			new AgentRef("bob", "Bob"),
			new AgentRef("amy", "Amy"));
		List<AgentRef> amyCurrent = List.of(
			new AgentRef("brightside", "Brightside"),
			new AgentRef("amy", "Amy"),
			new AgentRef("bob", "Bob"));

		List<String> expected = List.of("brightside", "amy", "bob");
		assertEquals(expected, ids(BrightSide.stableAgentOrder(bobCurrent, "brightside")));
		assertEquals(expected, ids(BrightSide.stableAgentOrder(amyCurrent, "brightside")));
	}

	@Test
	void settingsAccessTokenAuthenticatesAsTheNamedUser() {
		AKeyPair keyPair = AKeyPair.generate();
		String venueDID = "did:key:z6MkVenue";
		String userDID = venueDID + ":u:mike";
		long issuedAt = 1_787_832_000L;

		String token = BrightSide.signAccessToken(keyPair.getSeed().toHexString(), venueDID,
			userDID, issuedAt, 3600L);
		AMap<AString, ACell> claims = JWT.parse(Strings.create(token)).getClaims();

		assertEquals(userDID, claims.get(JWT.SUB).toString());
		assertEquals(venueDID, claims.get(JWT.ISS).toString());
		assertEquals(venueDID, claims.get(JWT.AUD).toString());
		assertEquals(issuedAt, ((convex.core.data.prim.CVMLong) claims.get(JWT.IAT)).longValue());
		assertEquals(issuedAt + 3600L, ((convex.core.data.prim.CVMLong) claims.get(JWT.EXP)).longValue());
	}

	@Test
	void operatorAccessTokenUsesTheVenueAsSubject() {
		AKeyPair keyPair = AKeyPair.generate();
		String venueDID = "did:key:z6MkVenue";
		String token = BrightSide.signAccessToken(keyPair.getSeed().toHexString(), venueDID,
			venueDID, 1_787_832_000L, 300L);
		AMap<AString, ACell> claims = JWT.parse(Strings.create(token)).getClaims();

		assertEquals(venueDID, claims.get(JWT.SUB).toString());
		assertEquals(venueDID, claims.get(JWT.ISS).toString());
		assertEquals(venueDID, claims.get(JWT.AUD).toString());
	}

	private static List<String> ids(List<AgentRef> agents) {
		return agents.stream().map(AgentRef::id).toList();
	}
}
