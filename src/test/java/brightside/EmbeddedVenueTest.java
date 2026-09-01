package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.chat.ChatSession;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;

/** Boots a real venue server from BrightSide's default config (temp store, free port). */
class EmbeddedVenueTest {

	@TempDir
	Path home;

	/** A port nothing is listening on right now — the venue defaults a missing port to 8080. */
	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	void launchesServesAndCloses() throws Exception {
		int port = freePort();
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port));
		EmbeddedVenue v = EmbeddedVenue.launch(config);
		try {
			assertEquals(port, v.port());
			assertNotNull(v.did());
			assertEquals(AppConfig.DEFAULT_VENUE_NAME, v.name());
			assertEquals("http://127.0.0.1:" + port + "/", v.url());

			// A named local user (u:tester) chats against the live venue...
			String userDID = Identity.of("tester").userDID(v.did());
			ChatSession chat = new ChatSession(v.clientAs(userDID), new AppConfig.Chat("bs-venue",
				AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo."), "Tester");
			assertTrue(chat.send("ping").text().contains("ping"));

			// ...and the HTTP surface is listening on loopback.
			HttpResponse<Void> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(v.url())).GET().build(),
				HttpResponse.BodyHandlers.discarding());
			assertTrue(response.statusCode() < 500, "status " + response.statusCode());
		} finally {
			v.close();
		}
		v.close(); // idempotent
	}
}
