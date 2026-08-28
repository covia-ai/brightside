package covia.brightside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Maps;
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
 * the loopback port therefore means a venue is running; only a refused or
 * timed-out connection means nothing is. (Treating only {@code 200} as "running"
 * made a private instance invisible, and the newcomer died on the store lock
 * with no takeover offered.)
 *
 * <p><b>Auth.</b> Shutdown is venue-operator only. The newcomer unlocks the same
 * encrypted identity seed, then mints a short-lived <em>venue-signed</em> token
 * (issuer and subject = the running venue's DID) — the venue trusts JWTs it
 * signed itself, and authenticates the bearer as its own DID, i.e. the operator.
 * The DID is the one the venue reports when it talks to strangers, otherwise the
 * {@code did:key} of the seed's public key — the same derivation the venue uses
 * for its own identity on loopback. The seed is never read from a plaintext key
 * file, and a seed that does not match the running venue is refused by it.
 */
public final class Takeover {

	private static final Logger log = LoggerFactory.getLogger(Takeover.class);
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
	private static final String DID_KEY = "did:key:";

	private Takeover() {
	}

	/** One answer from the loopback port: the HTTP status and, for a {@code 200}, the parsed body. */
	private record Probe(int status, ACell body) {
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
	 * Asks the running instance to shut down cleanly, authenticated as the venue
	 * operator via a token signed with the seed. {@code venueDID} is the DID the
	 * venue reported anonymously, or null when it did not; a reported
	 * {@code did:key} that is not the seed's is refused here without a request.
	 * Throws if the request is rejected — a wrong key ({@code 401}), a refusal by
	 * the operation ({@code 400}), or the op being unavailable on an older
	 * instance.
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

		long now = System.currentTimeMillis() / 1000;
		String token = JWT.signPublic(Maps.of(
			"sub", did, "iss", did, "aud", did,
			"iat", now, "exp", now + 300), keyPair).toString();

		String body = JSON.toStringPretty(Maps.of(
			"operation", "v/ops/brightside/shutdown", "input", Maps.empty()));
		HttpRequest request = HttpRequest.newBuilder(URI.create(base(port) + "/api/v1/run"))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
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
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return !isRunning(port);
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
			HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
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

	private static HttpClient client() {
		return HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
	}

	private static String base(int port) {
		return "http://127.0.0.1:" + port;
	}
}
