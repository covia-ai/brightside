package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.Maps;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

class FileRootsTest {

	@TempDir Path home;

	@Test
	void changesSurviveReloadWithoutRewritingConfigOrDeletingFiles() throws Exception {
		Path configFile = home.resolve("config.json");
		String source = "{ // keep my comments\n venue: {adapters: {example: {enabled: false}}} }";
		Files.writeString(configFile, source);
		AppConfig config = AppConfig.load(configFile);
		FileRoots roots = new FileRoots(home, config.venueConfig());
		Path folder = Files.createDirectory(home.resolve("documents"));
		Path document = Files.writeString(folder.resolve("keep.txt"), "keep this");
		roots.add("documents", folder, true);
		assertTrue(roots.list().stream().anyMatch(r -> r.name().equals("documents") && r.readOnly()));
		assertNull(RT.getIn(config.venueConfig(), "file", "roots", "documents"),
			"the running configuration stays unchanged until restart");
		AppConfig reloaded = AppConfig.load(configFile);
		assertEquals(folder.toRealPath().toString(),
			RT.getIn(reloaded.venueConfig(), "file", "roots", "documents", "path").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(reloaded.venueConfig(), "file", "roots", "documents", "readOnly"));
		assertEquals(CVMBool.FALSE, RT.getIn(reloaded.venueConfig(), "adapters", "example", "enabled"));
		FileRoots reopened = new FileRoots(home, reloaded.venueConfig());
		reopened.setReadOnly("documents", false);
		assertEquals(CVMBool.FALSE,
			RT.getIn(AppConfig.load(configFile).venueConfig(), "file", "roots", "documents", "readOnly"));
		reopened.remove("documents");
		reopened.remove("files");
		AppConfig removed = AppConfig.load(configFile);
		assertNull(RT.getIn(removed.venueConfig(), "file", "roots", "documents"));
		assertNull(RT.getIn(removed.venueConfig(), "file", "roots", "files"), "removed defaults stay removed");
		assertNotNull(RT.getIn(removed.venueConfig(), "file", "roots", "logs"));
		assertEquals("keep this", Files.readString(document));
		assertEquals(source, Files.readString(configFile));
	}

	@Test
	void rejectsInvalidFoldersAndDuplicateNamesWithoutOverwritingSettings() throws Exception {
		FileRoots roots = new FileRoots(home, Maps.empty());
		Path folder = Files.createDirectory(home.resolve("source"));
		roots.add("source", folder, true);
		String before = Files.readString(home.resolve(FileRoots.FILE));
		assertThrows(IllegalArgumentException.class, () -> roots.add("source", folder, false));
		assertThrows(IllegalArgumentException.class, () -> roots.add("../outside", folder, true));
		assertThrows(Exception.class, () -> roots.add("absent", home.resolve("missing"), true));
		assertThrows(IllegalArgumentException.class,
			() -> roots.add("file", home.resolve(FileRoots.FILE), true));
		assertThrows(IllegalArgumentException.class, () -> roots.setReadOnly("unknown", false));
		assertEquals(before, Files.readString(home.resolve(FileRoots.FILE)));
	}

	@Test
	void suggestedNamesAreOnesARootCanHave() {
		for (String folder : new String[] {"My Documents", "Q3 report!", "__drafts", "日本語", ".config"}) {
			String name = FileRoots.suggestName(Path.of("C:/x", folder));
			assertNull(FileRoots.nameProblem(name), folder + " → " + name);
		}
		assertEquals("my-documents", FileRoots.suggestName(Path.of("My Documents")));
		assertNotNull(FileRoots.nameProblem("-leading"));
	}

	@Test
	void editsPreserveOtherRootOptionsAndMalformedOverridesAreNotIgnored() throws Exception {
		AMap<AString, ACell> base = Maps.of("file", Maps.of("roots", Maps.of("drive", Maps.of(
			"dlfs", "archive", "subpath", "reports", "description", "Existing alias"))));
		FileRoots roots = new FileRoots(home, base);
		roots.setReadOnly("drive", true);
		var applied = FileRoots.apply(base, home);
		assertEquals("reports", RT.getIn(applied, "file", "roots", "drive", "subpath").toString());
		assertEquals("archive", RT.getIn(applied, "file", "roots", "drive", "dlfs").toString());
		Files.writeString(home.resolve(FileRoots.FILE), "[]");
		assertThrows(IllegalArgumentException.class, () -> FileRoots.apply(base, home),
			"a corrupt restriction file must not silently restore broader defaults");
	}
}
