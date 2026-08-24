package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityTest {

	@TempDir
	Path home;

	@Test
	void sanitisesToADidSafeLabel() {
		assertEquals("mike", Identity.sanitise("Mike"));
		assertEquals("mike-anderson", Identity.sanitise("  Mike Anderson  "));
		// ':' collapses to '-', so no ':g:' agent separator can be smuggled into the DID
		assertEquals("mike-g-hack", Identity.sanitise("mike:g:hack"));
		assertFalse(Identity.sanitise("mike:g:hack").contains(":"));
		assertEquals("a-b", Identity.sanitise("a / b"));
		assertEquals("mike_1.0", Identity.sanitise("mike_1.0"));
		assertEquals("caf", Identity.sanitise("café")); // non-ASCII collapses to a sep, then trims
		assertEquals("", Identity.sanitise("   "));
		assertEquals("", Identity.sanitise("///"));
		assertEquals("", Identity.sanitise(null));
	}

	@Test
	void buildsTheUserDidAsAVenueSubPrincipal() {
		Identity id = Identity.of("Mike");
		assertEquals("mike", id.name());
		assertEquals("u:mike", id.label());
		assertEquals("did:key:z6Mkabc:u:mike", id.userDID("did:key:z6Mkabc"));
	}

	@Test
	void rejectsAnEmptyName() {
		assertThrows(IllegalArgumentException.class, () -> Identity.of("  "));
		assertThrows(IllegalArgumentException.class, () -> Identity.of("///"));
	}

	@Test
	void suggestionIsNeverEmpty() {
		assertTrue(Identity.suggestName().length() > 0);
		assertEquals(Identity.suggestName(), Identity.sanitise(Identity.suggestName()));
	}

	@Test
	void savesAndReloadsRoundTrip() throws IOException {
		assertNull(Identity.load(home), "no identity yet");
		Identity id = Identity.of("Mike Smith");
		id.save(home);
		Identity reloaded = Identity.load(home);
		assertNotNull(reloaded);
		assertEquals(id, reloaded);
		assertEquals("mike-smith", reloaded.name());
	}

	@Test
	void loadReturnsNullOnGarbage() throws IOException {
		java.nio.file.Files.writeString(home.resolve(Identity.FILE_NAME), "not json at all {");
		assertNull(Identity.load(home));
	}
}
