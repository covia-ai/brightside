package brightside.ui.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class FileKindTest {

	/** A kind whose icon isn't bundled would paint nothing; every one resolves. */
	@Test
	void everyKindsIconIsBundled() {
		for (FileKind k : FileKind.values()) {
			assertNotNull(FileKind.class.getClassLoader().getResource("icons/lucide/" + k.icon + ".svg"), k.icon);
		}
	}

	@Test
	void kindComesFromTheLastExtensionWhateverItsCase() {
		assertEquals(FileKind.CODE, FileKind.of("Main.JAVA", false));
		assertEquals(FileKind.ARCHIVE, FileKind.of("release.tar.gz", false));
		assertEquals(FileKind.IMAGE, FileKind.of("photo.jpeg", false));
	}

	@Test
	void foldersDotfilesAndTheUnknownAreNotGuessed() {
		assertEquals(FileKind.FOLDER, FileKind.of("notes.md", true));
		assertEquals(FileKind.OTHER, FileKind.of(".gitignore", false));
		assertEquals(FileKind.OTHER, FileKind.of("Makefile", false));
		assertEquals(FileKind.OTHER, FileKind.of("trailing.", false));
		assertEquals(FileKind.OTHER, FileKind.of("data.unknownext", false));
	}
}
