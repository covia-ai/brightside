package covia.brightside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>Auth.</b> Shutdown is venue-operator only. The newcomer unlocks the same
 * encrypted identity seed, then mints a short-lived <em>venue-signed</em> token
 * (issuer and subject = the running venue's DID, read from its
 * {@code /api/v1/status}) — the venue trusts JWTs it signed itself, and
 * authenticates the bearer as its own DID, i.e. the operator. The seed is never
 * read from a plaintext key file.
 */
public final class Takeover {

	private static final Logger log = LoggerFactory.getLogger(Takeover.class);
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

	private Takeover() {
	}

	/** True if a Brightside/Covia venue is answering on the loopback port. */
	public static boolean isRunning(int port) {
		return status(port) != null;
	}

	/** The running venue's DID (from {@code /api/v1/status}), or null if none answers. */
	public static String venueDID(int port) {
		ACell status = status(port);
		ACell did = (status != null) ? RT.getIn(status, "did") : null;
		return (did != null) ? did.toString() : null;
	}

	/**
	 * Asks the running instance to shut down cleanly, authenticated as the venue
	 * operator via a token signed with {@code venueKey}. Throws if the request is
	 * rejected (e.g. wrong key, or the op is unavailable on an older instance).
	 */
	public static void requestShutdown(int port, String venueDID, String seedHex) throws Exception {
		if (venueDID == null) throw new IllegalStateException("could not read the running venue's DID");
		if (seedHex == null || seedHex.isBlank()) {
			throw new IllegalStateException("no venue key available to authorise the shutdown");
		}
		AKeyPair keyPair = AKeyPair.create(Blob.fromHex(seedHex.trim()));
		long now = System.currentTimeMillis() / 1000;
		String token = JWT.signPublic(Maps.of(
			"sub", venueDID, "iss", venueDID, "aud", venueDID,
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

	private static ACell status(int port) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base(port) + "/api/v1/status"))
				.timeout(PROBE_TIMEOUT).GET().build();
			HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
			return (response.statusCode() == 200) ? JSON.parseJSON5(response.body()) : null;
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
