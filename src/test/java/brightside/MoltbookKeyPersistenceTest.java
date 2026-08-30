package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Venue;
import covia.venue.RequestContext;
import brightside.vault.Vault;

/**
 * The Moltbook key outlives the vault. Brightside keeps it in the owner's
 * secret store inside the venue store, which is keyed from the identity seed
 * — so a forgotten-passphrase recovery (a new passphrase, {@code keys.enc}
 * deleted) reopens the same store and still finds the key and the claim
 * record, by the same resolution the Moltbook operations and the Settings page
 * use. Two real launches of the encrypted store, nothing mocked.
 */
class MoltbookKeyPersistenceTest {

	/** Any 32 bytes are an Ed25519 seed; this one stands in for the owner's. */
	private static final String SEED = "7f".repeat(32);

	@TempDir
	static Path home;

	@Test
	void theKeyAndRecordSurviveAForgottenPassphrase() throws Exception {
		String userDID;
		try (Launched first = launch("the first passphrase")) {
			userDID = Identity.of("owner").userDID(first.venue().did());
			Venue user = first.venue().clientAs(userDID);
			Moltbook.storeKey(user, "moltbook_persisted");
			Moltbook.saveRecord(user, "Molty", "https://www.moltbook.com/claim/molty", "code-1");
		}

		// Recovery: keys.enc goes, the seed is re-encrypted under a new passphrase,
		// and the venue is launched from the same seed and store.
		Files.deleteIfExists(home.resolve(Vault.KEYS_FILE));
		try (Launched second = launch("a different passphrase")) {
			assertEquals(userDID, Identity.of("owner").userDID(second.venue().did()),
				"the same seed gives the same venue and user");
			String key = second.venue().engine().resolveSecret(MoltbookAdapter.SECRET_REF,
				RequestContext.of(Strings.create(userDID)));
			assertEquals("moltbook_persisted", key, "the key is still in the owner's store");
			ACell record = second.venue().resolve(userDID, Moltbook.RECORD_PATH);
			assertNotNull(record, "the claim record is still there");
			assertEquals(Strings.create("Molty"), RT.getIn(record, "name"));
		}
	}

	private record Launched(EmbeddedVenue venue) implements AutoCloseable {
		@Override
		public void close() {
			venue.close();
		}
	}

	/** Launches the venue the way Brightside does: the store keyed from the seed, the vault from the passphrase. */
	private static Launched launch(String passphrase) throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		Vault vault = Vault.open(home, passphrase.toCharArray());
		AMap<AString, ACell> config = vault.secure(AppConfig.defaultVenue(home), SEED)
			.assoc(Fields.PORT, CVMLong.create(port));
		return new Launched(EmbeddedVenue.launch(config));
	}
}
