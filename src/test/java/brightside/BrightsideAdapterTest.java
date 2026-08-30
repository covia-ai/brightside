package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import convex.core.data.prim.CVMBool;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.BrightsideSkillsAdapter;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.Odin;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * Boots a real venue via {@link EmbeddedVenue} (which registers both Brightside
 * adapters) and checks the {@code brightside:info} op and the seeded skillset.
 */
class BrightsideAdapterTest {

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
	void infoReportsAppAndVenue() throws Exception {
		Venue client = venue.clientAs(venue.did());
		Job job = client.invoke("v/ops/brightside/info", Maps.empty()).get(5, TimeUnit.SECONDS);
		ACell result = job.future().get(5, TimeUnit.SECONDS);
		assertNotNull(result);
		assertEquals(Strings.create("Brightside"), RT.getIn(result, "app"));
		assertEquals(Strings.create(venue.did()), RT.getIn(result, "did"));
		assertNotNull(RT.getIn(result, "version"), "reports a version");
		assertTrue(vectorContains(RT.getIn(result, "skills"), Strings.create("introduction")));
	}

	@Test
	void shutdownIsVenueOperatorOnly() throws Exception {
		// The operator (a caller authenticated as the venue's own DID) is accepted.
		// This test venue is launched without a shutdown callback, so nothing exits.
		Venue operator = venue.clientAs(venue.did());
		Job accepted = operator.invoke("v/ops/brightside/shutdown", Maps.empty()).get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(accepted.future().get(5, TimeUnit.SECONDS), "accepted"));

