package brightside.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import brightside.files.DirectoryCache.Location;
import brightside.files.FileSource.Entry;

/**
 * The browser's changes to storage, each a batch run off the event thread. A
 * batch carries on past an item that fails, reports it by name, and says
 * which folders changed so the browser reads only those again. Nothing is
 * replaced: a copy or an item brought in takes the next free name, a move or a
 * rename onto a taken name fails.
 */
public final class FileChanges {

	/** What a change acts on: where it is, and whether it is a folder. */
	public record Item(Location location, boolean folder) {}

	public record Failure(String name, String reason) {}

	/**
	 * What a batch did: the names of the items it acted on — as they are now,
	 * for a copy or a move — what failed, the folders whose contents changed,
	 * and the folders no longer where they were.
	 */
	public record Outcome(List<String> done, List<Failure> failures, Set<Location> changed, Set<Location> gone) {}

	private static final Executor IO = Executors.newVirtualThreadPerTaskExecutor();
	private final FileSource source;
	private final Executor executor;

	public FileChanges(FileSource source) { this(source, IO); }

	FileChanges(FileSource source, Executor executor) {
		this.source = source;
		this.executor = executor;
	}

	public CompletableFuture<Outcome> createFolder(Location parent, String name) {
		return batch(b -> {
			if (b.refused(name, nameProblem(name))) return;
			Set<String> taken = b.names(parent, List.of(name));
			if (taken == null) return;
			if (taken.contains(key(name))) { b.fail(name, "There is already an item with this name here."); return; }
			if (b.attempt(name, () -> source.createFolder(parent.root(), parent.child(name).path()))) {
				b.done(name, parent);
			}
		});
	}

	public CompletableFuture<Outcome> rename(Item item, String name) {
		Location from = item.location();
		return batch(b -> {
			if (b.refused(from.name(), nameProblem(name))) return;
			if (name.equals(from.name())) return;
			Set<String> taken = b.names(from.parent(), List.of(from.name()));
			if (taken == null) return;
			// A change of case alone keeps the name it already holds.
			if (!key(name).equals(key(from.name())) && taken.contains(key(name))) {
				b.fail(from.name(), "There is already an item with this name here.");
				return;
			}
			if (b.attempt(from.name(), () -> source.move(from.root(), from.path(), from.parent().child(name).path()))) {
				b.done(name, from.parent());
				if (item.folder()) b.gone.add(from);
			}
		});
	}

	public CompletableFuture<Outcome> delete(List<Item> items) {
		return batch(b -> {
			for (Item item : items) {
				Location at = item.location();
				if (b.attempt(at.name(), () -> source.delete(at.root(), at.path()))) {
					b.done(at.name(), at.parent());
					if (item.folder()) b.gone.add(at);
				}
			}
		});
	}

	/** Into {@code folder}, within the items' own root. */
	public CompletableFuture<Outcome> move(List<Item> items, Location folder) {
		return batch(b -> {
			Set<String> taken = b.names(folder, names(items));
			if (taken == null) return;
			for (Item item : items) {
				Location from = item.location();
				String name = from.name();
				if (!canMove(item, folder)) {
					b.fail(name, from.parent().equals(folder) ? "It is already here."
						: from.root().equals(folder.root()) ? "A folder can't go inside itself."
						: "Items move only within one place.");
				} else if (taken.contains(key(name))) {
					b.fail(name, "There is already an item with this name there.");
				} else if (b.attempt(name, () -> source.move(from.root(), from.path(), folder.child(name).path()))) {
					taken.add(key(name));
					b.done(name, folder);
					b.changed.add(from.parent());
					if (item.folder()) b.gone.add(from);
				}
			}
		});
	}

	/** Into {@code folder}, which may be in another root; a folder is copied with everything in it. */
	public CompletableFuture<Outcome> copy(List<Item> items, Location folder) {
		return batch(b -> {
			Set<String> taken = b.names(folder, names(items));
			if (taken == null) return;
			for (Item item : items) {
				Location from = item.location();
				if (!canCopy(item, folder)) {
					b.fail(from.name(), "A folder can't be copied inside itself.");
					continue;
				}
				String name = free(from.name(), item.folder(), taken);
				taken.add(key(name));
				b.changed.add(folder);
				if (b.attempt(from.name(), () -> copy(item, folder.child(name)))) b.done(name, folder);
			}
		});
	}

