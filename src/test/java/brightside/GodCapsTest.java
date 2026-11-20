package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Each god calls what its caps cover directly and Covia refuses the rest,
 * whatever tool it holds: the god's caps are the boundary, not its tools or a
 * bridge.
 */
class GodCapsTest {

	private static final long TIMEOUT_SECONDS = 30;
	private static final String TASK_LLM = "v/test/ops/taskllm";

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		Gods.ensureAll(venue, TASK_LLM);
	}

	@Test
	void everyGodButNjordSeesTheVenuesUsers() throws Exception {
		for (God god : new God[] { Odin.GOD, Thor.GOD, Eir.GOD }) {
			assertNotNull(done(god, "v/ops/user/list", Maps.empty()), god.id());
		}
		denied(Njord.GOD, "v/ops/user/list", Maps.empty());
	}

	@Test
	void odinRegistersUsersButNeitherDeletesThemNorAddsTheirSignInsHimself() throws Exception {
		AString made = Strings.create(venue.did() + ":u:caps-made");
		done(Odin.GOD, "v/ops/user/create", Maps.of(Fields.DID, made));
		assertNotNull(venue.engine().getVenueState().users().user(made), "registered");
		denied(Odin.GOD, "v/ops/user/delete", Maps.of(Fields.DID, made));
		assertFalse(venue.engine().getVenueState().users().isDeleted(made), "Covia keeps the account from his caps");
		assertFalse(venue.engine().hasVenueAuthority(GodContexts.of(venue, Odin.AGENT_ID), "users",
			Abilities.ADMIN_USER_AUTH_ADD), "nor may he add a way to sign in as someone");
		assertTrue(venue.engine().hasVenueAuthority(GodContexts.of(venue, Odin.AGENT_ID), "users",
			Abilities.ADMIN_USER_CREATE), "as Covia judges his caps");

		AString other = Strings.create(venue.did() + ":u:caps-thor");
		denied(Thor.GOD, "v/ops/user/create", Maps.of(Fields.DID, other));
		assertNull(venue.engine().getVenueState().users().user(other), "Thor registers no one");
	}

	@Test
	void odinKeepsTheVenuesSecretsButNeverReadsOne() throws Exception {
		done(Odin.GOD, "v/ops/secret/set", Maps.of("name", "CAPS_TEST", "value", "not-a-real-key"));
		refused(Odin.GOD, "v/ops/secret/extract", Maps.of("name", "CAPS_TEST"));
		refused(Eir.GOD, "v/ops/secret/delete", Maps.of("name", "CAPS_TEST"));
		assertTrue(String.valueOf(done(Eir.GOD, "v/ops/secret/list", Maps.empty())).contains("CAPS_TEST"),
			"Eir sees the name, and leaves the secret where it is");
		done(Odin.GOD, "v/ops/secret/delete", Maps.of("name", "CAPS_TEST"));
		assertFalse(String.valueOf(done(Eir.GOD, "v/ops/secret/list", Maps.empty())).contains("CAPS_TEST"),
			"Odin removes it");
	}

	@Test
	void odinManagesAdaptersAndTheOthersOnlyThroughTheirBridges() throws Exception {
		assertNotNull(done(Odin.GOD, "v/ops/venue/adapters", Maps.empty()));
		denied(Eir.GOD, "v/ops/venue/adapters", Maps.empty());
		denied(Thor.GOD, "v/ops/venue/adapter/disable", Maps.of("name", "brightside"));
	}

	/** Restart, relaunch and shutdown are the venue's process, which Odin's caps hold and no other god's do. */
	@Test
	void onlyOdinsCapsReachTheProcessAndTheStore() throws Exception {
		for (God god : new God[] { Thor.GOD, Eir.GOD, Njord.GOD }) {
			denied(god, "v/ops/brightside/restart", Maps.empty());
			denied(god, "v/ops/brightside/storage", Maps.empty());
		}
		// The test venue has no app to restart and no store file: past Covia's
		// check, the operation itself declines.
		for (String op : new String[] { "v/ops/brightside/restart", "v/ops/brightside/storage" }) {
			String error = failure(Odin.GOD, op, Maps.empty());
			assertFalse(error.startsWith(Engine.VENUE_AUTHORITY_DENIED), "Odin's caps let him past the check: " + error);
		}
	}

	/** Brightside's process and store operations take the venue's authority, which a user and their own agents lack. */
	@Test
	void noUserNorTheirUnrestrictedAssistantReachesTheProcessOrTheStore() throws Exception {
		String userDID = Identity.of("caps-owner").userDID(venue.did());
		venue.clientAs(userDID).invoke("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "CapsHelper",
			Fields.CONFIG, Maps.of("caps", Config.UNRESTRICTED))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		RequestContext user = venue.engine().context(Strings.create(userDID), Strings.create(userDID));
		RequestContext assistant = venue.engine().context(Strings.create(userDID), Strings.create(userDID + ":g:CapsHelper"));
		for (RequestContext ctx : new RequestContext[] { user, assistant }) {
			for (String op : new String[] { "v/ops/brightside/restart", "v/ops/brightside/relaunch",
					"v/ops/brightside/shutdown", "v/ops/brightside/storage" }) {
				Job job = venue.engine().jobs().invokeOperation(op, Maps.empty(), ctx);
				assertThrows(ExecutionException.class, () -> job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), op);
				assertTrue(job.getErrorMessage().startsWith(Engine.VENUE_AUTHORITY_DENIED), op + ": " + job.getErrorMessage());
			}
		}
	}

	@Test
	void odinsCapsLeaveOutTheGodsTheInboxAndTheLiveStore() throws Exception {
		refused(Odin.GOD, "v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, Thor.AGENT_ID));
		assertFalse(Suspension.deliberate(venue.agentRecord(venue.did(), Thor.AGENT_ID)), "Thor still runs");
		refused(Odin.GOD, "v/ops/covia/write", Maps.of("path", "w/caps-test", "value", "x"));
		assertNull(RT.getIn(venue.engine().jobs().invokeOperation("v/ops/covia/read", Maps.of("path", "w/caps-test"),
			venue.engine().venueContext()).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "value"),
			"nothing written in the venue's workspace");
		denied(Odin.GOD, "v/ops/venue/gc", Maps.empty());

		AString user = Strings.create(Identity.of("caps-sudo").userDID(venue.did()));
		if (venue.engine().getVenueState().users().user(user) == null) {
			venue.operator().invoke("v/ops/user/create", Maps.of(Fields.DID, user)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		refused(Odin.GOD, "v/ops/user/sudo", Maps.of(Fields.DID, user, "operation", "v/ops/covia/write",
			"input", Maps.of("path", "w/caps-test", "value", "x")));
		assertNull(RT.getIn(venue.clientAs(user.toString()).invoke("v/ops/covia/read", Maps.of("path", "w/caps-test"))
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "value"),
			"standing caps never act as a user");
	}

	@Test
	void everyGodReadsTauFilesAndSetup() throws Exception {
		for (God god : Gods.ALL) {
			assertNotNull(done(god, "v/ops/file/roots", Maps.empty()), god.id());
			assertNotNull(done(god, "v/ops/venue/show-config", Maps.empty()), god.id());
		}
	}

	@Test
	void noGodWritesAFile() throws Exception {
		for (God god : Gods.ALL) {
			String name = "caps-" + god.id() + ".txt";
			refused(god, "v/ops/file/write", Maps.of("root", "files", "path", name, "content", "x"));
			assertEquals(CVMBool.FALSE, RT.getIn(done(god, "v/ops/file/stat", Maps.of("root", "files", "path", name)),
				"exists"), god.id() + " wrote nothing");
		}
	}

	private static ACell done(God god, String op, AMap<AString, ACell> input) throws Exception {
		return job(god, op, input).future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** Asserts Covia's venue-administration check refuses {@code god} the call. */
	private static void denied(God god, String op, AMap<AString, ACell> input) throws Exception {
		String error = failure(god, op, input);
		assertTrue(error != null && error.startsWith(Engine.VENUE_AUTHORITY_DENIED), god.id() + " " + op + ": " + error);
	}

	/** Asserts the call fails; the caller checks that nothing changed. */
	private static void refused(God god, String op, AMap<AString, ACell> input) throws Exception {
		failure(god, op, input);
	}

	/** Why the call failed, which it must. */
	private static String failure(God god, String op, AMap<AString, ACell> input) throws Exception {
		Job job = job(god, op, input);
		assertThrows(ExecutionException.class, () -> job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
			god.id() + " " + op);
		return job.getErrorMessage();
	}

	private static Job job(God god, String op, AMap<AString, ACell> input) {
		RequestContext ctx = GodContexts.of(venue, god.id());
		return venue.engine().jobs().invokeOperation(op, input, ctx);
	}
}
