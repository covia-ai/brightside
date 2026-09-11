package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;
import covia.venue.SecretStore;

/**
 * The reads and writes behind Settings → Secrets: the in-process accessors see
 * exactly what {@code secret:set} stores, an app-side write resolves for
 * operations as {@code s/<name>}, and deletion removes the entry.
 */
class SecretStoreAccessTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void theStoreTheSettingsPageReadsIsTheOneOperationsUse() throws Exception {
		String did = Identity.of("secretive").userDID(venue.did());
		Venue user = venue.clientAs(did);

		// Stored through the op (as an agent would store it) …
		Job job = user.invoke("v/ops/secret/set",
			Maps.of("name", "TEST_TOKEN", "value", "hush-op")).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);

		// … is listed and decryptable through the in-process accessors.
		SecretStore store = venue.secrets(did, false);
		assertNotNull(store, "the user's store reads in-process");
		assertTrue(store.exists("TEST_TOKEN"), "the op-stored secret is listed");
		assertEquals("hush-op", store.decrypt("TEST_TOKEN", venue.secretKey()).toString());

		// An app-side write resolves for operations as s/<name> — the same
		// resolution the Moltbook and Discord adapters use.
		venue.secrets(did, true).store("APP_SET", "hush-app", venue.secretKey());
		assertEquals("hush-app", venue.engine().resolveSecret("s/APP_SET",
			RequestContext.of(Strings.create(did))));

		// Deletion removes the entry.
		store.delete("TEST_TOKEN");
		assertFalse(store.exists("TEST_TOKEN"), "a deleted secret is gone");
	}
}
