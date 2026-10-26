package brightside;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import covia.venue.Config;
import covia.venue.server.VenueServer;

/**
 * The disk the venue store takes, and getting it back.
 *
 * <p>Etch only appends: a value replaced or deleted stays in the file until
 * the store is collected, which copies what the lattice root still reaches
 * into a fresh file and drops the rest. Covia can collect a running venue,
 * but until that venue next closes cleanly the store spans two files, and a
 * crash in that time leaves files that only a repair by hand can open
 * ({@link StoreRepair#isInterruptedCollection}). The safe point is the start,
 * before the venue holds the store and while nothing writes to it. So
 * compaction is asked for, by the owner in Settings or by Odin, and the next
 * launch does it, once: it opens the store, has Covia collect it there, and
 * hands the venue the result. The request is a file beside the store, so it
 * is read before the store is opened.</p>
 *
 * <p>The originals a launch-time repair keeps beside the store
 * ({@link StoreRepair}) are the owner's to remove once the repaired store has
 * proved itself. This lists them, and removes them only when asked, never as
 * part of compacting.</p>
 */
public final class StoreSpace {

	private static final Logger log = LoggerFactory.getLogger(StoreSpace.class);

	/** What the request's name adds to the store's. */
	static final String REQUEST = ".compact";

	private final Path store;

	public StoreSpace(Path store) {
		this.store = store.toAbsolutePath();
	}

	/** The space of the persistent store {@code config} names, or null for a temp or memory store. */
	public static StoreSpace of(Config config) {
		return config.isPersistentFileStore() ? new StoreSpace(Path.of(config.getStore())) : null;
	}

	public Path store() {
		return store;
	}

	/** An original a repair kept: its file name, size, and when it last changed. */
	public record KeptOriginal(String name, long bytes, Instant modified) {
	}

	/** The store's size on disk, whether compaction is asked for, and the originals kept beside it. */
	public record Report(long storeBytes, boolean compactAtNextStart, List<KeptOriginal> keptOriginals) {

		public long keptBytes() {
			return keptOriginals.stream().mapToLong(KeptOriginal::bytes).sum();
		}
	}

	public Report report() throws IOException {
		return new Report(Files.exists(store) ? Files.size(store) : 0, compactAtNextStart(), keptOriginals());
	}

	public boolean compactAtNextStart() {
		return Files.exists(request());
	}

	/** Asks for compaction at the next start, or withdraws the request. */
	public void compactAtNextStart(boolean value) throws IOException {
		if (!value) {
			Files.deleteIfExists(request());
		} else if (!Files.exists(request())) {
			Files.writeString(request(), "Compact " + store.getFileName() + " the next time Brightside starts.\n");
		}
	}

	/**
	 * Whether this launch should compact, spending the request: before the
	 * launch, so a compaction cut short is not tried again at every start.
	 */
	public boolean takeRequest() throws IOException {
		return Files.deleteIfExists(request());
	}

	/**
	 * Opens the store as {@code config} describes it and has Covia collect it
	 * ({@link VenueServer#collectAtStartup}), for the venue to launch on. A
	 * collection that fails before it cuts over leaves the store as it was,
	 * which is then what the venue gets. The caller owns the returned store.
	 * The outcome is logged here, as soon as the store has changed, whatever
	 * becomes of the launch after it.
	 */
	public Compacted compact(Config config) throws IOException {
		EtchConfig etch = config.getEtchConfig();
		File file = store.toFile();
		EtchStore opened = (etch != null) ? EtchStore.create(file, etch) : EtchStore.create(file);
		long before = opened.getEtch().getDataLength();
		EtchStore running;
		try {
			running = VenueServer.collectAtStartup(opened, file, etch, config.isEtchGcRetainSuperseded());
		} catch (IOException | RuntimeException e) {
			opened.close();
			throw e;
		}
		Compacted compacted;
		if (running == opened) {
			compacted = new Compacted(running, before, before, false, false);
		} else {
			// Where the platform still pins the old file, Covia runs on the
			// compacted one under its own name and swaps them at the next start.
			boolean oldFileWaits = !running.getFile().toPath().toAbsolutePath().equals(store);
			compacted = new Compacted(running, before, running.getEtch().getDataLength(), true, oldFileWaits);
		}
		log.info("Store compaction at start: {}", compacted.summary());
		return compacted;
	}

	/**
	 * The store to launch on, and what compacting came to: what it held
	 * {@code before} and {@code after}, whether it was {@code collected} at all,
	 * and whether the old file waits for the next start to be removed.
	 */
	public record Compacted(EtchStore store, long before, long after, boolean collected, boolean oldFileWaits) {

		/** One line for the log. */
		public String summary() {
			if (!collected) return "not compacted; the venue runs on the store as it was";
			return before + " -> " + after + " bytes" + (oldFileWaits ? "; the old file goes at the next start" : "");
		}

		/** What to tell the owner, in their words. */
		public String notice() {
			if (!collected) return "Brightside tried to compact its data store, as asked, but couldn't. The log says why.";
			return "Brightside compacted its data store, as asked: it now holds " + Sizes.of(after)
				+ ", down from " + Sizes.of(before) + "."
				+ (oldFileWaits ? " The old file is removed the next time Brightside starts." : "");
		}
	}

	/** Removes the originals repairs kept, returning what went. Cannot be undone. */
	public List<KeptOriginal> removeKeptOriginals() throws IOException {
		List<KeptOriginal> kept = keptOriginals();
		for (KeptOriginal k : kept) Files.delete(store.resolveSibling(k.name()));
		return kept;
	}

	private List<KeptOriginal> keptOriginals() throws IOException {
		Path dir = store.getParent();
		if (dir == null || !Files.isDirectory(dir)) return List.of();
		String prefix = store.getFileName() + StoreRepair.KEPT;
		List<KeptOriginal> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				String name = f.getFileName().toString();
				if (name.startsWith(prefix) && Files.isRegularFile(f)) {
					out.add(new KeptOriginal(name, Files.size(f), Files.getLastModifiedTime(f).toInstant()));
				}
			}
		}
		out.sort(Comparator.comparing(KeptOriginal::name));
		return out;
	}

	private Path request() {
		return store.resolveSibling(store.getFileName() + REQUEST);
	}
}
