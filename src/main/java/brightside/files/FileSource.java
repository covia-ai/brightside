package brightside.files;

import java.util.List;

/** Read-only browser boundary, independent of Swing, Covia and filesystem providers. */
public interface FileSource {

	record Root(String id, String name, String kind) {
		@Override public String toString() { return name + " · " + kind; }
	}
	record Entry(String name, String type, long size, long modified) {
		public boolean directory() { return "directory".equals(type); }
	}
	record Listing(List<Entry> entries, String warning) {}
	record Preview(String text, boolean truncated, boolean binary) {}

	List<Root> roots() throws Exception;
	Listing list(Root root, String path) throws Exception;
	Preview preview(Root root, String path) throws Exception;
}
