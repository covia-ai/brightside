package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * The Moltbook operations on a real venue, up to the edge of the network:
 * every operation is installed, an account that is not set up is reported as
 * such rather than attempted, and input is refused before any request.
 */
class MoltbookAdapterTest {

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port));
		venue = EmbeddedVenue.launch(config);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void everyOperationIsInstalled() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : MoltbookAdapter.OPS) {
			ACell asset = venue.engine().resolvePath(Strings.create("v/ops/moltbook/" + op), ctx);
			assertNotNull(asset, "operation present: " + op);
			assertTrue(RT.getIn(asset, Fields.OPERATION) instanceof AMap,
				"operation metadata is an operation: " + op);
		}
	}

	@Test
	void withoutAnAccountTheOperationSaysWhereToSetOneUp() throws Exception {
		Venue user = venue.clientAs(Identity.of("no-moltbook").userDID(venue.did()));
		String message = failure(user, "v/ops/moltbook/home", Maps.empty());
		assertTrue(message.contains("Settings"), "names where the owner sets it up: " + message);
	}

	@Test
	void accountReportsNotConfiguredWithoutAKey() throws Exception {
		Venue user = venue.clientAs(Identity.of("unconfigured").userDID(venue.did()));
		ACell out = run(user, "v/ops/moltbook/account", Maps.empty());
		assertEquals(CVMBool.FALSE, RT.getIn(out, "configured"));
		assertTrue(String.valueOf(RT.getIn(out, "how")).contains("register"), "says how to set one up");
	}

	@Test
	void registeringNeedsANameBeforeAnythingElse() throws Exception {
		Venue user = venue.clientAs(Identity.of("nameless").userDID(venue.did()));
		String message = failure(user, "v/ops/moltbook/register", Maps.of("description", "no name given"));
		assertTrue(message.contains("name"), "a name is required: " + message);
	}

	@Test
	void registeringTwiceIsRefused() throws Exception {
		Venue user = venue.clientAs(Identity.of("twice").userDID(venue.did()));
		run(user, "v/ops/secret/set", Maps.of("name", Moltbook.KEY_SECRET, "value", "moltbook_test_only"));
		String message = failure(user, "v/ops/moltbook/register", Maps.of("name", "AnotherMolty"));
		assertTrue(message.contains("already"), "an existing account is not replaced: " + message);
	}

	@Test
	void inputIsRefusedBeforeAnyRequestIsMade() throws Exception {
		Venue user = venue.clientAs(Identity.of("has-moltbook").userDID(venue.did()));
		// A key in the user's store lets the operation past the set-up check; the
		// input then has to be right before Moltbook would be contacted at all.
		run(user, "v/ops/secret/set", Maps.of("name", Moltbook.KEY_SECRET, "value", "moltbook_test_only"));

		String missingTitle = failure(user, "v/ops/moltbook/create-post", Maps.of("submolt", "general"));
		assertTrue(missingTitle.contains("title"), "a post needs a title: " + missingTitle);

		String badTarget = failure(user, "v/ops/moltbook/vote", Maps.of("target", "molty", "id", "x"));
		assertTrue(badTarget.contains("post or comment"), "vote targets are checked: " + badTarget);

		String badScope = failure(user, "v/ops/moltbook/feed", Maps.of("scope", "everything"));
		assertTrue(badScope.contains("scope"), "feed scopes are checked: " + badScope);

		String commentDownvote = failure(user, "v/ops/moltbook/vote",
			Maps.of("target", "comment", "id", "c1", "direction", "down"));
		assertTrue(commentDownvote.contains("upvoted"), "comments cannot be downvoted: " + commentDownvote);
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(10, TimeUnit.SECONDS);
		return job.future().get(10, TimeUnit.SECONDS);
	}

	/** The message the operation fails with. */
	private static String failure(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(10, TimeUnit.SECONDS);
		try {
			job.future().get(10, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			return String.valueOf(cause.getMessage());
		}
		throw new AssertionError(op + " should have failed");
	}
}
