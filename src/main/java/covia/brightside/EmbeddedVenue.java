package covia.brightside;

import java.util.concurrent.atomic.AtomicBoolean;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import covia.venue.server.VenueServer;

/**
 * The Covia venue running inside the BrightSide process.
 *
 * <p>Wraps a {@link VenueServer} (the full venue: engine, adapters, HTTP/MCP
 * surface on the configured port) and a {@link LocalVenue} — the in-process
 * client the chat window uses, so a conversation never leaves the JVM. The
 * desktop user acts as the venue's own principal: agents and state live under
 * the venue DID.
 */
public final class EmbeddedVenue implements AutoCloseable {

	private final VenueServer server;
	private final LocalVenue venue;
	private final AtomicBoolean closed = new AtomicBoolean();

	private EmbeddedVenue(VenueServer server, LocalVenue venue) {
		this.server = server;
		this.venue = venue;
	}

	/**
	 * Launches the venue described by {@code config} — a single Covia venue
	 * config map, as produced by {@link AppConfig#venueConfig()} — and returns
	 * once it is serving.
	 */
	public static EmbeddedVenue launch(AMap<AString, ACell> config) {
		VenueServer server = VenueServer.launch(config);
		Engine engine = server.getEngine();
		LocalVenue local = LocalVenue.create(engine);
		local.setUser(engine.getDIDString());
		return new EmbeddedVenue(server, local);
	}

	public Engine engine() {
		return server.getEngine();
	}

	/** In-process client acting as the venue principal. */
	public LocalVenue venue() {
		return venue;
	}

	public int port() {
		return server.port();
	}

	/** Loopback URL of the venue's web interface. */
	public String url() {
		return "http://127.0.0.1:" + port() + "/";
	}

	public String did() {
		AString did = engine().getDIDString();
		return (did == null) ? null : did.toString();
	}

	public String name() {
		AString name = engine().getName();
		return (name == null || name.toString().isBlank()) ? "Covia Venue" : name.toString();
	}

	/** Flushes venue state and stops the server. Idempotent. */
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		server.close();
	}
}
