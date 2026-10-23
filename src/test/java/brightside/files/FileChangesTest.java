package brightside.files;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.files.DirectoryCache.Location;
import brightside.files.FileChanges.Failure;
import brightside.files.FileChanges.Item;
import brightside.files.FileChanges.Outcome;
import brightside.files.FileSource.Entry;
import brightside.files.FileSource.Listing;
import brightside.files.FileSource.Root;

class FileChangesTest {

	private static final Root HOME = new Root("dlfs:personal", "personal", "Personal drive");
	private static final Root WORK = new Root("file:work", "work", "Folder");

	private final Storage storage = new Storage();
	private final FileChanges changes = new FileChanges(storage, Runnable::run);

	@Test void movesStayInOnePlaceAndNeverIntoThemselves() {
		storage.folder(HOME, "Notes").folder(HOME, "Notes/Old").file(HOME, "a.txt", "a").file(HOME, "Notes/a.txt", "older");
		Location notes = at(HOME, "Notes");
		Outcome o = changes.move(List.of(file(HOME, "a.txt"), folder(HOME, "Notes"), folder(HOME, "Notes/Old")), notes).join();
		assertEquals(List.of(), o.done());
		assertEquals(List.of(
			new Failure("a.txt", "There is already an item with this name there."),
			new Failure("Notes", "A folder can't go inside itself."),
			new Failure("Old", "It is already here.")), o.failures());
		assertFalse(FileChanges.canMove(file(HOME, "a.txt"), at(WORK, "")), "a move never leaves its root");

		storage.file(HOME, "b.txt", "b");
		o = changes.move(List.of(folder(HOME, "Notes/Old"), file(HOME, "b.txt")), notes).join();
		assertEquals(List.of("b.txt"), o.done(), "a batch carries on past an item that can't move");
		assertEquals(List.of(new Failure("Old", "It is already here.")), o.failures());
		assertEquals("b", storage.text(HOME, "Notes/b.txt"));
		assertEquals(Set.of(at(HOME, ""), notes), o.changed());
		assertEquals(Set.of(), o.gone());

		o = changes.move(List.of(folder(HOME, "Notes/Old")), at(HOME, "")).join();
		assertEquals(Set.of(at(HOME, "Notes/Old")), o.gone(), "a moved folder is no longer where it was");
		assertTrue(storage.folders.contains(key(HOME, "Old")));
	}

	@Test void copiesTakeFreeNamesAndCarryFoldersWhole() {
		storage.folder(HOME, "Notes").folder(HOME, "Notes/Inner").file(HOME, "Notes/Inner/deep.txt", "deep")
			.file(HOME, "report.pdf", "pdf").folder(WORK, "");
		Outcome o = changes.copy(List.of(file(HOME, "report.pdf"), folder(HOME, "Notes")), at(HOME, "")).join();
		assertEquals(List.of("report (2).pdf", "Notes (2)"), o.done());
		assertEquals("deep", storage.text(HOME, "Notes (2)/Inner/deep.txt"));
		o = changes.copy(List.of(folder(HOME, "Notes")), at(WORK, "")).join();
		assertEquals(List.of("Notes"), o.done(), "a copy can go to another place");
		assertEquals("deep", storage.text(WORK, "Notes/Inner/deep.txt"));
		assertEquals("deep", storage.text(HOME, "Notes/Inner/deep.txt"), "the original stays");
		o = changes.copy(List.of(folder(HOME, "Notes")), at(HOME, "Notes/Inner")).join();
		assertEquals(List.of(new Failure("Notes", "A folder can't be copied inside itself.")), o.failures());
	}

	@Test void filesFromThisComputerComeInWithTheirFolders(@TempDir Path desktop) throws IOException {
		Files.createDirectories(desktop.resolve("Trip/Photos"));
		Files.writeString(desktop.resolve("Trip/Photos/beach.jpg"), "sand");
		Files.writeString(desktop.resolve("plan.txt"), "go");
		storage.folder(HOME, "").file(HOME, "plan.txt", "taken");
		Outcome o = changes.bringIn(List.of(desktop.resolve("Trip"), desktop.resolve("plan.txt")), at(HOME, "")).join();
		assertTrue(o.failures().isEmpty(), o.failures().toString());
		assertEquals(List.of("Trip", "plan (2).txt"), o.done());
		assertEquals("sand", storage.text(HOME, "Trip/Photos/beach.jpg"));
		assertEquals("go", storage.text(HOME, "plan (2).txt"));
		assertEquals("taken", storage.text(HOME, "plan.txt"), "nothing is replaced");
		assertTrue(Files.exists(desktop.resolve("plan.txt")), "the originals stay");
	}

