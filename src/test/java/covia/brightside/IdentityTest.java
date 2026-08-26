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
	void preservesDisplayCaseButLowercasesTheDid() {
		Identity id = Identity.of("Mike");
		assertEquals("Mike", id.name(), "display name keeps the case as typed");
		assertEquals("mike", id.slug());
		assertEquals("u:mike", id.label());
		assertEquals("did:key:z6Mkabc:u:mike", id.userDID("did:key:z6Mkabc"));
	}

	@Test
	void displayNameTrimsAndCollapsesWhitespace() {
		assertEquals("Mike Anderson", Identity.of("  Mike   Anderson  ").name());
		assertEquals("mike-anderson", Identity.of("  Mike   Anderson  ").slug());
	}

	@Test
	void rejectsAnEmptyName() {
		assertThrows(IllegalArgumentException.class, () -> Identity.of("  "));
		assertThrows(IllegalArgumentException.class, () -> Identity.of("///"));
	}

	@Test
	void suggestionIsUsableAsAName() {
		String suggestion = Identity.suggestName();
		assertTrue(suggestion.length() > 0, "suggestion is never empty");
		// It must yield a valid identity (a non-empty slug), even if the display
		// form differs from its slug (e.g. "mike_" → slug "mike").
		assertFalse(Identity.sanitise(suggestion).isEmpty());
		assertEquals(suggestion, Identity.of(suggestion).name());
	}

	@Test
	void savesAndReloadsRoundTrip() throws IOException {
		assertNull(Identity.load(home), "no identity yet");
		Identity id = Identity.of("Mike Smith");
		id.save(home);
		Identity reloaded = Identity.load(home);
		assertNotNull(reloaded);
		assertEquals(id, reloaded);
		assertEquals("Mike Smith", reloaded.name(), "display case survives a round trip");
		assertEquals("mike-smith", reloaded.slug());
	}

	@Test
	void renamingKeepsThePrincipal() throws IOException {
		Identity mike = Identity.of("Mike");
		Identity michael = mike.withName("Michael Anderson");
		assertEquals("Michael Anderson", michael.name(), "the display name changes");
		assertEquals("mike", michael.slug(), "the slug — and so the DID, agent and memory — does not");
		assertEquals(mike.userDID("did:x"), michael.userDID("did:x"));
		assertThrows(IllegalArgumentException.class, () -> mike.withName("   "));

		// The pinned slug survives a save/load round trip.
		michael.save(home);
		Identity reloaded = Identity.load(home);
		assertNotNull(reloaded);
		assertEquals("Michael Anderson", reloaded.name());
		assertEquals("mike", reloaded.slug());
	}

	@Test
	void loadsAnOlderNameOnlyFile() throws IOException {
		// identity.json written before the slug was saved: derive it from the name.
		java.nio.file.Files.writeString(home.resolve(Identity.FILE_NAME), "{\"name\": \"Mike Smith\"}");
		Identity id = Identity.load(home);
		assertNotNull(id);
		assertEquals("mike-smith", id.slug());
	}

	@Test
	void loadReturnsNullOnGarbage() throws IOException {
		java.nio.file.Files.writeString(home.resolve(Identity.FILE_NAME), "not json at all {");
		assertNull(Identity.load(home));
	}
}
