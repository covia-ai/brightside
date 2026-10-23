package brightside;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.files.FileSource;
import brightside.files.VenueFiles;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.FileAdapter;
import covia.venue.RequestContext;

class VenueFilesTest {

	@RegisterExtension static final SharedVenue shared = new SharedVenue();

	@Test void localFoldersUseRunningRootsAndStayConfined() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("browser-local").userDID(venue.did());
		VenueFiles browser = new VenueFiles(venue, did);
		FileAdapter adapter = (FileAdapter) venue.engine().getAdapter("file");
		var directory = adapter.resolve(RequestContext.of(Strings.create(did)), "files", "", true);
		Files.createDirectory(directory.resolve("nested"));
		Files.writeString(directory.resolve("nested/note.txt"), "<html>literal file content</html>");
		Files.writeString(directory.resolve("z.txt"), "last");
		long jobs = RecordedJobs.of(venue, did);
		var root = browser.roots().stream().filter(r -> r.id().equals("file:files")).findFirst().orElseThrow();
		var listing = browser.list(root, "").entries();
		assertEquals("nested", listing.get(0).name());
		assertTrue(listing.get(0).directory());
		assertEquals("note.txt", browser.list(root, "nested").entries().get(0).name());
		assertEquals("<html>literal file content</html>", browser.preview(root, "nested/note.txt").text());
		assertThrows(IllegalArgumentException.class, () -> browser.list(root, "../"));
		assertThrows(IllegalArgumentException.class, () -> browser.preview(root, "nested/../../config.json"));
		assertThrows(IllegalArgumentException.class, () -> browser.preview(root, "archive.zip!/secret"));
		assertThrows(Exception.class, () -> browser.list(new FileSource.Root("file:missing", "", ""), ""));
		assertEquals(jobs, RecordedJobs.of(venue, did), "browser reads must create no durable jobs");
	}

	@Test void changesRunAsThePersonsOwnOperations() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String did = Identity.of("browser-changes").userDID(venue.did());
		new PersonalDrives(venue, did).initialise();
		VenueFiles files = new VenueFiles(venue, did);
		var roots = files.roots();
		var folder = roots.stream().filter(r -> r.id().equals("file:files")).findFirst().orElseThrow();
		var drive = roots.stream().filter(r -> r.id().equals("dlfs:personal")).findFirst().orElseThrow();
		assertFalse(folder.readOnly());
		assertTrue(Files.isDirectory(folder.local()), "a folder root says where it is on this computer");
		assertNull(drive.local());

		long jobs = RecordedJobs.of(venue, did);
		files.createFolder(drive, "Trip");
		files.add(drive, "Trip/plan.txt", new ByteArrayInputStream("go".getBytes(StandardCharsets.UTF_8)));
		assertThrows(Exception.class, () -> files.add(drive, "Trip/plan.txt", InputStream.nullInputStream()),
			"bringing a file in never replaces one");
		files.move(drive, "Trip/plan.txt", "plan.txt");
		assertEquals("go", files.preview(drive, "plan.txt").text());
		files.copyFile(drive, "plan.txt", folder, "changes-plan.txt");
		assertEquals("go", Files.readString(folder.local().resolve("changes-plan.txt")), "a copy can go to another place");
		files.delete(folder, "changes-plan.txt");
		files.add(folder, "changes-in.txt", new ByteArrayInputStream("in".getBytes(StandardCharsets.UTF_8)));
		assertEquals("in", files.preview(folder, "changes-in.txt").text());
		assertThrows(Exception.class, () -> files.add(folder, "changes-in.txt", InputStream.nullInputStream()));
		files.delete(folder, "changes-in.txt");
		assertFalse(Files.exists(folder.local().resolve("changes-in.txt")));
		files.delete(drive, "Trip");
		assertFalse(Files.exists(folder.local().resolve("changes-plan.txt")));
		assertTrue(files.list(drive, "").entries().stream().noneMatch(e -> e.name().equals("Trip")));
		assertTrue(RecordedJobs.of(venue, did) > jobs, "changes leave a record");

		String other = Identity.of("browser-changes-other").userDID(venue.did());
		new PersonalDrives(venue, other).initialise();
		new VenueFiles(venue, other).delete(drive, "plan.txt");
		assertEquals("go", files.preview(drive, "plan.txt").text(), "another person's changes reach only their own drive");
	}

	@Test void personalContentsAreBoundToTheCapturedUser() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String first = Identity.of("browser-first").userDID(venue.did());
		String second = Identity.of("browser-second").userDID(venue.did());
		new PersonalDrives(venue, first).initialise();
		new PersonalDrives(venue, second).initialise();
		venue.clientAs(first).run("v/ops/dlfs/write", Maps.of("drive", "personal", "path", "note.txt", "content", "private"))
			.get(5, TimeUnit.SECONDS);
		VenueFiles a = new VenueFiles(venue, first);
		VenueFiles b = new VenueFiles(venue, second);
		long jobs = RecordedJobs.of(venue, first);
		var root = a.roots().stream().filter(r -> r.id().equals("dlfs:personal")).findFirst().orElseThrow();
		assertEquals("note.txt", a.list(root, "").entries().get(0).name());
		assertEquals("private", a.preview(root, "note.txt").text());
		assertTrue(b.list(root, "").entries().isEmpty());
		assertThrows(Exception.class, () -> b.preview(root, "note.txt"));
		assertEquals("private", a.preview(root, "note.txt").text());
		assertEquals(jobs, RecordedJobs.of(venue, first));
	}
}