		// A normal user is rejected.
		String userDID = Identity.of("intruder").userDID(venue.did());
		Job denied = venue.clientAs(userDID).invoke("v/ops/brightside/shutdown", Maps.empty())
			.get(5, TimeUnit.SECONDS);
		assertThrows(Exception.class, () -> denied.future().get(5, TimeUnit.SECONDS),
			"a non-operator cannot shut the process down");
	}

	@Test
	void seedsTheDefaultSkillset() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String path : BrightsideSkillsAdapter.SHIPPED) {
			assertNotNull(venue.engine().resolvePath(Strings.create(path), ctx), "skill present: " + path);
		}
		// The conversations skill is what grants the past-session tools; the
		// ops it names must exist in the venue's catalogue or the load is hollow.
		for (String op : new String[] { "v/ops/agent/sessions", "v/ops/agent/session-read",
			"v/ops/agent/rename-session", "v/ops/agent/delete-session", "v/ops/agent/context",
			"v/ops/brightside/context", "v/ops/brightside/delete-skill",
			"v/ops/brightside/report-skill-feedback", Odin.OP_ASK, Odin.OP_RUN }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(op), ctx), "op present: " + op);
		}

		ACell introduction = venue.engine().resolvePath(
			Strings.create(BrightsideSkillsAdapter.INTRODUCTION), ctx);
		assertTrue(RT.getIn(introduction, "content", "inline") instanceof AString,
			"greeting guidance is a self-contained on-demand skill");

		ACell authoring = venue.engine().resolvePath(
			Strings.create(BrightsideSkillsAdapter.SKILL_AUTHORING), ctx);
		assertTrue(RT.getIn(authoring, "skill", "tools") instanceof AVector<?>,
			"skill authoring declares its capability set structurally");
	}

	@Test
	void contextOperationReturnsLoadableText() throws Exception {
		Venue client = venue.clientAs(Identity.of("context-owner").userDID(venue.did()));
		Job job = client.invoke("v/ops/brightside/context", Maps.of(
			"userName", "Context Owner",
			"modelOperation", AppConfig.ECHO_LLM_OPERATION)).get(5, TimeUnit.SECONDS);
		assertTrue(job.future().get(5, TimeUnit.SECONDS) instanceof AString,
			"an operation load must resolve to text");
	}

	@Test
	void deleteSkillIsCallerScopedValidatedAndIdempotent() throws Exception {
		String userDID = Identity.of("skill-owner").userDID(venue.did());
		Venue user = venue.clientAs(userDID);
		String path = "w/skills/probe-writable";
		Job write = user.invoke("v/ops/covia/write", Maps.of(
			"path", path,
			"value", Maps.of("name", "probe-writable", "description", "Disposable test skill")))
			.get(5, TimeUnit.SECONDS);
		write.future().get(5, TimeUnit.SECONDS);
		RequestContext ctx = RequestContext.of(Strings.create(userDID));
		assertNotNull(venue.engine().resolvePath(Strings.create(path), ctx));

		Job deletion = user.invoke("v/ops/brightside/delete-skill", Maps.of("name", "probe-writable"))
			.get(5, TimeUnit.SECONDS);
		ACell result = deletion.future().get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "deleted"));
		assertNull(venue.engine().resolvePath(Strings.create(path), ctx));

		Job again = user.invoke("v/ops/brightside/delete-skill", Maps.of("name", "probe-writable"))
			.get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.FALSE, RT.getIn(again.future().get(5, TimeUnit.SECONDS), "deleted"));

		Job invalid = user.invoke("v/ops/brightside/delete-skill", Maps.of("name", "../memory"))
			.get(5, TimeUnit.SECONDS);
		assertThrows(Exception.class, () -> invalid.future().get(5, TimeUnit.SECONDS),
			"the operation accepts a name, never an attacker-controlled path");
	}

	@Test
	void skillFeedbackAppendsPrivateStructuredRecords() throws Exception {
		String userDID = Identity.of("feedback-owner").userDID(venue.did());
		Venue user = venue.clientAs(userDID);
		Job first = user.invoke("v/ops/brightside/report-skill-feedback", Maps.of(
			"category", "failed-load",
			"summary", "conversations did not resolve",
			"failedAction", "skill_load conversations",
			"error", "not found")).get(5, TimeUnit.SECONDS);
		ACell receipt = first.future().get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(receipt, "recorded"));
		AString firstPath = RT.ensureString(RT.getIn(receipt, "path"));
		assertTrue(firstPath.toString().startsWith("w/skill-feedback/"));

		RequestContext ctx = RequestContext.of(Strings.create(userDID));
		ACell record = venue.engine().resolvePath(firstPath, ctx);
		assertEquals(Strings.create("failed-load"), RT.getIn(record, "category"));
		assertEquals(Strings.create("conversations did not resolve"), RT.getIn(record, "summary"));
		assertEquals(Strings.create(userDID), RT.getIn(record, "reporter"));
		assertNotNull(RT.getIn(record, "created"));

		Job second = user.invoke("v/ops/brightside/report-skill-feedback", Maps.of(
			"category", "instruction-conflict",
			"summary", "instructions disagreed with the live palette"))
			.get(5, TimeUnit.SECONDS);
		second.future().get(5, TimeUnit.SECONDS);
		ACell log = venue.engine().resolvePath(Strings.create("w/skill-feedback"), ctx);
		assertTrue(log instanceof AMap<?, ?>);
		assertEquals(2, ((AMap<?, ?>) log).count(), "reports append at unique paths");

		Blob sessionId = Blob.fromHex("00112233445566778899aabbccddeeff");
		RequestContext agentCtx = RequestContext.ofAgent(
			Strings.create(userDID), Strings.create("brightside")).withSessionId(sessionId);
		Job fromAgent = venue.engine().jobs().invokeOperation(
			"v/ops/brightside/report-skill-feedback",
			Maps.of("category", "missing-skill", "summary", "Expected writing helper absent"),
			agentCtx);
		ACell agentReceipt = fromAgent.future().get(5, TimeUnit.SECONDS);
		ACell agentRecord = venue.engine().resolvePath(
			RT.ensureString(RT.getIn(agentReceipt, "path")), ctx);
		assertEquals(Strings.create("brightside"), RT.getIn(agentRecord, "agentId"));
		assertEquals(Strings.create(sessionId.toHexString()), RT.getIn(agentRecord, "sessionId"));

		Job invalid = user.invoke("v/ops/brightside/report-skill-feedback", Maps.of(
			"category", "anything-goes", "summary", "bad"))
			.get(5, TimeUnit.SECONDS);
		assertThrows(Exception.class, () -> invalid.future().get(5, TimeUnit.SECONDS));
	}

	private static boolean vectorContains(ACell cell, ACell expected) {
		if (!(cell instanceof AVector<?> vector)) return false;
		for (long i = 0; i < vector.count(); i++) {
			if (expected.equals(vector.get(i))) return true;
		}
		return false;
	}
}
