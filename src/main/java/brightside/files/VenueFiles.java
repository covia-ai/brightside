package brightside.files;

import java.io.IOException;
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
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.adapter.FileAdapter;
import covia.venue.RequestContext;
import covia.venue.storage.ContentProvider;

/** Job-free reads through the running adapters, bound to one captured person. */
public final class VenueFiles implements FileSource {

	private final FileAdapter files;
	private final DLFSAdapter drives;
	private final RequestContext context;

	public VenueFiles(EmbeddedVenue venue, String did) {
		files = (FileAdapter) venue.engine().getAdapter("file");
		drives = (DLFSAdapter) venue.engine().getAdapter("dlfs");
		context = RequestContext.of(Strings.create(Objects.requireNonNull(did)));
	}

	@Override public List<Root> roots() throws Exception {
		List<Root> roots = new ArrayList<>();
		if (files != null) {
			for (ACell r : vector(file("roots", Maps.empty()), "roots")) {
				String name = text(r, "name");
				roots.add(new Root("file:" + name, name, "Folder"));
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

	@Override public Preview preview(Root root, String path) throws Exception {
		validate(path);
		String name = name(root);
		ContentProvider provider = root.id().startsWith("file:") ? files : drives;
		String reference = root.id().startsWith("file:") ? "file://" + name + "/" + path : "dlfs/" + name + "/" + path;
		var content = provider.getContent(Strings.create(reference), context);
		if (content == null) throw new IOException("File is unavailable.");
		try (var stream = content.content().getInputStream()) {
			if (stream == null) throw new IOException("File is unavailable.");
			return TextPreview.read(stream);
		}
	}

	private ACell file(String operation, AMap<AString, ACell> input) throws Exception {
		return files.invokeFuture(context, Maps.of("operation", Maps.of("adapter", "file:" + operation)), input)
			.get(10, TimeUnit.SECONDS);
	}

	private static String name(Root root) {
		if (!root.id().startsWith("file:") && !root.id().startsWith("dlfs:")) throw new IllegalArgumentException("Unknown storage.");
		String name = root.id().substring(root.id().indexOf(':') + 1);
		if (name.isBlank() || name.matches(".*[/\\\\:].*")) throw new IllegalArgumentException("Invalid storage name.");
		return name;
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
