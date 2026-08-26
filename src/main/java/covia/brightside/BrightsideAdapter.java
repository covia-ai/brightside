package covia.brightside;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * The Brightside venue adapter: Brightside-specific operations.
 *
 * <p>Registered on the embedded engine at launch ({@link EmbeddedVenue}). This
 * is the home for app-specific operations; the shipped skill library lives in
 * its own {@link BrightsideSkillsAdapter}.</p>
 *
 * <ul>
 *   <li>{@code brightside:info} — app and venue diagnostics.</li>
 *   <li>{@code brightside:shutdown} — clean process shutdown, venue-operator
 *       only, so a newly launched instance can take over (see
 *       {@link covia.brightside.Takeover}).</li>
 * </ul>
 *
 * <p>Add further Brightside operations by adding a {@code brightside/<op>.json}
 * resource and a case in {@link #invokeFuture}.</p>
 */
public class BrightsideAdapter extends AAdapter {

	private static final AString K_APP = Strings.intern("app");
	private static final AString K_VERSION = Strings.intern("version");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_DID = Strings.intern("did");
	private static final AString K_SKILLS = Strings.intern("skills");
	private static final AString K_ACCEPTED = Strings.intern("accepted");

	/** Runs to shut the app down cleanly (flush venue + exit); null off the desktop. */
	private final Runnable onShutdown;

	public BrightsideAdapter() {
		this(null);
	}

	public BrightsideAdapter(Runnable onShutdown) {
		this.onShutdown = onShutdown;
	}

	@Override
	public String getName() {
		return "brightside";
	}

	@Override
	public String getDescription() {
		return "Brightside desktop app operations. brightside:info reports app and "
			+ "venue diagnostics; further app-specific operations live here.";
	}

	@Override
	protected void installAssets() {
		installAsset("brightside/info", "/adapters/brightside/info.json");
		installAsset("brightside/shutdown", "/adapters/brightside/shutdown.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("info".equals(op)) {
			return CompletableFuture.completedFuture(buildInfo());
		}
		if ("shutdown".equals(op)) {
			return handleShutdown(ctx);
		}
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("Unknown brightside operation: " + op));
	}

	/**
	 * Clean process shutdown, restricted to the venue operator: the caller must
	 * authenticate as the venue's own DID (a venue-signed token — the same trust
	 * path {@code auth:whoami} reports as "internal"). Schedules the shutdown just
	 * after acknowledging, so the caller gets a clean response before we exit.
	 */
	private CompletableFuture<ACell> handleShutdown(RequestContext ctx) {
		AString caller = (ctx != null) ? ctx.getCallerDID() : null;
		AString venueDID = engine.getDIDString();
		if (caller == null || venueDID == null || !caller.equals(venueDID)) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("brightside:shutdown requires venue operator authority"));
		}
		if (onShutdown != null) {
			Thread t = new Thread(() -> {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				onShutdown.run();
			}, "brightside-shutdown");
			t.setDaemon(true);
			t.start();
		}
		return CompletableFuture.completedFuture(Maps.of(K_ACCEPTED, CVMBool.TRUE));
	}

	private AMap<AString, ACell> buildInfo() {
		String version = getClass().getPackage().getImplementationVersion();
		AString venueName = engine.getName();
		AString did = engine.getDIDString();
		return Maps.of(
			K_APP, BrightSide.APP_NAME,
			K_VERSION, (version != null) ? version : "dev",
			K_VENUE, (venueName != null) ? venueName.toString() : null,
			K_DID, (did != null) ? did.toString() : null,
			K_SKILLS, shippedSkills());
	}

	/** The shipped skill names, from the skills adapter's own list (one source of truth). */
	private static AVector<ACell> shippedSkills() {
		AVector<ACell> out = Vectors.empty();
		for (String path : BrightsideSkillsAdapter.SHIPPED) {
			out = out.conj(Strings.create(path.substring(path.lastIndexOf('/') + 1)));
		}
		return out;
	}
}
