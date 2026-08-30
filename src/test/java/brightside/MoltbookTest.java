package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;

/**
 * How Brightside reads what Moltbook says — the registration, profile and
 * status responses documented in Moltbook's skill.md — without a network.
 */
class MoltbookTest {

	@Test
	void registrationYieldsTheKeyAndTheClaimPage() throws IOException {
		String json = """
			{"agent": {"api_key": "moltbook_abc", "claim_url": "https://www.moltbook.com/claim/moltbook_claim_xyz",
			           "verification_code": "reef-X4B2"}, "important": "SAVE YOUR API KEY!"}
			""";
		Moltbook.Registration r = Moltbook.parseRegistration(json);
		assertEquals("moltbook_abc", r.apiKey());
		assertEquals("https://www.moltbook.com/claim/moltbook_claim_xyz", r.claimUrl());
		assertEquals("reef-X4B2", r.verificationCode());
	}

	@Test
	void registrationWithoutAKeyIsRefused() {
		assertThrows(IOException.class, () -> Moltbook.parseRegistration("{\"success\": false, \"error\": \"Name taken\"}"));
		assertThrows(IOException.class, () -> Moltbook.parseRegistration("<html>not json</html>"));
	}

	@Test
	void claimedAccountCarriesItsProfileAndNoClaimPage() throws IOException {
		String me = """
			{"success": true, "agent": {"name": "BrightsideForMike", "karma": 42, "follower_count": 15,
			 "posts_count": 12, "is_claimed": true}}
			""";
		ACell record = Maps.of("name", "BrightsideForMike", "claimUrl", "https://www.moltbook.com/claim/x");
		Moltbook.Account a = Moltbook.parseAccount(me, "{\"status\": \"claimed\"}", record);
		assertTrue(a.claimed());
		assertFalse(a.pending());
		assertEquals("BrightsideForMike", a.name());
		assertEquals(42, a.karma());
		assertEquals(15, a.followers());
		assertEquals(12, a.posts());
		assertNull(a.claimUrl(), "a claimed account no longer needs the claim page");
		assertNull(a.error());
	}

	@Test
	void pendingAccountKeepsTheClaimPageFromTheRecord() throws IOException {
		String me = "{\"success\": true, \"agent\": {\"name\": \"NewMolty\", \"karma\": 0, \"is_claimed\": false}}";
		ACell record = Maps.of("name", "NewMolty", "claimUrl", "https://www.moltbook.com/claim/moltbook_claim_1",
			"verificationCode", "reef-1");
		Moltbook.Account a = Moltbook.parseAccount(me, "{\"status\": \"pending_claim\"}", record);
		assertTrue(a.pending());
		assertEquals("https://www.moltbook.com/claim/moltbook_claim_1", a.claimUrl());
		assertEquals("reef-1", a.verificationCode());
	}

	@Test
	void claimStatusFallsBackToTheProfileWhenStatusIsUnavailable() throws IOException {
		String me = "{\"agent\": {\"name\": \"X\", \"is_claimed\": false}}";
		assertTrue(Moltbook.parseAccount(me, null, null).pending());
		String claimed = "{\"agent\": {\"name\": \"X\", \"is_claimed\": true}}";
		assertTrue(Moltbook.parseAccount(claimed, null, null).claimed());
	}

	@Test
	void recordAloneSaysWhatItRemembers() {
		ACell record = Maps.of("name", "Offline", "claimUrl", "https://www.moltbook.com/claim/o");
		Moltbook.Account a = Moltbook.fromRecord(record, "no network");
		assertEquals("Offline", a.name());
		assertEquals("https://www.moltbook.com/claim/o", a.claimUrl());
		assertEquals("no network", a.error());
		assertFalse(a.claimed());
	}

	@Test
	void errorsQuoteMoltbooksErrorAndHint() {
		assertEquals("Rate limit exceeded (Wait 45 seconds)",
			Moltbook.errorOf(429, "{\"success\": false, \"error\": \"Rate limit exceeded\", \"hint\": \"Wait 45 seconds\"}"));
		assertEquals("Invalid API key", Moltbook.errorOf(401, "{\"error\": \"Invalid API key\"}"));
		assertEquals("Moltbook answered HTTP 502", Moltbook.errorOf(502, "<html>bad gateway</html>"));
	}
}
