package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import convex.core.data.prim.CVMBool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

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
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * On a real venue launched by {@link EmbeddedVenue} (which registers both
 * Brightside adapters): the {@code brightside:info} op, the seeded skillset,
 * and Brightside's other operations.
 */
@Execution(ExecutionMode.CONCURRENT)
class BrightsideAdapterTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
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
			"v/ops/brightside/report-skill-feedback", Odin.OP_ASK, Odin.OP_RUN, Thor.OP_ALERT, Thor.OP_RUN, Njord.OP_ASK, Njord.OP_RUN }) {
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
	void contextKnowsTheDate() {
		StringBuilder text = new StringBuilder();
		BrightsideAdapter.appendClock(text);
		String today = java.time.LocalDate.now().toString();
		assertTrue(text.toString().contains("Today is " + today), text.toString());
		assertTrue(text.toString().matches("(?s).*Timestamp \\d{13}\\..*"), "the epoch millisecond alongside: " + text);
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

	/**
	 * As a context load the op runs under the agent's own identity, so the
	 * caller is the agent sub-principal. The owner it describes must be the
	 * user that agent works for, with the agent named as acting for them —
	 * and no heading of its own, since the pin that loads it labels the entry.
	 */
	@Test
	void contextOperationNamesTheOwnerNotTheAgentActingForThem() throws Exception {
		String userDID = Identity.of("context-owner").userDID(venue.did());
		String agentDID = userDID + ":g:Brightside";
		RequestContext agentCtx = RequestContext.ofAgent(Strings.create(userDID), Strings.create("Brightside"));
		Job job = venue.engine().jobs().invokeOperation("v/ops/brightside/context",
			Maps.of("userName", "Context Owner", "modelOperation", AppConfig.ECHO_LLM_OPERATION), agentCtx);
		String text = job.future().get(5, TimeUnit.SECONDS).toString();
		assertTrue(text.contains("user DID is " + userDID + "."), "the owner is the user, not the agent: " + text);
		assertTrue(text.contains("agent " + agentDID), "the agent is named as acting for them: " + text);
		assertFalse(text.startsWith("Brightside application context"), "the pin's label already says what this is");

		// Called by the owner directly, there is no agent to name.
		Venue owner = venue.clientAs(userDID);
		String direct = owner.run("v/ops/brightside/context", Maps.of("userName", "Context Owner"))
			.get(5, TimeUnit.SECONDS).toString();
		assertTrue(direct.contains("user DID is " + userDID + "."), direct);
		assertFalse(direct.contains("You act as"), direct);
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
			"the operation cannot escape the caller's skills");
	}

	@Test
	void deletesNestedSkillWithoutTouchingNamesakesSiblingsOrOtherUsers() throws Exception {
		String ownerDID = Identity.of("nested-skills").userDID(venue.did());
		String otherDID = Identity.of("other-skills").userDID(venue.did());
		Venue owner = venue.clientAs(ownerDID);
		Venue other = venue.clientAs(otherDID);
		ACell child = Maps.of("name", "child", "description", "A nested skill",
			"grandchild", Maps.of("name", "grandchild"));
		ACell sibling = Maps.of("name", "sibling", "description", "Keep this skill");
		ACell parent = Maps.of("name", "parent", "description", "Keep this parent",
			"child", child, "sibling", sibling);
		for (Venue client : new Venue[] { owner, other }) {
			client.run("v/ops/covia/write", Maps.of("path", "w/skills/parent", "value", parent))
				.get(5, TimeUnit.SECONDS);
		}
		owner.run("v/ops/covia/write", Maps.of("path", "w/skills/child", "value", child))
			.get(5, TimeUnit.SECONDS);

		ACell result = owner.run("v/ops/brightside/delete-skill", Maps.of("name", "parent/child"))
			.get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "deleted"));
		assertEquals(Strings.create("parent/child"), RT.getIn(result, "name"));
		RequestContext ctx = RequestContext.of(Strings.create(ownerDID));
		assertNull(venue.engine().resolvePath(Strings.create("w/skills/parent/child"), ctx));
		assertNull(venue.engine().resolvePath(Strings.create("w/skills/parent/child/grandchild"), ctx));
		assertEquals(sibling, venue.engine().resolvePath(Strings.create("w/skills/parent/sibling"), ctx));
		assertEquals(Strings.create("Keep this parent"),
			venue.engine().resolvePath(Strings.create("w/skills/parent/description"), ctx));
		assertEquals(child, venue.engine().resolvePath(Strings.create("w/skills/child"), ctx));
		assertEquals(parent, venue.engine().resolvePath(Strings.create("w/skills/parent"),
			RequestContext.of(Strings.create(otherDID))));
		ACell again = owner.run("v/ops/brightside/delete-skill", Maps.of("name", "parent/child"))
			.get(5, TimeUnit.SECONDS);
		assertEquals(CVMBool.FALSE, RT.getIn(again, "deleted"));
	}

	@Test
	void rejectsMalformedSkillPathsWithoutChangingTheWorkspace() throws Exception {
		String userDID = Identity.of("skill-paths").userDID(venue.did());
		Venue user = venue.clientAs(userDID);
		user.run("v/ops/covia/write", Maps.of("path", "w/skills/parent/child",
			"value", Maps.of("name", "child"))).get(5, TimeUnit.SECONDS);
		user.run("v/ops/covia/write", Maps.of("path", "w/memory", "value", "keep"))
			.get(5, TimeUnit.SECONDS);
		RequestContext ctx = RequestContext.of(Strings.create(userDID));
		ACell before = venue.engine().resolvePath(Strings.create("w"), ctx);
		for (String name : new String[] { "", "/", "/parent/child", "parent/", "parent//child",
			"../memory", "parent/../../memory", "parent/./child", "parent\\child",
			"parent/%2e%2e/memory", "parent/Child", "parent/-child", "parent/child-",
			"parent/" + "a".repeat(65), ("a/".repeat(512)) + "a" }) {
			assertThrows(Exception.class, () -> user.run("v/ops/brightside/delete-skill",
				Maps.of("name", name)).get(5, TimeUnit.SECONDS), name);
		}
		assertEquals(before, venue.engine().resolvePath(Strings.create("w"), ctx));
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
