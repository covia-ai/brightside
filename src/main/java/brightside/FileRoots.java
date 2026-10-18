package brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/** Settings-owned overrides for the venue's file roots; config.json remains hand-editable. */
public final class FileRoots {

	public static final String FILE = "file-roots.json";
	private static final Object LOCK = new Object();
	private final Path home;
	private final AMap<AString, ACell> configured;

	public record Root(String name, String location, boolean readOnly) {}

	public FileRoots(Path home, AMap<AString, ACell> venueConfig) {
		this.home = home;
		this.configured = rootsOf(venueConfig);
	}

	/** Overlay by root, not by field: a null suppresses even an app-supplied default. */
	public static AMap<AString, ACell> apply(AMap<AString, ACell> venueConfig, Path home) {
		try {
			AMap<AString, ACell> file = RT.ensureMap(RT.getIn(venueConfig, "file"));
			if (file == null) file = Maps.empty();
			return venueConfig.assoc(Strings.create("file"), file.assoc(Strings.create("roots"),
				overlay(rootsOf(venueConfig), read(home))));
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not read " + home.resolve(FILE), e);
		}
	}

	public List<Root> list() throws IOException {
		List<Root> result = new ArrayList<>();
		for (var entry : effective().entrySet()) {
			ACell value = entry.getValue();
			String location = value instanceof AString ? value.toString() : text(RT.getIn(value, "path"));
			if (location == null) {
				String drive = text(RT.getIn(value, "dlfs"));
				location = drive != null ? "Personal drive: " + drive : "Temporary folder";
			}
			result.add(new Root(entry.getKey().toString(), location, RT.bool(RT.getIn(value, "readOnly"))));
		}
		result.sort(Comparator.comparing(Root::name));
		return List.copyOf(result);
	}

	public void add(String name, Path directory, boolean readOnly) throws IOException {
		if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
			throw new IllegalArgumentException("Use 1–64 letters, digits, dots, dashes or underscores for the name.");
		}
		Path real = directory.toRealPath();
		if (!Files.isDirectory(real)) throw new IllegalArgumentException("Choose an existing folder.");
		synchronized (LOCK) {
			if (effective().containsKey(Strings.create(name))) throw new IllegalArgumentException("That name is already in use.");
			put(name, Maps.of("path", real.toString(), "readOnly", readOnly,
				"description", "Folder chosen by the owner: " + name));
		}
	}

	public void setReadOnly(String name, boolean readOnly) throws IOException {
		synchronized (LOCK) {
			ACell value = effective().get(Strings.create(name));
			if (value == null) throw new IllegalArgumentException("That folder is no longer configured.");
			AMap<AString, ACell> spec = value instanceof AString
				? Maps.of("path", value) : RT.ensureMap(value);
			put(name, spec.assoc(Strings.create("readOnly"), convex.core.data.prim.CVMBool.of(readOnly)));
		}
	}

	/** Removes access after restart; never deletes files from the host filesystem. */
	public void remove(String name) throws IOException {
		synchronized (LOCK) {
			if (!effective().containsKey(Strings.create(name))) throw new IllegalArgumentException("That folder is no longer configured.");
			put(name, null);
		}
	}

	private AMap<AString, ACell> effective() throws IOException {
		return overlay(configured, read(home));
	}

	private void put(String name, ACell value) throws IOException {
		AMap<AString, ACell> next = read(home).assoc(Strings.create(name), value);
		Files.createDirectories(home);
		Path temporary = Files.createTempFile(home, "file-roots-", ".tmp");
		try {
			Files.writeString(temporary, JSON.toStringPretty(next) + "\n");
			Files.move(temporary, home.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static AMap<AString, ACell> read(Path home) throws IOException {
		Path file = home.resolve(FILE);
		if (Files.notExists(file)) return Maps.empty();
		AMap<AString, ACell> result = RT.ensureMap(JSON.parseJSON5(Files.readString(file)));
		if (result == null) throw new IOException(FILE + " must contain an object.");
		for (var entry : result.entrySet()) {
			if (entry.getValue() != null && !(entry.getValue() instanceof AString)
					&& RT.ensureMap(entry.getValue()) == null) throw new IOException("Invalid folder: " + entry.getKey());
		}
		return result;
	}

	private static AMap<AString, ACell> rootsOf(AMap<AString, ACell> venueConfig) {
		AMap<AString, ACell> roots = RT.ensureMap(RT.getIn(venueConfig, "file", "roots"));
		return roots == null ? Maps.empty() : roots;
	}

	private static AMap<AString, ACell> overlay(AMap<AString, ACell> roots, AMap<AString, ACell> overrides) {
		for (var entry : overrides.entrySet()) {
			roots = entry.getValue() == null ? roots.dissoc(entry.getKey()) : roots.assoc(entry.getKey(), entry.getValue());
		}
		return roots;
	}

	private static String text(ACell value) {
		return value == null ? null : value.toString();
	}
}
