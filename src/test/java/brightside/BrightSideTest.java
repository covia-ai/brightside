package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
	void theCommandLineNamesAConfigAndMayAskToUnlock() {
		assertEquals(new BrightSide.Launch(AppConfig.DEFAULT_FILE, false, false), BrightSide.Launch.parse());
		java.nio.file.Path other = java.nio.file.Path.of("other", "config.json").toAbsolutePath().normalize();
		assertEquals(new BrightSide.Launch(other, true, false), BrightSide.Launch.parse("--unlock", "other/config.json"));
		assertEquals(new BrightSide.Launch(other, true, true), BrightSide.Launch.parse("other/config.json", "--takeover", "--unlock"));
		assertEquals(new BrightSide.Launch(AppConfig.DEFAULT_FILE, false, true), BrightSide.Launch.parse("--takeover"));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> BrightSide.Launch.parse("--unlcok"), "a mistyped option is not taken for a config file");
	}

	@Test
	void forgettingTheStoredPassphraseStopsUnlockingWithoutAsking(@TempDir Path home) throws Exception {
		Path file = home.resolve("config.json");
		AppConfig config = AppConfig.load(file);
		BrightSide app = new BrightSide(config, file, Prefs.load(home));
		RememberedPassphrase.store(home, "correct horse".toCharArray());
		app.setUnlockWithoutAsking(true);
		assertTrue(new BrightSide(config, file, Prefs.load(home)).unlockWithoutAsking(), "kept for the next launch");

		app.forgetRememberedPassphrase();

		assertFalse(app.hasRememberedPassphrase());
		// A passphrase stored again later is not consent to unlock with it unasked.
		assertFalse(new BrightSide(config, file, Prefs.load(home)).unlockWithoutAsking());
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
