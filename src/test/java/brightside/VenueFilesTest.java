package brightside;

import static org.junit.jupiter.api.Assertions.*;

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
