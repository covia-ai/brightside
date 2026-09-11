package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import brightside.vault.Vault;
import convex.core.data.Strings;
import covia.venue.RequestContext;

/**
 * Provider keys have one home: the encrypted secret stores. Anything staged in
 * the vault's {@code keys.enc} (onboarding, or an earlier build) is moved at
 * launch into both the user's and the operator's store — without overwriting a
 * value already set there — and {@code keys.enc} is removed, so a secret
 * deleted from the store never resurrects from a stale vault copy.
 */
class ProviderKeyMigrationTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	/** The vault's files; the venue's own home is elsewhere. */
	@TempDir
	static Path home;

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void vaultKeysMoveIntoBothStoresWithoutClobberingStoreEdits() throws Exception {
		Vault vault = Vault.open(home, "a passphrase".toCharArray());
		vault.storeApiKey("ANTHROPIC_API_KEY", "from-vault");
		vault.storeApiKey("OPENAI_API_KEY", "openai-vault");
		String did = Identity.of("keys").userDID(venue.did());
		// A value already in the user's store must win over the vault copy.
		venue.secrets(did, true).store("ANTHROPIC_API_KEY", "user-edited", venue.secretKey());

		BrightSide.migrateProviderKeys(venue, vault, did);

		assertEquals("user-edited",
			venue.secrets(did, false).decrypt("ANTHROPIC_API_KEY", venue.secretKey()).toString(),
			"a store edit is not overwritten by the vault copy");
		assertEquals("openai-vault",
			venue.secrets(did, false).decrypt("OPENAI_API_KEY", venue.secretKey()).toString(),
			"a vault key lands in the user's store");
		assertEquals("from-vault",
			venue.secrets(venue.did(), false).decrypt("ANTHROPIC_API_KEY", venue.secretKey()).toString(),
			"the operator's store gets the vault copy (Odin resolves it)");
		assertEquals("openai-vault", venue.engine().resolveSecret("s/OPENAI_API_KEY",
			RequestContext.of(Strings.create(did))),
			"agents resolve the migrated key as s/<name>");
		assertFalse(Files.isRegularFile(home.resolve(Vault.KEYS_FILE)),
			"keys.enc is removed once migrated — deleted store secrets never resurrect");

		// Running again (the next launch) is a no-op.
		BrightSide.migrateProviderKeys(venue, vault, did);
		assertEquals("user-edited",
			venue.secrets(did, false).decrypt("ANTHROPIC_API_KEY", venue.secretKey()).toString());
	}
}
