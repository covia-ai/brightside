package brightside;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Detects an already-running Brightside instance and takes over from it cleanly.
 *
 * <p>Two Brightside processes can't share the venue's Etch store — the second
 * fails on the store lock. Rather than fail, a new instance asks the running one
 * to shut down over the venue's own HTTP surface (the {@code brightside:shutdown}
 * operation), waits for it to release the store, and then starts its own venue.
 *
 * <p><b>Detection.</b> Brightside's venue is private: public access is disabled,
 * so {@code /api/v1/status} answers {@code 401} to a stranger. Any HTTP answer on
 * the loopback port therefore means <em>something</em> is running; only a refused
 * or timed-out connection means nothing is. (Treating only {@code 200} as
 * "running" made a private instance invisible, and the newcomer died on the
 * store lock with no takeover offered.) What it is — a Brightside, which store
 * it holds, which process — is learnt with {@link #identify} once the key is in
 * hand, from {@code brightside:info}.
 *
 * <p><b>Auth.</b> Shutdown is venue-operator only. The newcomer unlocks the same
 * encrypted identity seed, then mints a short-lived <em>venue-signed</em> token
 * (issuer and subject = the running venue's DID) — the venue trusts JWTs it
 * signed itself, and authenticates the bearer as its own DID, i.e. the operator.
 * The DID is the one the venue reports when it talks to strangers, otherwise the
 * {@code did:key} of the seed's public key — the same derivation the venue uses
 * for its own identity on loopback. The seed is never read from a plaintext key
 * file, and a seed that does not match the running venue is refused by it.
 *
 * <p><b>Release.</b> The venue closes its HTTP listener <em>before</em> it
 * flushes and closes the store, so the port going quiet does not mean the store
 * is free. The newcomer therefore also waits for the instance's process to exit
 * ({@link #waitUntilExited}) and for the store file's lock to be released
 * ({@link #waitUntilReleased}) before launching its own venue.
 */
public final class Takeover {

	private static final Logger log = LoggerFactory.getLogger(Takeover.class);
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final long TOKEN_SECONDS = 300;
	private static final String DID_KEY = "did:key:";
	private static final String OP_INFO = "v/ops/brightside/info";
	private static final String OP_SHUTDOWN = "v/ops/brightside/shutdown";

	/** One client for every probe: {@link #waitUntilDown} polls, and a client per poll is a thread per poll. */
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();

	private Takeover() {
	}

	/** One answer from the loopback port: the HTTP status and, for a {@code 200}, the parsed body. */
	private record Probe(int status, ACell body) {
	}

	/**
	 * What is running on the port, as {@code brightside:info} reports it: the
	 * app, its version, the venue DID, the store it holds ({@code temp} or a
	 * path) and its process id — the last two null and zero from an instance
	 * older than this field set.
	 */
	public record Instance(String app, String version, String did, String store, long pid) {

		/** The store as a path, or null when it is not one (a temp store, or unreported). */
		public Path storePath() {
			if (store == null || store.isBlank() || "temp".equals(store) || "memory".equals(store)) return null;
			try {
				return Path.of(store).toAbsolutePath().normalize();
			} catch (RuntimeException e) {
				return null;
			}
		}

		/** One line for the log. */
		public String describe() {
			return app + " " + version + " (pid " + pid + ", store " + store + ", " + did + ")";
		}
	}

	/**
	 * The takeover cannot go ahead, for a reason the owner can act on: the
	 * message is in their words and is what the unlock screen shows.
	 */
	public static final class Refused extends Exception {
		private static final long serialVersionUID = 1L;

		Refused(String message) {
			super(message);
		}
	}

	/**
	 * True if a venue is answering on the loopback port — with <em>any</em> HTTP
	 * status. A private venue answers {@code 401} to an anonymous probe, and that
	 * is still an answer.
	 */
	public static boolean isRunning(int port) {
		return probe(port) != null;
	}

	/**
	 * The running venue's DID as it reports it to strangers (from
	 * {@code /api/v1/status}), or null when nothing answers or the venue does not
	 * talk to strangers — in which case {@link #venueDIDFor} supplies it.
	 */
	public static String venueDID(int port) {
		Probe probe = probe(port);
		ACell did = (probe != null && probe.status() == 200) ? RT.getIn(probe.body(), "did") : null;
		return (did != null) ? did.toString() : null;
	}

	/**
	 * The DID a venue running on {@code seedHex} presents on loopback: the
	 * {@code did:key} of the seed's Ed25519 public key, exactly as the venue
	 * derives its own identity when no DID is configured.
	 */
	public static String venueDIDFor(String seedHex) {
		return DID.forKey(keyPair(seedHex).getAccountKey()).toString();
	}

	/**
	 * Asks whatever answers on the port what it is, as the venue operator:
	 * {@code brightside:info} run with a token signed by {@code seedHex}. The
	 * answer says it is a Brightside holding our identity, and names its store
	 * and process. Refused — with a message for the owner — when the key is not
	 * accepted (a different identity), when the operation is unknown (a Covia
	 * venue that is not Brightside) or when the answer is not a venue's at all
	 * (something else on the port).
	 */
	public static Instance identify(int port, String seedHex) throws Refused {
		if (seedHex == null || seedHex.isBlank()) {
			throw new IllegalStateException("no venue key available to identify the running instance");
		}
		AKeyPair keyPair = keyPair(seedHex);
		String did = DID.forKey(keyPair.getAccountKey()).toString();
		HttpResponse<String> response;
		try {
			response = run(port, token(did, keyPair), OP_INFO);
		} catch (Exception e) {
			throw new Refused("Something is listening on port " + port + " but it isn't answering as Brightside."
				+ " Stop it, or change the port in config.json, and try again.");
		}
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new Refused("The Brightside already running on this computer has a different identity,"
				+ " so this one can't ask it to stop. Quit it (tray icon ▸ Quit) and try again.");
		}
		ACell body = null;
		if (response.statusCode() == 200) {
			try {
				body = JSON.parseJSON5(response.body());
			} catch (Exception e) {
				body = null;
			}
		}
		ACell app = RT.getIn(body, "app");
		if (app == null) {
			throw new Refused("Something is listening on port " + port + " but it isn't Brightside"
				+ " (HTTP " + response.statusCode() + "). Stop it, or change the port in config.json, and try again.");
		}
		return new Instance(app.toString(), text(RT.getIn(body, "version")), text(RT.getIn(body, "did")),
			text(RT.getIn(body, "store")), (RT.getIn(body, "pid") instanceof CVMLong l) ? l.longValue() : 0L);
	}

	/**
	 * Asks the running instance to shut down cleanly, authenticated as the venue
	 * operator via a token signed with the seed. {@code venueDID} is the DID the
	 * venue reported, or null when it did not; a reported {@code did:key} that
	 * is not the seed's is refused here without a request. Throws if the
	 * request is rejected — a wrong key ({@code 401}), a refusal by the
	 * operation ({@code 400}), or the op being unavailable on an older instance.
	 */
	public static void requestShutdown(int port, String venueDID, String seedHex) throws Exception {
		if (seedHex == null || seedHex.isBlank()) {
			throw new IllegalStateException("no venue key available to authorise the shutdown");
		}
		AKeyPair keyPair = keyPair(seedHex);
		String ours = DID.forKey(keyPair.getAccountKey()).toString();
		if (venueDID != null && venueDID.startsWith(DID_KEY) && !venueDID.equals(ours)) {
			throw new IllegalStateException("the running instance has a different identity (" + venueDID + ")");
		}
		String did = (venueDID != null) ? venueDID : ours;
		HttpResponse<String> response = run(port, token(did, keyPair), OP_SHUTDOWN);
		if (response.statusCode() != 200) {
			throw new IllegalStateException("shutdown request rejected: HTTP "
				+ response.statusCode() + " " + response.body());
		}
		log.info("The running instance on port {} accepted the shutdown request", port);
	}

	/** Polls until the instance stops answering, or the timeout elapses. */
	public static boolean waitUntilDown(int port, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (!isRunning(port)) return true;
			if (!pause(250)) return false;
		}
		return !isRunning(port);
	}

	/**
	 * Waits for the process to exit. True at once for a pid of zero (unknown) or
	 * one that is not alive; false if it is still running when the timeout
	 * elapses. The current process never "exits" for this purpose.
	 */
	public static boolean waitUntilExited(long pid, long timeoutMillis) {
		if (pid <= 0) return true;
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);
		if (handle.isEmpty() || !handle.get().isAlive()) return true;
		try {
			handle.get().onExit().get(timeoutMillis, TimeUnit.MILLISECONDS);
			return true;
		} catch (TimeoutException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Exception e) {
			return !handle.get().isAlive();
		}
	}

	/**
	 * Waits until nothing holds the store file's lock — Etch's exclusive lock,
	 * released only when the store is closed. True at once when there is no such
	 * file; false if the lock is still held when the timeout elapses. Never
	 * keeps the lock: it is taken and released on each try.
	 */
	public static boolean waitUntilReleased(Path store, long timeoutMillis) {
		if (store == null || !Files.isRegularFile(store)) return true;
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (true) {
			if (lockFree(store)) return true;
			if (System.currentTimeMillis() >= deadline) return false;
			if (!pause(250)) return false;
		}
	}

	private static boolean lockFree(Path store) {
		try (FileChannel channel = FileChannel.open(store, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock lock = channel.tryLock();
			if (lock == null) return false;
			lock.release();
			return true;
		} catch (OverlappingFileLockException e) {
			return false; // held in this very process (a venue still closing here)
		} catch (IOException e) {
			return false; // the file is busy in a way we cannot read past yet
		}
	}

	private static boolean pause(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String token(String did, AKeyPair keyPair) {
		long now = System.currentTimeMillis() / 1000;
		return JWT.signPublic(Maps.of(
			"sub", did, "iss", did, "aud", did,
			"iat", now, "exp", now + TOKEN_SECONDS), keyPair).toString();
	}

	/** {@code POST /api/v1/run} of {@code operation} with no input, as the bearer of {@code token}. */
	private static HttpResponse<String> run(int port, String token, String operation) throws Exception {
		String body = JSON.toStringPretty(Maps.of("operation", operation, "input", Maps.empty()));
		HttpRequest request = HttpRequest.newBuilder(URI.create(base(port) + "/api/v1/run"))
			.timeout(REQUEST_TIMEOUT)
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String text(ACell cell) {
		return (cell == null) ? null : cell.toString();
	}

	private static AKeyPair keyPair(String seedHex) {
		Blob seed = Blob.fromHex(seedHex.trim());
		if (seed == null || seed.count() != 32) {
			throw new IllegalStateException("the venue key must be a 32-byte Ed25519 seed");
		}
		return AKeyPair.create(seed);
	}

	/** The venue's answer on the port, or null when nothing is listening (or not ready). */
	private static Probe probe(int port) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base(port) + "/api/v1/status"))
				.timeout(PROBE_TIMEOUT).GET().build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			ACell body = null;
			if (response.statusCode() == 200) {
				try {
					body = JSON.parseJSON5(response.body());
				} catch (Exception e) {
					body = null; // answered, but not with a status document we can read
				}
			}
			return new Probe(response.statusCode(), body);
		} catch (Exception e) {
			return null; // nothing listening / not ready
		}
	}

	private static String base(int port) {
		return "http://127.0.0.1:" + port;
	}
}
