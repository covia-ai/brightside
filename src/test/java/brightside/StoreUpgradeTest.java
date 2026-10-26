package brightside;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import brightside.vault.Vault;
import convex.core.data.prim.CVMLong;
import covia.lattice.Covia;
import covia.venue.Config;
import covia.venue.VenueState;

/**
 * Before the venue opens a store, Brightside reads its version with Covia's
 * check: an older store is copied, as it was, before Covia upgrades it; a
 * current one starts as it is; a newer one is refused and left unchanged.
 * Real stores throughout: the v0 install, upgraded by a real launch.
 */
class StoreUpgradeTest {

	@Test
	void anOlderStoreIsKeptBeforeItIsUpgradedAndANewerOneIsRefused() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path home = directory.path();
			try (ZipInputStream zip = new ZipInputStream(getClass().getResourceAsStream("/brightside/v0-install/venue.etch.zip"))) {
				zip.getNextEntry();
				Files.copy(zip, home.resolve("venue.etch"));
			}
			Path store = home.resolve("venue.etch");
			byte[] original = Files.readAllBytes(store);

			StoreUpgrade.Kept kept = StoreUpgrade.prepare(config(home)).orElseThrow();
			assertEquals(0, kept.from());
			assertEquals(VenueState.CURRENT_VERSION, kept.to());
			assertArrayEquals(original, Files.readAllBytes(kept.copy()), "the copy is the store as it was");
			assertArrayEquals(original, Files.readAllBytes(store), "checking writes nothing");
			assertEquals(List.of(kept.copy().getFileName().toString()), new StoreSpace(store).report().keptOriginals()
				.stream().map(StoreSpace.KeptOriginal::name).toList(), "the owner can see and remove the copy");

			// Covia upgrades it as the venue starts; then there is nothing more to keep.
			try (V0Install.Launched launched = V0Install.launch(home)) {
				assertEquals(VenueState.CURRENT_VERSION, launched.venue().engine().getVenueState().version());
			}
			assertTrue(StoreUpgrade.prepare(config(home)).isEmpty(), "a current store starts as it is");

			// A store from a newer build is refused, untouched.
			try (V0Install.Launched launched = V0Install.launch(home)) {
				launched.venue().engine().getVenueState().cursor().path(Covia.VERSION)
					.set(CVMLong.create(VenueState.CURRENT_VERSION + 1));
			}
			byte[] newer = Files.readAllBytes(store);
			StoreUpgrade.Newer refused = assertThrows(StoreUpgrade.Newer.class, () -> StoreUpgrade.prepare(config(home)));
			assertTrue(refused.getMessage().contains("newer Brightside"), refused.getMessage());
			assertArrayEquals(newer, Files.readAllBytes(store), "a refused store is left as it was");
			assertEquals(1, new StoreSpace(store).report().keptOriginals().size(), "and nothing more is kept");
		}
	}

	@Test
	void aNewInstallHasNothingToUpgrade() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			assertTrue(StoreUpgrade.prepare(config(directory.path())).isEmpty());
		}
	}

	private static Config config(Path home) throws Exception {
		return new Config(Vault.open(home, V0Install.PASSPHRASE.toCharArray())
			.secure(AppConfig.defaultVenue(home), V0Install.SEED));
	}
}
