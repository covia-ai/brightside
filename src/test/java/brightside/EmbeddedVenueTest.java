package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Boots a real venue server from BrightSide's default config (temp store,
 * ephemeral port) — the launch itself, so this venue is the test's own.
 */
class EmbeddedVenueTest {

	@TempDir
	Path home;

	@Test
	void launchesServesAndCloses() throws Exception {
		EmbeddedVenue v = EmbeddedVenue.launch(SharedVenue.config(home));
		try {
			int port = v.port();
			assertTrue(port > 0, "port 0 asks the server to pick one, and it reports the port it picked");
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
