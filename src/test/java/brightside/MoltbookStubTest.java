package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The Moltbook operations against a stand-in for Moltbook on loopback: what
 * reaches the agent when Moltbook refuses, and what of an answer it is given.
 * The venue is this class's own, so its Moltbook adapter can be pointed at the
 * stand-in; each test is its own user and asks for its own paths.
 */
@Execution(ExecutionMode.CONCURRENT)
class MoltbookStubTest {

	@TempDir
	static Path home;

	/** What the stand-in answers on a path below the API root. */
	private record Answer(int status, String retryAfter, String body) {
	}

	private static final Map<String, Answer> answers = new ConcurrentHashMap<>();
	private static HttpServer moltbook;
	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() throws IOException {
		moltbook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		moltbook.createContext("/api/v1/", exchange -> {
			Answer a = answers.getOrDefault(exchange.getRequestURI().getPath().substring("/api/v1".length()),
				new Answer(404, null, "{\"success\": false, \"error\": \"Not found\"}"));
			byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			if (a.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", a.retryAfter());
			exchange.sendResponseHeaders(a.status(), body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		moltbook.start();
		venue = EmbeddedVenue.launch(SharedVenue.config(home));
		venue.engine().registerAdapter(new MoltbookAdapter(
			"http://127.0.0.1:" + moltbook.getAddress().getPort() + "/api/v1"));
	}

	@AfterAll
	static void stop() {
		if (venue != null) venue.close();
		if (moltbook != null) moltbook.stop(0);
	}

	@Test
	void aRateLimitRefusalNamesTheWait() throws Exception {
		answers.put("/posts/limited/upvote", new Answer(429, "45", """
			{"statusCode": 429, "message": "Rate limit exceeded", "remaining": 0,
			 "reset_at": "2026-10-30T15:22:00.000Z", "retry_after_seconds": 45}
			"""));
		String refusal = failure(connected("molty-limited"), "v/ops/moltbook/vote", Maps.of("target", "post", "id", "limited"));
		assertTrue(refusal.contains("Rate limit exceeded"), "Moltbook's own message: " + refusal);
		assertTrue(refusal.contains("45 seconds"), "and the wait: " + refusal);
	}

	@Test
	void theHeaderNamesTheWaitWhenTheBodyDoesNot() throws Exception {
		answers.put("/comments/limited/upvote", new Answer(429, "30", "{\"message\": \"Rate limit exceeded\"}"));
		String refusal = failure(connected("molty-header"), "v/ops/moltbook/vote", Maps.of("target", "comment", "id", "limited"));
		assertTrue(refusal.contains("30 seconds"), "the Retry-After header's wait: " + refusal);
	}

	@Test
	void aPostsAuthorsComeByName() throws Exception {
		answers.put("/posts/thread", new Answer(200, null, """
			{"success": true, "post": {"id": "thread", "title": "A title", "content": "The post",
			 "author": {"id": "a1", "name": "padmeai", "description": "A long bio", "karma": 1254, "followerCount": 85}}}
			"""));
		answers.put("/posts/thread/comments", new Answer(200, null, """
			{"success": true, "post_id": "thread", "has_more": true, "next_cursor": "page-2",
			 "comments": [{"id": "c1", "content": "First", "author_id": "a2",
			   "author": {"id": "a2", "name": "vina", "description": "Another bio", "lastActive": "2026-10-31T09:54:58.737Z"},
			   "replies": [{"id": "c2", "parent_id": "c1", "content": "A reply",
			     "author": {"id": "a3", "name": "brightside", "description": "A third bio"}, "replies": []}]}]}
			"""));
		ACell out = run(connected("molty-reader"), "v/ops/moltbook/read-post", Maps.of("id", "thread"));

		assertEquals(Maps.of("name", "padmeai"), RT.getIn(out, "post", "post", "author"));
		assertEquals(Maps.of("name", "vina"), RT.getIn(out, "comments", "comments", 0, "author"));
		assertEquals(Maps.of("name", "brightside"), RT.getIn(out, "comments", "comments", 0, "replies", 0, "author"));
		// Everything else a reader follows the thread by is as Moltbook sent it.
		assertEquals("The post", RT.getIn(out, "post", "post", "content").toString());
		assertEquals("A reply", RT.getIn(out, "comments", "comments", 0, "replies", 0, "content").toString());
		assertEquals("c1", RT.getIn(out, "comments", "comments", 0, "replies", 0, "parent_id").toString());
		assertEquals("page-2", RT.getIn(out, "comments", "next_cursor").toString());
	}

	/** A user with a Moltbook key in their own secret store. */
	private static Venue connected(String name) throws Exception {
		Venue user = venue.clientAs(Identity.of(name).userDID(venue.did()));
		run(user, "v/ops/secret/set", Maps.of("name", Moltbook.KEY_SECRET, "value", "moltbook_test_only"));
		return user;
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
