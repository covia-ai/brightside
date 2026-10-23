package brightside.files;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import brightside.files.FileSource.Listing;
import brightside.files.FileSource.Preview;
import brightside.files.FileSource.Root;

/** A browser binding's lazy reads. Tree expansion and selection share one directory read. */
public final class DirectoryCache {

	public record Location(Root root, String path) {
		public Location child(String name) { return new Location(root, path.isEmpty() ? name : path + "/" + name); }
		public Location parent() {
			int slash = path.lastIndexOf('/');
			return new Location(root, slash < 0 ? "" : path.substring(0, slash));
		}
		public String name() { return path.isEmpty() ? root.name() : path.substring(path.lastIndexOf('/') + 1); }
		/** Whether this is {@code folder} or somewhere inside it. */
		public boolean within(Location folder) {
			return root.equals(folder.root) && (folder.path.isEmpty() || path.equals(folder.path) || path.startsWith(folder.path + "/"));
		}
		/** Where this is on this computer, or null when its root lives elsewhere. */
		public Path local() {
			return (root.local() == null) ? null : path.isEmpty() ? root.local() : root.local().resolve(path);
		}
	}

	private static final Executor IO = Executors.newVirtualThreadPerTaskExecutor();
	private final FileSource source;
	private final Executor executor;
	private final Map<Location, CompletableFuture<Listing>> listings = new HashMap<>();

	public DirectoryCache(FileSource source) { this(source, IO); }

	DirectoryCache(FileSource source, Executor executor) {
		this.source = source;
		this.executor = executor;
	}

	public CompletableFuture<List<Root>> roots() { return read(source::roots); }
	public CompletableFuture<Preview> preview(Location file) { return read(() -> source.preview(file.root(), file.path())); }

	public synchronized CompletableFuture<Listing> list(Location location) {
		var existing = listings.get(location);
		if (existing != null && !existing.isCompletedExceptionally()) return existing;
		var pending = read(() -> source.list(location.root(), location.path()));
		listings.put(location, pending);
		return pending;
	}

	/** Old completions cannot repopulate a refreshed cache. */
	public synchronized void clear() { listings.clear(); }

	/** After a change: {@code folder} and everything inside it are read again when next asked for. */
	public synchronized void forget(Location folder) { listings.keySet().removeIf(l -> l.within(folder)); }

	private <T> CompletableFuture<T> read(Callable<T> task) {
		return CompletableFuture.supplyAsync(() -> {
			try { return task.call(); }
			catch (Exception e) { throw new CompletionException(e); }
		}, executor);
	}
}
