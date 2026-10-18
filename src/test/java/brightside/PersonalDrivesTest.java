package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.Maps;
import covia.adapter.DLFSAdapter;

class PersonalDrivesTest {

	@RegisterExtension static final SharedVenue shared = new SharedVenue();

	@Test
	void defaultIsCreatedOnce() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("drive-default").userDID(venue.did());
		PersonalDrives drives = new PersonalDrives(venue, did);
		assertTrue(drives.list().isEmpty());
		drives.initialise();
		assertEquals(List.of(PersonalDrives.DEFAULT), drives.list());
		long jobs = RecordedJobs.of(venue, did);
		new PersonalDrives(venue, did).initialise();
		assertEquals(jobs, RecordedJobs.of(venue, did), "another bind needs no jobs or model calls");
	}

	@Test
	void initialisedUserWithNoDrivesStaysEmpty() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("drive-removed").userDID(venue.did());
		// A saved installation whose initial storage has since been removed.
		venue.clientAs(did).run("v/ops/covia/write", Maps.of("path", PersonalDrives.INITIALISED, "value", true))
			.get(5, TimeUnit.SECONDS);
		PersonalDrives drives = new PersonalDrives(venue, did);
		long jobs = RecordedJobs.of(venue, did);
		drives.initialise();
		assertTrue(drives.list().isEmpty());
		assertEquals(jobs, RecordedJobs.of(venue, did));
	}

	@Test
	void drivesAndTheirContentsBelongToTheUserNotTheAgent() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String owner = Identity.of("drive-owner").userDID(venue.did());
		String other = Identity.of("drive-other").userDID(venue.did());
		PersonalDrives first = new PersonalDrives(venue, owner);
		PersonalDrives second = new PersonalDrives(venue, other);
		first.initialise();
		second.initialise();
		venue.clientAs(owner).run("v/ops/dlfs/write", Maps.of("drive", PersonalDrives.DEFAULT,
			"path", "note.txt", "content", "private note")).get(5, TimeUnit.SECONDS);
		DLFSAdapter adapter = (DLFSAdapter) venue.engine().getAdapter("dlfs");
		assertEquals("private note", Files.readString(adapter.getDriveForIdentity(owner, PersonalDrives.DEFAULT)
			.getPath("/note.txt")));
		assertFalse(Files.exists(adapter.getDriveForIdentity(other, PersonalDrives.DEFAULT).getPath("/note.txt")));
		first.create("projects");
		assertEquals(List.of(PersonalDrives.DEFAULT, "projects"), first.list());
		assertEquals(List.of(PersonalDrives.DEFAULT), second.list());
	}

	@Test
	void existingDrivesAreKeptAndDuplicateCreationDoesNotResetThem() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("drive-existing").userDID(venue.did());
		PersonalDrives drives = new PersonalDrives(venue, did);
		drives.create("work");
		drives.initialise();
		assertEquals(List.of("work"), drives.list(), "existing users need no extra default drive");
		assertThrows(IllegalArgumentException.class, () -> drives.create("work"));
		assertThrows(IllegalArgumentException.class, () -> drives.create("../other"));
		assertEquals(List.of("work"), drives.list());
	}
}