	/** Files and folders from this computer into {@code folder}; the originals stay where they are. */
	public CompletableFuture<Outcome> bringIn(List<Path> files, Location folder) {
		return batch(b -> {
			List<String> names = files.stream().map(f -> String.valueOf(f.getFileName())).toList();
			Set<String> taken = b.names(folder, names);
			if (taken == null) return;
			for (Path file : files) {
				String original = String.valueOf(file.getFileName());
				if (b.refused(original, nameProblem(original))) continue;
				String name = free(original, Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS), taken);
				taken.add(key(name));
				b.changed.add(folder);
				if (b.attempt(original, () -> bringIn(file, folder.child(name)))) b.done(name, folder);
			}
		});
	}

	/** A copy of {@code file} on this computer at {@code destination}, replacing what is there. */
	public CompletableFuture<Outcome> saveCopy(Location file, Path destination) {
		return batch(b -> {
			if (b.attempt(file.name(), () -> {
				try (InputStream in = source.open(file.root(), file.path())) {
					Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			})) b.done.add(file.name());
		});
	}

	/** Why {@code name} can't name a file or folder, or null when it can. */
	public static String nameProblem(String name) {
		if (name == null || name.isBlank()) return "Enter a name.";
		if (!name.equals(name.strip())) return "A name can't start or end with a space.";
		if (name.equals(".") || name.equals("..")) return "That name is reserved.";
		if (name.length() > 255) return "That name is too long.";
		for (char c : name.toCharArray()) {
			if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '!') return "A name can't contain / \\ : or !";
		}
		return null;
	}

	/** Within its own root, and not into itself, below itself, or where it already is. */
	public static boolean canMove(Item item, Location folder) {
		Location at = item.location();
		return at.root().equals(folder.root()) && !at.path().isEmpty()
			&& !folder.within(at) && !at.parent().equals(folder);
	}

	/** Anywhere but inside itself. */
	public static boolean canCopy(Item item, Location folder) {
		return !item.location().path().isEmpty() && !folder.within(item.location());
	}

	/** {@code name} if none of {@code names} matches it ignoring case, else the first free "name (2)", "name (3)"… */
	public static String freeName(String name, boolean folder, Collection<String> names) {
		Set<String> taken = new HashSet<>();
		for (String n : names) taken.add(key(n));
		return free(name, folder, taken);
	}

	private static String free(String name, boolean folder, Set<String> taken) {
		if (!taken.contains(key(name))) return name;
		int dot = folder ? -1 : name.lastIndexOf('.');
		String stem = (dot > 0) ? name.substring(0, dot) : name;
		String extension = (dot > 0) ? name.substring(dot) : "";
		for (int n = 2;; n++) {
			String candidate = stem + " (" + n + ")" + extension;
			if (!taken.contains(key(candidate))) return candidate;
		}
	}

	private void copy(Item item, Location to) throws Exception {
		Location from = item.location();
		if (!item.folder()) {
			source.copyFile(from.root(), from.path(), to.root(), to.path());
			return;
		}
		source.createFolder(to.root(), to.path());
		for (Entry entry : source.list(from.root(), from.path()).entries()) {
			copy(new Item(from.child(entry.name()), entry.directory()), to.child(entry.name()));
		}
	}

	private void bringIn(Path file, Location to) throws Exception {
		if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
			source.createFolder(to.root(), to.path());
			List<Path> children;
			try (Stream<Path> listed = Files.list(file)) { children = listed.toList(); }
			for (Path child : children) {
				String name = String.valueOf(child.getFileName());
				String problem = nameProblem(name);
				if (problem != null) throw new IOException("“" + name + "” can't be stored: " + problem);
				bringIn(child, to.child(name));
			}
		} else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			try (InputStream in = Files.newInputStream(file)) {
				source.add(to.root(), to.path(), in);
			}
		} else {
			throw new IOException("Only files and folders can be added.");
		}
	}

	private CompletableFuture<Outcome> batch(Work work) {
		return CompletableFuture.supplyAsync(() -> {
			Batch b = new Batch();
			work.run(b);
			return new Outcome(List.copyOf(b.done), List.copyOf(b.failures), Set.copyOf(b.changed), Set.copyOf(b.gone));
		}, executor);
	}

	private static List<String> names(List<Item> items) {
		return items.stream().map(i -> i.location().name()).toList();
	}

	/** Names compare ignoring case, as on the platforms people use. */
	private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

	private static String reason(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		if (error instanceof FileAlreadyExistsException) return "There is already an item with this name there.";
		if (error instanceof NoSuchFileException) return "It is no longer there.";
		if (error instanceof AccessDeniedException) return "Access was denied.";
		return (error.getMessage() == null) ? error.getClass().getSimpleName() : error.getMessage();
	}

	private interface Work { void run(Batch batch); }
	private interface Step { void run() throws Exception; }

	private final class Batch {
		final List<String> done = new ArrayList<>();
		final List<Failure> failures = new ArrayList<>();
		final Set<Location> changed = new LinkedHashSet<>();
		final Set<Location> gone = new LinkedHashSet<>();

		boolean attempt(String name, Step step) {
			try {
				step.run();
				return true;
			} catch (Exception e) {
				fail(name, reason(e));
				return false;
			}
		}

		void fail(String name, String reason) { failures.add(new Failure(name, reason)); }

		boolean refused(String name, String problem) {
			if (problem != null) fail(name, problem);
			return problem != null;
		}

		void done(String name, Location folder) {
			done.add(name);
			changed.add(folder);
		}

		/** The names {@code folder} holds now, as keys; null, with each of {@code items} failed, when it can't be read. */
		Set<String> names(Location folder, List<String> items) {
			try {
				Set<String> names = new HashSet<>();
				for (Entry entry : source.list(folder.root(), folder.path()).entries()) names.add(key(entry.name()));
				return names;
			} catch (Exception e) {
				for (String item : items) fail(item, reason(e));
				return null;
			}
		}
	}
}
