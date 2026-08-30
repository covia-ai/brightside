package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.Inbox;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.hitl.Hitl;
import covia.venue.Config;

/**
 * Mechanism test for the Inbox: a HITL request lands in the owner's {@code h/}
 * inbox exactly as {@link Inbox} reads it, and answering or rejecting it through
 * Brightside's response path resolves the requester's job.
 */
class InboxTest {

	private static final long TIMEOUT_SECONDS = 30;

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;
	private static Venue client;
	private static String userDID;

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
		userDID = Identity.of("inbox-test").userDID(venue.did());
		client = venue.clientAs(userDID);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@Test
	void answersResolveTheRequestersJob() throws Exception {
		Job job = request(Hitl.request("Pay invoice INV-4711")
			.description("Acme Ltd, £12,400, matched to PO-2231.")
			.ask(Hitl.approval("pay", "Approve payment?").required().grant("w/payments/", "crud/read"))
			.ask(Hitl.text("note", "Anything to add?").allowComment())
			.ask(Hitl.choice("when", "When?").option("now", "Now").option("later", "Later"))
			.ask(Hitl.checkboxes("notify", "Notify by").option("email", "Email").option("sms", "SMS"))
			.timeout(600));
		String id = job.getID().toHexString();
		Inbox.Request r = awaitRequest(id);

		assertTrue(r.open());
		assertEquals("Pay invoice INV-4711", r.title());
		assertEquals("Acme Ltd, £12,400, matched to PO-2231.", r.description());
		assertEquals(userDID, r.from(), "a user asking themselves");
		assertNull(r.agent());
		assertTrue(r.expires() > r.created(), "timeout becomes an expiry");
		assertEquals(List.of("pay", "note", "when", "notify"), r.asks().stream().map(Inbox.Ask::id).toList());
		Inbox.Ask pay = r.asks().get(0);
		assertEquals("approval", pay.type());
		assertTrue(pay.required());
		assertEquals(List.of(new Inbox.Grant("w/payments/", "crud/read", null)), pay.grants());
		assertTrue(r.asks().get(1).allowComment());
		assertEquals(List.of("Now", "Later"), r.asks().get(2).options().stream().map(Inbox.Option::label).toList());
		assertEquals(1, Inbox.pending(Inbox.parse(venue.inbox(userDID))));

		Map<String, Object> answers = new LinkedHashMap<>();
		answers.put("pay", true);
		answers.put("note", "Fine");
		answers.put("when", "now");
		answers.put("notify", List.of("email", "sms"));
		Inbox.answer(client, id, new Inbox.Answer(answers, Map.of("note", "per-ask"), List.of(),
			"Approved for this invoice only"));

		ACell output = job.future().get(10, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(output, "answers", "pay"));
		assertEquals(Strings.create("now"), RT.getIn(output, "answers", "when"));
		assertNull(RT.getIn(output, "token"), "consent is the echo, not the approval: nothing echoed, nothing granted");

		Inbox.Request done = find(id);
		assertFalse(done.open());
		assertEquals("answered", done.status());
		Inbox.Response resp = done.response();
		assertEquals("answer", resp.outcome());
		assertEquals("Approved", resp.answers().get("pay"));
		assertEquals("Fine", resp.answers().get("note"));
		assertEquals("Now", resp.answers().get("when"));
		assertEquals("Email, SMS", resp.answers().get("notify"));
		assertEquals("Approved for this invoice only", resp.comment());
		assertTrue(resp.grants().isEmpty());
		assertEquals(0, Inbox.pending(Inbox.parse(venue.inbox(userDID))));
	}

	/**
	 * covia#440 resolved: a venue roots grants for the user DIDs it issued below
	 * its own identity — a personal venue's {@code did:key:…:u:name} subjects
	 * included. Echoing an offered grant therefore mints: the requester's job
	 * carries a venue-signed token that verifies here with the venue as its root
	 * authority, and the inbox record shows the grant conferred.
	 */
	@Test
	void echoingAGrantMintsAVenueRootedToken() throws Exception {
		Job job = request(Hitl.request("Read my reports")
			.ask(Hitl.approval("ok", "Allow?").grant("w/reports/", "crud/read")));
		String id = job.getID().toHexString();
		awaitRequest(id);

		Inbox.Grant offered = new Inbox.Grant("w/reports/", "crud/read", null);
		Inbox.answer(client, id, new Inbox.Answer(Map.of("ok", true), Map.of(), List.of(offered), null));

		ACell output = job.future().get(10, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(output, "answers", "ok"));
		AString token = RT.ensureString(RT.getIn(output, "token"));
		assertNotNull(token, "the echoed grant is minted into the requester's job output: " + output);

		ACell verdict = client.invoke("v/ops/ucan/verify", Maps.of("token", token))
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(verdict, "valid"), "verdict: " + verdict);
		assertEquals(Strings.create(venue.did()), RT.getIn(verdict, "iss"), "signed by the venue");
		assertEquals(Strings.create(userDID), RT.getIn(verdict, "aud"), "for the requester");
		AVector<ACell> att = RT.ensureVector(RT.getIn(verdict, "att"));
		assertEquals(Strings.create("venue"), RT.getIn(att.get(0), "rootAuthority"),
			"the venue is root authority for its own :u: user's resource: " + verdict);

		Inbox.Request done = find(id);
		assertFalse(done.open());
		assertEquals(List.of(offered), done.response().grants(), "the record shows the grant conferred");
	}

	@Test
	void rejectionFailsTheRequestersJobWithTheReason() throws Exception {
		Job job = request(Hitl.request("Delete everything").ask(Hitl.approval("ok", "Sure?").required()));
		String id = job.getID().toHexString();
		awaitRequest(id);

		Inbox.reject(client, id, "Not now");

		ExecutionException e = assertThrows(ExecutionException.class, () -> job.future().get(10, TimeUnit.SECONDS));
		assertTrue(String.valueOf(e.getCause().getMessage()).contains("Not now"), e.getCause().getMessage());
		Inbox.Request done = find(id);
		assertEquals("rejected", done.status());
		assertEquals("Not now", done.response().comment());
	}

	private static Job request(Hitl.RequestBuilder request) throws Exception {
		return client.invoke("v/ops/hitl/request", request.build()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** The request as the inbox shows it, once the venue has delivered it. */
	private static Inbox.Request awaitRequest(String id) throws Exception {
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			for (Inbox.Request r : Inbox.parse(venue.inbox(userDID))) if (id.equals(r.id())) return r;
			Thread.sleep(50);
		}
		throw new AssertionError("request " + id + " never reached the inbox");
	}

	private static Inbox.Request find(String id) {
		return Inbox.parse(venue.inbox(userDID)).stream().filter(r -> id.equals(r.id())).findFirst().orElseThrow();
	}
}
