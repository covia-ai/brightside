package brightside;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import covia.venue.Config;
import covia.venue.VenueState;

/**
 * Bringing a store an older Brightside wrote up to this build's format.
 *
 * <p>Covia does the upgrade itself, in one transaction, as the venue starts
 * ({@code venue/docs/MIGRATE.md}); Brightside, as the embedder, decides what
 * happens around it. Before the venue opens the store it reads the versions
 * the store holds, with Covia's own check and nothing written. A store at
 * this build's version starts as usual. An older one is copied beside itself
 * first, as it was, because no older build can read it once upgraded: the
 * copy is the way back, and the owner removes it in Settings with the
 * originals a repair keeps ({@link StoreSpace}). A newer one is refused,
 * unchanged.</p>
 */
public final class StoreUpgrade {

	private static final Logger log = LoggerFactory.getLogger(StoreUpgrade.class);

	/** What the kept copy's name adds to the store's, before its version and when. */
	static final String KEPT = ".before-upgrade-";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private StoreUpgrade() {
	}

	/** A store written by a newer Brightside than this one. Nothing has been changed. */
	public static final class Newer extends IOException {
		private static final long serialVersionUID = 1L;

		Newer(long stored, long supported) {
			super("This data store was written by a newer Brightside (format " + stored
				+ "; this one reads up to " + supported + "). Nothing was changed: start the newer Brightside"
				+ " to open it.");
		}
	}

	/** An upgrade about to happen: the version the store holds, the one it goes to, and the copy kept of it. */
	public record Kept(long from, long to, Path copy) {

		/** What to tell the owner, in their words. */
		public String notice() {
			return "Brightside upgraded its data store to this version's format. A copy of it as it was is kept as "
				+ copy.getFileName() + ", for going back to an older Brightside; remove it in Settings once you are"
				+ " happy with this one.";
		}
	}

	/**
	 * Gets the store {@code config} names ready for the venue to open: nothing
	 * for a new store or one already current; a copy kept for an older one; a
	 * refusal for a newer one. The store is opened only to read, and closed.
	 *
	 * @return the copy kept, or empty when nothing will be upgraded
	 * @throws Newer when the store is newer than this build
	 */
	public static Optional<Kept> prepare(Config config) throws IOException {
		if (!config.isPersistentFileStore() || config.getSeed() == null) return Optional.empty();
		Path store = Path.of(config.getStore()).toAbsolutePath();
		if (!Files.exists(store)) return Optional.empty();
		long stored = storedVersion(config, store);
		if (stored < 0 || stored == VenueState.CURRENT_VERSION) return Optional.empty();
		if (stored > VenueState.CURRENT_VERSION) throw new Newer(stored, VenueState.CURRENT_VERSION);
		Path copy = store.resolveSibling(store.getFileName() + KEPT + "v" + stored + "-" + LocalDateTime.now().format(STAMP));
		Files.copy(store, copy, StandardCopyOption.COPY_ATTRIBUTES);
		Kept kept = new Kept(stored, VenueState.CURRENT_VERSION, copy);
		log.info("Store at format {}; upgrading to {} as the venue starts, with the original kept as {}",
			stored, kept.to(), copy.getFileName());
		return Optional.of(kept);
	}

	/** The venue version the store holds for this venue's key, or -1 when it holds nothing for it. */
	static long storedVersion(Config config, Path store) throws IOException {
		AccountKey owner = AKeyPair.create(Blob.fromHex(config.getSeed())).getAccountKey();
		EtchConfig etch = config.getEtchConfig();
		File file = store.toFile();
		try (EtchStore opened = (etch != null) ? EtchStore.create(file, etch) : EtchStore.create(file)) {
			return VenueState.peekVersions(opened, owner).map(VenueState.StoredVersions::venue).orElse(-1L);
		}
	}
}