	@Test void renamesAndNewFoldersCheckTheirNames() {
		storage.folder(HOME, "Notes").file(HOME, "a.txt", "a").file(HOME, "b.txt", "b");
		Location home = at(HOME, "");
		assertEquals(List.of(new Failure("a.txt", "A name can't contain / \\ : or !")),
			changes.rename(file(HOME, "a.txt"), "x/y").join().failures());
		assertEquals(List.of(new Failure("a.txt", "There is already an item with this name here.")),
			changes.rename(file(HOME, "a.txt"), "B.TXT").join().failures());
		assertEquals(List.of("A.txt"), changes.rename(file(HOME, "a.txt"), "A.txt").join().done(), "a change of case alone");
		Outcome o = changes.rename(folder(HOME, "Notes"), "Journal").join();
		assertEquals(Set.of(at(HOME, "Notes")), o.gone());
		assertEquals(List.of(new Failure("journal", "There is already an item with this name here.")),
			changes.createFolder(home, "journal").join().failures());
		assertEquals(List.of("Drafts"), changes.createFolder(home, "Drafts").join().done());
		assertTrue(storage.folders.contains(key(HOME, "Drafts")));
		assertEquals("New folder (3)", FileChanges.freeName("New folder", true, List.of("new folder", "New folder (2)", "a.txt")));
		assertEquals("v1.2 (2)", FileChanges.freeName("v1.2", true, List.of("v1.2")), "a folder's name has no extension");
		assertEquals(".env (2)", FileChanges.freeName(".env", false, List.of(".env")));
		assertNull(FileChanges.nameProblem("Q3 report.pdf"));
		for (String bad : new String[] {"", " ", " lead", "trail ", ".", "..", "a:b", "a!b", "a\\b", "tab\there"}) {
			assertNotNull(FileChanges.nameProblem(bad), bad);
		}
	}

	@Test void deletesReportEachItemAndFoldersGone() {
		storage.folder(HOME, "Notes").file(HOME, "Notes/x.txt", "x").file(HOME, "a.txt", "a");
		Outcome o = changes.delete(List.of(folder(HOME, "Notes"), file(HOME, "missing.txt"), file(HOME, "a.txt"))).join();
		assertEquals(List.of("Notes", "a.txt"), o.done());
		assertEquals(List.of(new Failure("missing.txt", "It is no longer there.")), o.failures());
		assertEquals(Set.of(at(HOME, "Notes")), o.gone());
		assertNull(storage.files.get(key(HOME, "Notes/x.txt")));
	}

	private static Location at(Root root, String path) { return new Location(root, path); }
	private static Item file(Root root, String path) { return new Item(at(root, path), false); }
	private static Item folder(Root root, String path) { return new Item(at(root, path), true); }
	private static String key(Root root, String path) { return root.id() + "|" + path; }

	/** Storage in memory: folders and files keyed by root and path. */
	private static final class Storage implements FileSource {
		final Set<String> folders = new java.util.TreeSet<>();
		final TreeMap<String, String> files = new TreeMap<>();

		Storage folder(Root root, String path) { folders.add(key(root, path)); return this; }
		Storage file(Root root, String path, String text) { files.put(key(root, path), text); return this; }
		String text(Root root, String path) { return files.get(key(root, path)); }

		private boolean exists(String key) { return folders.contains(key) || files.containsKey(key); }
		private void free(Root root, String path) throws IOException {
			if (exists(key(root, path))) throw new java.nio.file.FileAlreadyExistsException(path);
		}

		@Override public List<Root> roots() { return List.of(HOME, WORK); }
		@Override public Listing list(Root root, String path) throws IOException {
			String prefix = key(root, path.isEmpty() ? "" : path + "/");
			if (!path.isEmpty() && !folders.contains(key(root, path))) throw new NoSuchFileException(path);
			List<Entry> entries = new ArrayList<>();
			for (String f : folders) if (f.startsWith(prefix) && f.length() > prefix.length() && f.indexOf('/', prefix.length()) < 0) {
				entries.add(new Entry(f.substring(prefix.length()), "directory", 0, 0));
			}
			for (String f : files.keySet()) if (f.startsWith(prefix) && f.indexOf('/', prefix.length()) < 0) {
				entries.add(new Entry(f.substring(prefix.length()), "file", 1, 0));
			}
			return new Listing(entries, "");
		}
		@Override public InputStream open(Root root, String path) throws IOException {
			String text = text(root, path);
			if (text == null) throw new NoSuchFileException(path);
			return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
		}
		@Override public void createFolder(Root root, String path) throws IOException { free(root, path); folder(root, path); }
		@Override public void move(Root root, String from, String to) throws IOException {
			String source = key(root, from);
			if (!exists(source)) throw new NoSuchFileException(from);
			// A change of case alone is the same name.
			if (!source.equalsIgnoreCase(key(root, to))) free(root, to);
			String target = key(root, to);
			for (String f : List.copyOf(folders)) if (f.equals(source) || f.startsWith(source + "/")) {
				folders.remove(f);
				folders.add(target + f.substring(source.length()));
			}
			for (String f : List.copyOf(files.keySet())) if (f.equals(source) || f.startsWith(source + "/")) {
				files.put(target + f.substring(source.length()), files.remove(f));
			}
		}
		@Override public void copyFile(Root from, String path, Root to, String target) throws IOException {
			free(to, target);
			files.put(key(to, target), text(from, path));
		}
		@Override public void delete(Root root, String path) throws IOException {
			String target = key(root, path);
			if (!exists(target)) throw new NoSuchFileException(path);
			folders.removeIf(f -> f.equals(target) || f.startsWith(target + "/"));
			files.keySet().removeIf(f -> f.equals(target) || f.startsWith(target + "/"));
		}
		@Override public void add(Root root, String path, InputStream content) throws IOException {
			free(root, path);
			files.put(key(root, path), new String(content.readAllBytes(), StandardCharsets.UTF_8));
		}
	}
}
