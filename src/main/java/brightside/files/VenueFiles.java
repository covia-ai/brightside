package brightside.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import brightside.EmbeddedVenue;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.adapter.FileAdapter;
import covia.grid.Venue;
import covia.venue.RequestContext;
import covia.venue.storage.ContentProvider;

/**
 * Storage through the running adapters, bound to one captured person. Reads
 * are job-free; changes run as that person's file operations, so each leaves
 * a record. Bytes stream through the adapters' content API, which applies the
 * same access checks.
 */
public final class VenueFiles implements FileSource {

	private static final long ACTION_SECONDS = 120;
	private final FileAdapter files;
	private final DLFSAdapter drives;
	private final RequestContext context;
	private final Venue client;

	public VenueFiles(EmbeddedVenue venue, String did) {
		files = (FileAdapter) venue.engine().getAdapter("file");
		drives = (DLFSAdapter) venue.engine().getAdapter("dlfs");
		context = RequestContext.of(Strings.create(Objects.requireNonNull(did)));
		client = venue.clientAs(did);
	}

	@Override public List<Root> roots() throws Exception {
		List<Root> roots = new ArrayList<>();
		if (files != null) {
			for (ACell r : vector(file("roots", Maps.empty()), "roots")) {
				String name = text(r, "name");
				roots.add(new Root("file:" + name, name, "Folder", RT.bool(RT.getIn(r, "readOnly")), local(name)));
			}
		}
		if (drives != null) {
			for (ACell name : vector(drives.listDrives(context), "drives")) {
				roots.add(new Root("dlfs:" + name, name.toString(), "Personal drive"));
			}
		}
		roots.sort(Comparator.comparing(Root::kind).thenComparing(Root::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(roots);
	}

	@Override public Listing list(Root root, String path) throws Exception {
		validate(path);
		String name = name(root);
		ACell result = root.id().startsWith("file:")
			? file("list", Maps.of("root", name, "path", path))
			: drives.listDirectory(context, Maps.of("drive", name, "path", path));
		List<Entry> entries = new ArrayList<>();
		for (ACell entry : vector(result, "entries")) {
			entries.add(new Entry(text(entry, "name"), text(entry, "type"),
				RT.ensureLong(RT.getIn(entry, "size")).longValue(), RT.ensureLong(RT.getIn(entry, "modified")).longValue()));
		}
		entries.sort(Comparator.comparing(Entry::directory).reversed()
			.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
		ACell warnings = RT.getIn(result, "warnings");
		return new Listing(List.copyOf(entries), warnings == null ? "" : warnings.toString());
	}

	@Override public InputStream open(Root root, String path) throws Exception {
		var content = provider(root).getContent(reference(root, path), context);
		InputStream stream = (content == null) ? null : content.content().getInputStream();
		if (stream == null) throw new IOException("File is unavailable.");
		return stream;
	}

	@Override public void createFolder(Root root, String path) throws Exception {
		act("mkdir", at(root, path));
	}

	@Override public void move(Root root, String from, String to) throws Exception {
		act("move", Maps.of("from", reference(root, from), "to", reference(root, to)));
	}

	@Override public void copyFile(Root from, String path, Root to, String target) throws Exception {
		act("copy", Maps.of("from", reference(from, path), "to", reference(to, target)));
	}

	@Override public void delete(Root root, String path) throws Exception {
		act("delete", at(root, path).assoc(Strings.create("recursive"), CVMBool.TRUE));
	}

	@Override public void add(Root root, String path, InputStream content) throws Exception {
		if (!provider(root).createContent(reference(root, path), content, null, context)) {
			throw new IOException("This storage cannot take new files.");
		}
	}

	/** Where a folder root is on this computer; null for one backed by a drive, or not there. */
	private Path local(String name) {
		try {
			Path base = files.resolve(context, name, "", true);
			return (base.getFileSystem() == FileSystems.getDefault()) ? base : null;
		} catch (Exception e) {
			return null;
		}
	}

	private ACell file(String operation, AMap<AString, ACell> input) throws Exception {
		return files.invokeFuture(context, Maps.of("operation", Maps.of("adapter", "file:" + operation)), input)
			.get(10, TimeUnit.SECONDS);
	}

	/** A change, as the person's own file operation. */
	private void act(String operation, AMap<AString, ACell> input) throws Exception {
		client.run("v/ops/file/" + operation, input).get(ACTION_SECONDS, TimeUnit.SECONDS);
	}

	private ContentProvider provider(Root root) {
		return root.id().startsWith("file:") ? files : drives;
	}

	/** The adapters' reference to {@code path}: {@code file://<root>/<path>}, or {@code dlfs/<drive>/<path>}. */
	private static AString reference(Root root, String path) {
		validate(path);
		String name = name(root);
		return Strings.create(root.id().startsWith("file:") ? "file://" + name + "/" + path : "dlfs/" + name + "/" + path);
	}

	/** A single-path operation's target: a folder root by name, a drive by its reference. */
	private static AMap<AString, ACell> at(Root root, String path) {
		return root.id().startsWith("file:")
			? Maps.of("root", name(root), "path", validated(path))
			: Maps.of("path", reference(root, path));
	}

	private static String name(Root root) {
		if (!root.id().startsWith("file:") && !root.id().startsWith("dlfs:")) throw new IllegalArgumentException("Unknown storage.");
		String name = root.id().substring(root.id().indexOf(':') + 1);
		if (name.isBlank() || name.matches(".*[/\\\\:].*")) throw new IllegalArgumentException("Invalid storage name.");
		return name;
	}

	private static String validated(String path) {
		validate(path);
		return path;
	}

	private static void validate(String path) {
		// The browser handles ordinary files only. Archive entry expansion can materialise an entire file.
		if (path == null || path.startsWith("/") || path.contains("\\") || path.contains("!") || path.contains(":")) {
			throw new IllegalArgumentException("This path cannot be browsed.");
		}
		for (String part : path.split("/")) {
			if (part.equals("..") || part.equals(".")) throw new IllegalArgumentException("Stay inside the selected folder.");
		}
	}

	private static AVector<?> vector(ACell value, String key) throws IOException {
		if (RT.getIn(value, key) instanceof AVector<?> vector) return vector;
		throw new IOException("Storage returned an invalid listing.");
	}

	private static String text(ACell value, String key) { return Objects.toString(RT.getIn(value, key), ""); }
}
