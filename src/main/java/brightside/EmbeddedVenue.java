package brightside;

import java.util.concurrent.atomic.AtomicBoolean;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.server.VenueServer;

/**
 * The Covia venue running inside the BrightSide process.
 *
 * <p>Wraps a {@link VenueServer} — the full venue: engine, adapters, HTTP/MCP
 * surface on the configured port. {@link #clientAs} mints an in-process
 * {@link LocalVenue} client bound to a chosen principal, so a conversation
 * runs entirely inside the JVM under the identity the user picked.
 */
public final class EmbeddedVenue implements AutoCloseable {

	private final VenueServer server;
	private final AtomicBoolean closed = new AtomicBoolean();

	private EmbeddedVenue(VenueServer server) {
		this.server = server;
	}

	/**
	 * Launches the venue described by {@code config} — a single Covia venue
	 * config map, as produced by {@link AppConfig#venueConfig()} — and returns
	 * once it is serving.
	 */
	public static EmbeddedVenue launch(AMap<AString, ACell> config) {
		return launch(config, null);
	}

	/**
	 * As {@link #launch(AMap)}, but wires {@code onShutdown} into the
	 * {@code brightside:shutdown} operation so an operator (or a newly launched
	 * instance taking over) can stop this process cleanly over the venue's own
	 * HTTP surface. Pass null off the desktop (e.g. tests).
	 */
	public static EmbeddedVenue launch(AMap<AString, ACell> config, Runnable onShutdown) {
		VenueServer server = VenueServer.launch(config);
		// Brightside's venue adapters: operations, and the default skill library.
		Engine engine = server.getEngine();
		engine.registerAdapter(new BrightsideAdapter(onShutdown));
		engine.registerAdapter(new BrightsideSkillsAdapter());
		// Moltbook as typed operations; idle until the owner connects an account.
		engine.registerAdapter(new MoltbookAdapter());
		// Covia's Discord module, in-process rather than as a loaded module jar:
		// the same adapter, on Brightside's own classpath. With no bots it costs
		// nothing; the owner's bot is created from Settings (see Discord).
		engine.registerAdapter(new covia.adapter.discord.DiscordAdapter());
		return new EmbeddedVenue(server);
	}

	public Engine engine() {
		return server.getEngine();
	}

	/**
	 * Reads an agent record ({@code g/<agentId>}) straight from the in-process
	 * lattice as {@code userDID}, with no job — the same
	 * {@link Engine#resolvePath} that {@code v/ops/covia/read} calls internally.
	 * Used for cheap, silent change detection and projection (the venue is right
	 * here in the process; there is no reason to submit a read job for it).
	 * Returns null if absent or on any resolution error.
	 */
	public ACell agentRecord(String userDID, String agentId) {
		return resolve(userDID, "g/" + agentId);
	}

	/**
	 * The user's agent ids, via the agent adapter's public job-free listing
	 * (#180) — terminated agents excluded upstream, so no venue-state schema
	 * knowledge lives here. Empty before the user exists.
	 */
	public java.util.List<String> agents(String userDID) {
		try {
			covia.adapter.AgentAdapter adapter = (covia.adapter.AgentAdapter) engine().getAdapter("agent");
			if (adapter == null) return java.util.List.of();
			ACell out = adapter.listAgents(RequestContext.of(Strings.create(userDID)), false, false);
			java.util.List<String> ids = new java.util.ArrayList<>();
			if (convex.core.lang.RT.getIn(out, "agents") instanceof convex.core.data.AVector<?> vec) {
				for (long i = 0; i < vec.count(); i++) ids.add(String.valueOf(vec.get(i)));
			}
			return ids;
		} catch (Exception e) {
			return java.util.List.of();
		}
	}

	/**
	 * Subscribes to the venue's live agent event tap (#394) for one agent —
	 * the same ordered run-loop/cycle/tool events the SSE stream carries,
	 * delivered in-process. The callback runs on the emitting thread: hand
	 * off, never block. Close the returned subscription when done.
	 */
	public AutoCloseable subscribeAgent(String ownerDID, String agentId,
			java.util.function.Consumer<covia.venue.AgentEvents.Event> onEvent) {
		return engine().agentEvents().subscribe(Strings.create(ownerDID), Strings.create(agentId), onEvent::accept);
	}

	/**
	 * The user's encrypted secret store ({@code s/}), straight from the
	 * in-process venue state. With {@code ensure} the user record is created
	 * first; otherwise null before the user exists. Values decrypt only with
	 * {@link #secretKey()} — the venue identity's key, exactly as
	 * {@code secret:set} encrypts them.
	 */
	public covia.venue.SecretStore secrets(String userDID, boolean ensure) {
		try {
			var users = engine().getVenueState().users();
			User user = ensure ? users.ensure(Strings.create(userDID)) : users.get(Strings.create(userDID));
			return (user == null) ? null : user.secrets();
		} catch (Exception e) {
			return null;
		}
	}

	/** The AES key for {@link #secrets}: derived from the venue key pair, as the secret adapter derives it. */
	public byte[] secretKey() {
		return covia.venue.SecretStore.deriveKey(engine().getKeyPair());
	}

	/** The user's HITL inbox ({@code h/}): request id → record, straight from the in-process lattice. Null before the user exists. */
	public AMap<AString, ACell> inbox(String userDID) {
		try {
			User user = engine().getVenueState().users().get(Strings.create(userDID));
			return (user == null) ? null : user.getHitlRequests();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolves any lattice {@code path} as {@code userDID} straight from the
	 * in-process engine — no job. Null if absent or on any resolution error.
	 */
	public ACell resolve(String userDID, String path) {
		try {
			RequestContext ctx = RequestContext.of(Strings.create(userDID));
			return engine().resolvePath(Strings.create(path), ctx);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * A trusted in-process client acting as {@code userDID}. With
	 * {@code users.autoCreate} the user is registered on first use, so a
	 * freshly chosen {@code <venueDID>:u:<name>} principal just works.
	 */
	public Venue clientAs(String userDID) {
		LocalVenue local = LocalVenue.create(engine());
		local.setUser(userDID);
		return local;
	}

	/**
	 * A trusted in-process client acting as the venue itself — the operator.
	 * Covia's administrative gates admit exactly this principal; Brightside uses
	 * it for the things the app does on the owner's behalf as operator, such as
	 * keeping {@link Odin} configured and answering his Inbox requests.
	 */
	public Venue operator() {
		return clientAs(did());
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
