package brightside;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;

/**
 * The embedded venue a test class shares.
 *
 * <p>A venue is built to serve many principals at once, so a class that only
 * needs <em>a</em> venue takes this one and keeps to user principals of its own
 * — a {@code <venueDID>:u:<name>} nobody else uses, minted with
 * {@link Identity#of} exactly as the app mints the owner's — and what one test
 * can see of another is what one user can see of another: nothing. Test
 * classes run concurrently (see {@code junit-platform.properties}), so several
 * venues may be up at once, each on a temp store and an ephemeral port.</p>
 *
 * <p>How far the venue is shared is one switch: by default each test class
 * launches its own, closed with the class; with
 * {@code -Dbrightside.tests.sharedVenue=true} the whole run shares one, and
 * every class's principals work the same venue at the same time. The run-wide
 * mode is the intended one — it is how a venue is used for real, and it costs
 * one boot instead of one per class — but under that load the venue loses
 * writes (a chat cycle reports itself superseded, a reply never comes, a
 * record written a moment ago is gone: covia-ai/covia#521), so it stays off
 * until that is fixed. The per-class default is what the suite ran as before
 * classes ran concurrently.</p>
 *
 * <p>Use it as {@code @RegisterExtension static final SharedVenue shared = new
 * SharedVenue();} and read {@link #venue()} in {@code @BeforeAll}. A test of
 * the launch itself, of an encrypted store, or of taking a venue down launches
 * its own from {@link #config(Path)}.</p>
 */
public final class SharedVenue implements BeforeAllCallback, AfterAllCallback {

	/** One venue for the whole run, rather than one per class. */
	static final boolean ACROSS_RUN = Boolean.getBoolean("brightside.tests.sharedVenue");

	private static final Namespace NAMESPACE = Namespace.create(SharedVenue.class);

	private volatile Launched launched;

	@Override
	public void beforeAll(ExtensionContext context) {
		// The store's scope decides the venue's life: JUnit closes what the store
		// holds when its context ends — the run's, or this class's.
		ExtensionContext scope = ACROSS_RUN ? context.getRoot() : context;
		launched = scope.getStore(NAMESPACE).getOrComputeIfAbsent("venue", key -> new Launched(), Launched.class);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		launched = null;
	}

	/** The venue this class shares; valid from {@code @BeforeAll} to {@code @AfterAll}. */
	public EmbeddedVenue venue() {
		Launched l = launched;
		if (l == null) throw new IllegalStateException("No venue: register SharedVenue as a static @RegisterExtension");
		return l.venue;
	}

	/**
	 * A private test venue's config: Brightside's defaults on {@code home}, a
	 * temporary store, and port 0 so the server picks a free port itself (read
	 * it back from {@link EmbeddedVenue#port()}) — no race with other venues
	 * booting alongside.
	 */
	public static AMap<AString, ACell> config(Path home) {
		return AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(0));
	}

	/** A venue on a temp home; JUnit closes it with the store it was put in. */
	private static final class Launched implements AutoCloseable {

		private final Path home;
		private final EmbeddedVenue venue;

		Launched() {
			try {
				home = Files.createTempDirectory("brightside-test-venue");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			venue = EmbeddedVenue.launch(config(home));
		}

		@Override
		public void close() throws IOException {
			try {
				venue.close();
			} finally {
				try (var paths = Files.walk(home)) {
					for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
				}
			}
		}
	}
}
