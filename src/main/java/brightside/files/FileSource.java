package brightside.files;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The browser's boundary to storage, independent of Swing, Covia and
 * filesystem providers. Paths are relative to a root and separated by '/'.
 * Changes never replace an existing item: a destination must be free.
 */
public interface FileSource {

	/**
	 * A place files live. {@code local} is where it is on this computer, or
	 * null when it lives elsewhere — a personal drive.
	 */
	record Root(String id, String name, String kind, boolean readOnly, Path local) {
		public Root(String id, String name, String kind) { this(id, name, kind, false, null); }
		@Override public String toString() { return name + " · " + kind; }
	}
	record Entry(String name, String type, long size, long modified) {
		public boolean directory() { return "directory".equals(type); }
	}
	record Listing(List<Entry> entries, String warning) {}
	record Preview(String text, boolean truncated, boolean binary) {}

	List<Root> roots() throws Exception;
	Listing list(Root root, String path) throws Exception;
	/** The file's bytes, for the caller to close. */
	InputStream open(Root root, String path) throws Exception;

	default Preview preview(Root root, String path) throws Exception {
		try (InputStream stream = open(root, path)) {
			return TextPreview.read(stream);
		}
	}

	void createFolder(Root root, String path) throws Exception;
	/** Renames or moves a file or folder within one root. */
	void move(Root root, String from, String to) throws Exception;
	/** Copies one file, within a root or between roots. */
	void copyFile(Root from, String path, Root to, String target) throws Exception;
	/** Deletes a file, or a folder and everything in it. */
	void delete(Root root, String path) throws Exception;
	/** A new file holding {@code content}, brought in from outside storage. */
	void add(Root root, String path, InputStream content) throws Exception;
}
