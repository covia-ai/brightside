package covia.brightside;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
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
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		if ("info".equals(op)) {
			return CompletableFuture.completedFuture(buildInfo());
		}
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("Unknown brightside operation: " + op));
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
			K_SKILLS, Vectors.of(
				Strings.create("introduction"),
				Strings.create("skills"),
				Strings.create("skill-authoring")));
	}
}
