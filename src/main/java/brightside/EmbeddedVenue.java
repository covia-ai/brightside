package brightside;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger log = LoggerFactory.getLogger(EmbeddedVenue.class);

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
	 *
	 * <p>Brightside's adapters are registered in Covia's pre-start hook: after
	 * the built-in adapters are installed and configured secrets provisioned,
	 * before durable jobs are recovered and adapter workers activated. So a job
	 * a crash left on one of Brightside's own operations is recovered by the
	 * adapter that owns it, and the Discord bots re-arm when every other worker
	 * starts. A failure in the hook closes the half-assembled server before the
	 * exception reaches us, so nothing is left holding the port or the store.
	 */
	public static EmbeddedVenue launch(AMap<AString, ACell> config, Runnable onShutdown) {
		VenueServer server = VenueServer.launch(config, List.of(), engine -> {
			// Brightside's venue adapters: operations, and the default skill library.
			engine.registerAdapter(new BrightsideAdapter(onShutdown));
			engine.registerAdapter(new BrightsideSkillsAdapter());
			// Moltbook as typed operations; idle until the owner connects an account.
			engine.registerAdapter(new MoltbookAdapter());
			// Covia's Discord module, in-process rather than as a loaded module jar:
			// the same adapter, on Brightside's own classpath. With no bots it costs
			// nothing; the owner's bot is created from Settings (see Discord).
			engine.registerAdapter(new covia.adapter.discord.DiscordAdapter());
			// Covia's Claude Code module, the same way: the owner declares the
			// project directories in config.json (see ClaudeCode). A mistake in
			// that block is not worth a failed launch — it is logged and Claude
			// Code sits this one out.
			try {
				engine.registerAdapter(new covia.adapter.claudecode.ClaudeCodeAdapter());
			} catch (IllegalArgumentException | IllegalStateException e) {
				log.warn("Claude Code is not available this launch — check venue.adapters.claudecode in config.json: {}",
					e.getMessage());
			}
			// Covia's speech module, for voice typing in the chat (see
			// brightside.voice). Brightside's default runs it on this computer;
			// config.json may point it elsewhere, and a mistake there leaves
			// voice out of this launch rather than failing it.
			try {
				engine.registerAdapter(new covia.adapter.speech.SpeechAdapter());
			} catch (IllegalArgumentException | IllegalStateException e) {
				log.warn("Voice typing is not available this launch — check venue.adapters.speech in config.json: {}",
					e.getMessage());
			}
			// Registration is synchronous: nothing asynchronous for the venue to await.
			return CompletableFuture.completedFuture(null);
		});
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
	 * The user's agents for the Peers map: each one's id, display name and run
	 * state, read from its record in-process, so drawing them leaves no job
	 * records.
	 */
	public java.util.List<brightside.peers.HomePeers.Agent> agentsForMap(String userDID) {
		java.util.List<brightside.peers.HomePeers.Agent> out = new java.util.ArrayList<>();
		for (String aid : agents(userDID)) {
			ACell record = agentRecord(userDID, aid);
			AString state = convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(record, "status"));
			AString name = convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(record, "config", "name"));
			out.add(new brightside.peers.HomePeers.Agent(aid,
				(name == null || name.toString().isBlank()) ? aid : name.toString(),
				(state == null) ? null : state.toString()));
		}
		return out;
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

	/**
	 * {@code userDID}'s Peers map settings, kept by Brightside's own adapter;
	 * an empty in-memory store if that adapter isn't registered.
	 */
	public brightside.peers.MapSettingsStore mapSettings(String userDID) {
		if (!(engine().getAdapter("brightside") instanceof BrightsideAdapter adapter)) {
			return brightside.peers.MapSettingsStore.memory();
		}
		AString did = Strings.create(userDID);
		return new brightside.peers.MapSettingsStore() {
			@Override
			public brightside.peers.MapSettings load() {
				try {
					return brightside.peers.MapSettings.fromCell(adapter.mapSettings(did));
				} catch (RuntimeException e) {
					log.warn("Could not read the Peers map settings: {}", e.toString());
					return brightside.peers.MapSettings.DEFAULT;
				}
			}

			@Override
			public void save(brightside.peers.MapSettings settings) {
				adapter.saveMapSettings(did, settings.toCell());
			}
		};
	}

	/** The keys for {@link #secrets}: derived from the venue key pair, as the secret adapter derives them. */
	public covia.venue.SecretStore.Keys secretKey() {
		return covia.venue.SecretStore.keys(engine().getKeyPair());
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
	 * keeping the {@link Gods} configured and answering their Inbox requests.
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
