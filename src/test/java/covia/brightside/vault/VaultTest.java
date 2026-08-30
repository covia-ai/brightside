package covia.brightside.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
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
	void neverOverwritesAnExistingSalt(@TempDir Path home) throws Exception {
		String seed = Mnemonic.toSeedHex(Mnemonic.generate(12));
		Vault.open(home, "pw".toCharArray()).storeSeed(seed);
		Path saltFile = home.resolve(Vault.SALT_FILE);
		byte[] salt = java.nio.file.Files.readAllBytes(saltFile);

		// A malformed salt (truncated by a bad write, edited, …) must be an
		// error, not a reason to mint a new one — that would silently make
		// identity.enc and the store undecryptable with the right passphrase.
		java.nio.file.Files.write(saltFile, new byte[] { 1, 2, 3 });
		assertThrows(IOException.class, () -> Vault.open(home, "pw".toCharArray()));
		assertEquals(3, java.nio.file.Files.readAllBytes(saltFile).length, "left untouched");

		// Restore it and the vault opens again with the same keys.
		java.nio.file.Files.write(saltFile, salt);
		assertEquals(seed, Vault.open(home, "pw".toCharArray()).seedHex());
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
		assertEquals(64, RT.getIn(etch, "key").toString().length(), "32-byte store key as hex");
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
	void storeKeyIsDerivedFromTheSeedSoRecoveryCanReopenIt() throws Exception {
		// Not a JUnit @TempDir: this is the one test that keeps a real venue.etch,
		// and Etch maps it into memory. On Java 21 a mapping is released only by
		// GC, so Windows can refuse the deletion JUnit insists on at the end of
		// the class ("Failed to close extension context"). A directory under
		// target/ is deleted here on a best-effort basis and by the next clean.
		Path home = Files.createTempDirectory(Files.createDirectories(Path.of("target", "vault-test")), "home-");
		try {
			storeKeyRoundTrip(home);
		} finally {
			deleteQuietly(home);
		}
	}

	private static void storeKeyRoundTrip(Path home) throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		String seed = Mnemonic.toSeedHex(Mnemonic.generate(12));
		AMap<AString, ACell> base = AppConfig.defaultVenue(home).assoc(Fields.PORT, CVMLong.create(port));

		// Launch under passphrase A (with this seed), write a marker, close.
		AMap<AString, ACell> a = Vault.open(home, "passphrase-A".toCharArray()).secure(base, seed);
		EmbeddedVenue venue = EmbeddedVenue.launch(a);
		try {
			write(venue.clientAs(venue.did()), "w/vault-marker", "hi there");
		} finally {
			venue.close();
		}

		// Recovery validates the candidate seed against the authenticated store
		// header before replacing identity.enc or deleting provider credentials.
		Vault recovery = Vault.open(home, "new-passphrase".toCharArray());
		recovery.verifyStoreAccess(base, seed);
		String otherSeed = Mnemonic.toSeedHex(Mnemonic.generate(12));
		assertThrows(IOException.class, () -> recovery.verifyStoreAccess(base, otherSeed));

		// A DIFFERENT passphrase but the SAME seed reopens the store and reads the
		// marker — the store key comes from the seed, so recovery (which reaches the
		// seed via the recovery phrase) restores full access, not just the identity.
		AMap<AString, ACell> different = Vault.open(home, "totally-different".toCharArray()).secure(base, seed);
		EmbeddedVenue again = EmbeddedVenue.launch(different);
		try {
			assertEquals("hi there", str(read(again.clientAs(again.did()), "w/vault-marker")),
				"the same seed reopens the store regardless of passphrase");
		} finally {
			again.close();
		}

		// A DIFFERENT seed cannot open it.
		AMap<AString, ACell> wrong = Vault.open(home, "passphrase-A".toCharArray()).secure(base, otherSeed);
		assertThrows(Exception.class, () -> EmbeddedVenue.launch(wrong).close(),
			"a different seed cannot open the encrypted store");
	}

	/** Deletes a tree if the platform lets it; a store still mapped on Windows is left for the next clean. */
	private static void deleteQuietly(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// still mapped: target/ is cleared by mvn clean
				}
			});
		} catch (IOException ignored) {
			// nothing to delete
		}
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
