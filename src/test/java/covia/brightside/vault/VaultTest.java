package covia.brightside.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.brightside.AppConfig;
import covia.brightside.EmbeddedVenue;
import covia.grid.Job;
import covia.grid.Venue;

class VaultTest {

	@Test
	void roundTripsAnEncryptedSeed(@TempDir Path home) throws Exception {
		String seed = Mnemonic.toSeedHex(Mnemonic.generate(12));
		assertFalse(Vault.exists(home));
		Vault.open(home, "correct horse battery staple".toCharArray()).storeSeed(seed);
		assertTrue(Vault.exists(home));
		// Re-derive from the same passphrase (same salt) → same keys → decrypts.
		assertEquals(seed, Vault.open(home, "correct horse battery staple".toCharArray()).seedHex());
	}

	@Test
	void wrongPassphraseCannotDecryptTheSeed(@TempDir Path home) throws Exception {
		Vault.open(home, "right".toCharArray()).storeSeed(Mnemonic.toSeedHex(Mnemonic.generate(12)));
		Vault wrong = Vault.open(home, "WRONG".toCharArray());
		assertThrows(IOException.class, wrong::seedHex);
	}

	@Test
	void secureInjectsSeedAndEtchV3(@TempDir Path home) throws Exception {
		Vault vault = Vault.open(home, "pw".toCharArray());
		AMap<AString, ACell> secured = vault.secure(Maps.empty(), "ab".repeat(32));
		assertEquals(Strings.create("ab".repeat(32)), secured.get(Strings.create("seed")));
		ACell etch = secured.get(Strings.create("etch"));
		assertEquals(CVMLong.create(3), RT.getIn(etch, "version"));
		assertEquals(Strings.create("chacha20"), RT.getIn(etch, "cipher"));
		assertEquals(64, RT.getIn(etch, "key").toString().length(), "32-byte vault key as hex");
	}

	@Test
	void storesAndReadsEncryptedApiKeys(@TempDir Path home) throws Exception {
		Vault vault = Vault.open(home, "pw".toCharArray());
		assertTrue(vault.apiKeys().isEmpty());
		vault.storeApiKey("ANTHROPIC_API_KEY", "sk-ant-123");
		vault.storeApiKey("OPENAI_API_KEY", "sk-oai-456");

		// Re-open with the same passphrase → both keys decrypt.
		Vault again = Vault.open(home, "pw".toCharArray());
		assertEquals("sk-ant-123", again.apiKeys().get("ANTHROPIC_API_KEY"));
		assertEquals("sk-oai-456", again.apiKeys().get("OPENAI_API_KEY"));

		// A wrong passphrase can't read them.
		assertThrows(IOException.class, () -> Vault.open(home, "WRONG".toCharArray()).apiKeys());
	}

	@Test
	void venueRunsEncryptedAndRejectsAWrongKey(@TempDir Path home) throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		String seed = Mnemonic.toSeedHex(Mnemonic.generate(12));
		Vault vault = Vault.open(home, "vault-pass".toCharArray());
		AMap<AString, ACell> base = AppConfig.defaultVenue(home).assoc(Fields.PORT, CVMLong.create(port));
		AMap<AString, ACell> secured = vault.secure(base, seed);

		// Launch on the encrypted store, write a marker into it, close.
		EmbeddedVenue venue = EmbeddedVenue.launch(secured);
		try {
			Venue op = venue.clientAs(venue.did());
			write(op, "w/vault-marker", "hi there");
			assertEquals("hi there", str(read(op, "w/vault-marker")));
		} finally {
			venue.close();
		}

		// Reopen with the same vault key + seed → the marker persisted (decrypted).
		EmbeddedVenue again = EmbeddedVenue.launch(secured);
		try {
			assertEquals("hi there", str(read(again.clientAs(again.did()), "w/vault-marker")),
				"encrypted store persisted and reopened");
		} finally {
			again.close();
		}

		// A wrong passphrase → wrong vault key → the store will not open.
		AMap<AString, ACell> wrong = Vault.open(home, "not-the-pass".toCharArray()).secure(base, seed);
		assertThrows(Exception.class, () -> EmbeddedVenue.launch(wrong).close(),
			"a wrong vault key cannot open the encrypted store");
	}

	private static void write(Venue client, String path, String value) throws Exception {
		Job job = client.invoke("v/ops/covia/write",
			Maps.of("path", path, "value", Strings.create(value))).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);
	}

	private static ACell read(Venue client, String path) throws Exception {
		Job job = client.invoke("v/ops/covia/read", Maps.of("path", path)).get(30, TimeUnit.SECONDS);
		return RT.getIn(job.future().get(30, TimeUnit.SECONDS), "value");
	}

	private static String str(ACell cell) {
		return (cell == null) ? null : cell.toString();
	}
}
