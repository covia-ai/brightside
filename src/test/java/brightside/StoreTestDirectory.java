package brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Scratch space for persistent Etch tests. Close every store before closing
 * this directory. On Java 21, memory mappings can outlive store closure and
 * prevent Windows from deleting the files until GC or JVM exit. Any files
 * that cannot be deleted here stay under target/ for the next Maven clean.
 * Ordinary test files should still use JUnit's strict {@code @TempDir}.
 */
public final class StoreTestDirectory implements AutoCloseable {

	private final Path path;

	public StoreTestDirectory() throws IOException {
		Path parent = Files.createDirectories(Path.of("target", "test-stores"));
		path = Files.createTempDirectory(parent, "store-");
	}

	public Path path() {
		return path;
	}

	@Override
	public void close() throws IOException {
		try (var paths = Files.walk(path)) {
			for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
				try {
					Files.deleteIfExists(file);
				} catch (java.nio.file.FileSystemException ignored) {
					// Windows may still hold a mapping, keeping its parent non-empty.
				}
			}
		}
	}
}
