package brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.etch.EtchConfig;
import convex.etch.EtchRebuilder;
import covia.venue.Config;

/**
 * Puts the venue store right after an unclean close. Etch v3 records a clean
 * checkpoint in its header; a process that dies between a write and the next
 * sync or close — a crash, a power cut, an IDE launch stopped dead — leaves
 * the header open, and a normal open refuses the file. That refusal is by
 * design: an index that may be inconsistent must never be the base for
 * further writes, so Etch offers no writable recovery open. The way back is
 * a repair into a fresh file — an exclusive, read-only pass over the old one
 * that rediscovers every valid cell and proves the last synced root complete.
 *
 * <p>That is what {@link #repair} does at launch: it rebuilds into a fresh
 * file beside the store, moves the old file aside under a dated name and
 * installs the fresh one in its place, so nothing is destroyed and the owner
 * can go back. Only writes after the last sync can be missing, and the
 * outcome says whether the old file could be read through to its end.
 */
public final class StoreRepair {

	/** How Etch words its refusal; the only signal a caller gets. */
	static final String UNCLEAN = "not cleanly closed";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final int MOVE_ATTEMPTS = 10;

	/**
	 * What a repair did: the {@code store} now in place, the old file
	 * {@code kept} beside it, the rebuild's {@code status}, how many
	 * {@code values} the fresh store holds and any {@code problems} met.
	 */
	public record Outcome(Path store, Path kept, EtchRebuilder.Status status, long values, List<String> problems) {

		/** One line for the log. */
		public String summary() {
			return status + ": " + values + " values into " + store + "; the old file kept as " + kept.getFileName()
				+ (problems.isEmpty() ? "" : "; " + problems.size() + " problem(s) noted");
		}

		/** What to tell the owner, in their words. */
		public String notice() {
			String base = "Brightside wasn't closed cleanly last time, so it checked and rebuilt its data store "
				+ "before starting. The previous file is kept as " + kept.getFileName() + " in case anything is missing.";
			return (status == EtchRebuilder.Status.COMPLETE) ? base
				: base + " The check could not read the whole old file, so anything written after the last save may be missing.";
		}
	}

	private StoreRepair() {
	}

	/** Whether {@code t}, or a cause beneath it, is Etch refusing a store that was not cleanly closed. */
	public static boolean isUncleanClose(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c.getMessage() != null && c.getMessage().contains(UNCLEAN)) return true;
		}
		return false;
	}

	/**
	 * Repairs the persistent store {@code venueConfig} names (with its Etch
	 * policy and key) into a fresh file and installs it, keeping the old file
	 * beside it. Fails, leaving the old file untouched, when the last synced
	 * root cannot be proved complete — the rebuild is then a partial store
	 * left beside the original for a hand recovery.
	 */
	public static Outcome repair(AMap<AString, ACell> venueConfig) throws IOException {
		Config c = new Config(venueConfig);
		Path store = Path.of(c.getStore()).toAbsolutePath();
		EtchConfig etch = c.getEtchConfig();
		String stamp = STAMP.format(LocalDateTime.now());
		Path fresh = store.resolveSibling(store.getFileName() + ".repair-" + stamp);
		Path kept = store.resolveSibling(store.getFileName() + ".unclean-" + stamp);

		// The fresh file keeps the old one's policy — cipher, hint, mapping — and
		// its key, which the resolved policy carries when the old file's did.
		EtchRebuilder.Result result = EtchRebuilder.rebuildConfigured(store.toFile(), etch, fresh.toFile(),
			policy -> (policy.hasKeyFunction() || etch == null || !etch.hasKeyFunction())
				? policy : policy.withKeyFunction(etch.getKeyFunction()));
		if (!result.isRootComplete()) {
			throw new IOException("The store's last saved state is incomplete (" + result.missingRootHashes().size()
				+ " missing), so it was not replaced. The original is untouched at " + store
				+ "; the partial rebuild is at " + fresh);
		}
		move(store, kept);
		try {
			move(fresh, store);
		} catch (IOException e) {
			// Never leave no store at all: a launch would quietly create an empty
			// one. Put the old file back; the rebuild stays beside it.
			try {
				move(kept, store);
			} catch (IOException back) {
				e.addSuppressed(back);
				throw new IOException("Could not install the repaired store, and could not put the original back."
					+ " The original is at " + kept + " and the rebuild at " + fresh + "; rename one to " + store, e);
			}
			throw new IOException("Could not install the repaired store; the original is back in place at " + store
				+ " and the rebuild is at " + fresh, e);
		}
		return new Outcome(store, kept, result.status(), result.destinationValues(), result.problems());
	}

	/**
	 * A rename that outlasts a lingering mapping: on Windows the JDK's
	 * mapped-byte-buffer mapper keeps the file mapped until the buffer is
	 * collected, and a mapped file cannot be moved. So a refusal is met with a
	 * collection and another try, a few times, before giving up.
	 */
	private static void move(Path from, Path to) throws IOException {
		IOException last = null;
		for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
			try {
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (IOException e) {
				last = e;
				System.gc();
				try {
					Thread.sleep(200L * attempt);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
		throw new IOException("Could not move " + from.getFileName() + " to " + to.getFileName(), last);
	}
}
