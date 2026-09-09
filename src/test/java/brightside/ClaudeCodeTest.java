package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
 * Claude Code is part of the embedded venue: the adapter and its skill are
 * on it, a project declared in config.json becomes the owner's once the
 * identity is bound, and the owner's turn runs the configured command in
 * that directory and comes back as the result. The command is a stand-in
 * for the CLI; the real one runs only with {@code -Dbrightside.tests.claude=true}.
 */
class ClaudeCodeTest {

	@TempDir
	static Path home;

	private static Path project;
	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() throws Exception {
		project = Files.createDirectories(home.resolve("project"));
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port))
			.assoc(Config.ADAPTERS, Maps.of(ClaudeCode.ADAPTER, fakeConfig(Maps.of("scratch", project.toString()))));
		venue = EmbeddedVenue.launch(config);
		userDID = Identity.of("owner").userDID(venue.did());
		user = venue.clientAs(userDID);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	/** The adapter block with the stand-in as the command and the given projects. */
	private static AMap<AString, ACell> fakeConfig(AMap<AString, ACell> projects) throws Exception {
		return Maps.of(
			"command", RT.cvm(FakeClaudeCli.command()),
			"idleSeconds", 0,
			"projects", projects);
	}

	@Test
	void theAdapterAndItsSkillAreOnTheVenue() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : new String[] { "v/ops/claudecode/run", "v/ops/claudecode/session", "v/ops/claudecode/sessions",
			"v/ops/claudecode/stop", "v/ops/claudecode/projects" }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(op), ctx), "op present: " + op);
		}
		assertNotNull(venue.engine().resolvePath(Strings.create("v/skills/adapters/claudecode"), ctx),
			"the module's skill is installed for the assistant to discover");
	}

	@Test
	void ownedDefaultsTheUserOfEveryProjectThatNamesNone() {
		AMap<AString, ACell> projects = Maps.of(
			"plain", "/some/where",
			"open", Maps.of("path", "/else/where", "description", "no user"),
			"theirs", Maps.of("path", "/their/place", "user", "did:test:someone"));
		AMap<AString, ACell> cfg = Maps.of("command", "claude", "projects", projects);
		AMap<AString, ACell> owned = ClaudeCode.owned(cfg, "did:test:owner");
		assertEquals(Strings.create("did:test:owner"), RT.getIn(owned, "projects", "plain", "user"));
		assertEquals(Strings.create("/some/where"), RT.getIn(owned, "projects", "plain", "path"));
		assertEquals(Strings.create("did:test:owner"), RT.getIn(owned, "projects", "open", "user"));
		assertEquals(Strings.create("no user"), RT.getIn(owned, "projects", "open", "description"));
		assertEquals(Strings.create("did:test:someone"), RT.getIn(owned, "projects", "theirs", "user"));
		assertEquals(Strings.create("claude"), RT.getIn(owned, "command"), "other settings pass through untouched");
		assertEquals(Maps.empty(), ClaudeCode.owned(Maps.empty(), "did:test:owner"), "nothing declared, nothing added");
		assertEquals(owned, ClaudeCode.owned(owned, "did:test:owner"), "idempotent");
	}

	@Test
	void brightsidesOwnSourceIsAProjectByDefault() throws Exception {
		Path dir = ClaudeCode.checkoutDir(home);
		AMap<AString, ACell> cfg = ClaudeCode.withDefaultProject(Maps.of("command", "claude"), home);
		assertTrue(Files.isDirectory(dir), "created empty, for the assistant to clone into");
		assertEquals(Strings.create(dir.toString()), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "path"));
		assertEquals(Strings.create("auto"), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "permissionMode"),
			"works without asking in its own checkout");
		String allowed = String.valueOf(RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "allowedTools"));
		for (String rule : new String[] { "Edit", "Write", "Bash(git:*)", "Bash(mvn:*)" }) {
			assertTrue(allowed.contains(rule), "the developer tools are allowed: " + rule);
		}
		for (String rule : new String[] { "Bash(curl:*)", "Bash(rm:*)", "Bash(ssh:*)" }) {
			assertFalse(allowed.contains(rule), "left to Claude Code's own checks: " + rule);
		}
		assertEquals(Strings.create("0"), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "env", "GIT_TERMINAL_PROMPT"),
			"headless git fails fast instead of waiting for a credential prompt");
		assertTrue(String.valueOf(RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "description")).contains(ClaudeCode.REPOSITORY),
			"the description says where to clone from");
		assertEquals(Strings.create("claude"), RT.getIn(cfg, "command"), "other settings pass through");
		assertEquals(cfg, ClaudeCode.withDefaultProject(cfg, home), "declared once");

		// A project of that name declared by the owner wins.
		AMap<AString, ACell> mine = Maps.of("projects", Maps.of(ClaudeCode.PROJECT, "/my/checkout"));
		assertEquals(mine, ClaudeCode.withDefaultProject(mine, home));
		assertEquals(mine, ClaudeCode.withDefaultProject(mine, null), "no home, no default");
	}

	@Test
	void theOwnersProjectRunsAsTheOwner() throws Exception {
		// As declared, the project is the venue's: the owner cannot use it.
		assertNull(project(user, "scratch"), "not the owner's until the identity is bound");

		ClaudeCode.own(venue, userDID, home);
		ACell scratch = project(user, "scratch");
		assertNotNull(scratch, "the owner's after binding");
		assertEquals(Strings.create(userDID), RT.getIn(scratch, "user"));
		assertEquals(Strings.create(project.toAbsolutePath().normalize().toString()), RT.getIn(scratch, "path"));
		ACell own = project(user, ClaudeCode.PROJECT);
		assertNotNull(own, "Brightside's own source is the owner's project too");
		assertEquals(Strings.create(ClaudeCode.checkoutDir(home).toString()), RT.getIn(own, "path"));

		// Another user on the venue sees nothing and cannot run there.
		Venue other = venue.clientAs(Identity.of("someone-else").userDID(venue.did()));
		assertNull(project(other, "scratch"));
		assertThrows(Exception.class, () -> run(other, Maps.of("project", "scratch", "prompt", "cwd")));

		// A turn: the configured command, in the project directory, as one job.
		long jobsBefore = RecordedJobs.of(venue, userDID);
		AMap<AString, ACell> res = run(user, Maps.of("project", "scratch", "prompt", "cwd"));
		assertEquals("cwd: " + project.toAbsolutePath().normalize(), str(res, "result"));
		assertNotNull(res.get(Fields.SESSION), "the session id comes back so the conversation can continue");
		assertEquals(jobsBefore + 1, RecordedJobs.of(venue, userDID), "a run is an action: one job record");

		// Continuing the conversation names the session; the same process answers.
		AMap<AString, ACell> again = run(user, Maps.of("project", "scratch", "prompt", "hello again", "session", res.get(Fields.SESSION)));
		assertEquals("echo: hello again", str(again, "result"));
		assertEquals(res.get(Fields.SESSION), again.get(Fields.SESSION));

		// Binding again changes nothing (already the owner's).
		ClaudeCode.own(venue, userDID, home);
		assertNotNull(project(user, "scratch"));
	}

	/**
	 * The real CLI, as installed on this machine, in a scratch directory:
	 * one bounded turn on a cheap model, so the answer proves the whole path
	 * (process, protocol, project options, result) rather than any of
	 * Claude's abilities. Headless, it can only edit because the project
	 * allows it.
	 */
	@Test
	@EnabledIfSystemProperty(named = "brightside.tests.claude", matches = "true",
		disabledReason = "runs the real claude CLI (needs it installed and signed in); -Dbrightside.tests.claude=true")
	void theRealClaudeCodeAnswers() throws Exception {
		Path real = Files.createDirectories(home.resolve("real"));
		venue.engine().configureAdapter(ClaudeCode.ADAPTER, Maps.of("idleSeconds", 0,
			"projects", Maps.of("real", Maps.of("path", real.toString(), "user", userDID,
				"options", Maps.of("permissionMode", "acceptEdits")))));
		try {
			Job job = user.invoke("v/ops/claudecode/run", Maps.of(
				"project", "real",
				"prompt", "Create a file named pong.txt in the current directory containing the word pong, "
					+ "then reply with exactly the single word: pong",
				"model", "haiku",
				"maxTurns", 3)).get(30, TimeUnit.SECONDS);
			AMap<AString, ACell> res = RT.castMap(job.future().get(180, TimeUnit.SECONDS));
			String result = str(res, "result");
			assertNotNull(result, "a result");
			assertTrue(result.toLowerCase().contains("pong"), "answered: " + result);
			assertNotNull(res.get(Fields.SESSION));
			assertTrue(Files.exists(real.resolve("pong.txt")), "the project's options let it edit: " + res);
		} finally {
			venue.engine().configureAdapter(ClaudeCode.ADAPTER,
				fakeConfig(Maps.of("scratch", project.toString())));
		}
	}

	/**
	 * The self-improvement path, for real: the default {@code brightside}
	 * project starts as an empty directory under the Brightside home, and
	 * Claude Code, allowed git there, clones the repository into it and reads
	 * its rulebook. Network and a signed-in CLI required, hence gated.
	 */
	@Test
	@EnabledIfSystemProperty(named = "brightside.tests.claude", matches = "true",
		disabledReason = "clones Brightside from GitHub with the real claude CLI; -Dbrightside.tests.claude=true")
	void theRealClaudeCodeClonesBrightsideIntoItsOwnProject() throws Exception {
		Path checkout = ClaudeCode.checkoutDir(home);
		venue.engine().configureAdapter(ClaudeCode.ADAPTER,
			ClaudeCode.owned(ClaudeCode.withDefaultProject(Maps.of("idleSeconds", 0), home), userDID));
		try {
			assertTrue(Files.isDirectory(checkout) && Files.list(checkout).findAny().isEmpty(), "starts empty");
			Job job = user.invoke("v/ops/claudecode/run", Maps.of(
				"project", ClaudeCode.PROJECT,
				"prompt", "This directory is empty. Run exactly: git clone " + ClaudeCode.REPOSITORY + " . "
					+ "Then reply with exactly the first line of the file AGENTS.md and nothing else.",
				"model", "haiku",
				"maxTurns", 6)).get(30, TimeUnit.SECONDS);
			AMap<AString, ACell> res = RT.castMap(job.future().get(300, TimeUnit.SECONDS));
			String result = str(res, "result");
			// The model sometimes drops the trailing dot and clones into a subfolder; either way it cloned here.
			assertTrue(Files.isRegularFile(checkout.resolve("AGENTS.md"))
				|| Files.isRegularFile(checkout.resolve(ClaudeCode.PROJECT).resolve("AGENTS.md")), "cloned into the project: " + res);
			assertNotNull(result);
			assertTrue(result.contains("Brightside"), "read the rulebook: " + result);
		} finally {
			venue.engine().configureAdapter(ClaudeCode.ADAPTER,
				fakeConfig(Maps.of("scratch", project.toString())));
		}
	}

	private static AMap<AString, ACell> run(Venue client, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke("v/ops/claudecode/run", input).get(30, TimeUnit.SECONDS);
		return RT.castMap(job.future().get(60, TimeUnit.SECONDS));
	}

	/** The named project as the adapter lists it for this client, or null when it is not theirs. */
	private static ACell project(Venue client, String name) throws Exception {
		ACell out = client.run("v/ops/claudecode/projects", Maps.empty()).get(30, TimeUnit.SECONDS);
		if (!(RT.getIn(out, "projects") instanceof AVector<?> list)) return null;
		for (long i = 0; i < list.count(); i++) {
			ACell p = (ACell) list.get(i);
			if (Strings.create(name).equals(RT.getIn(p, "name"))) return p;
		}
		return null;
	}

	private static String str(AMap<AString, ACell> m, String key) {
		ACell v = (m == null) ? null : m.get(Strings.create(key));
		return (v == null) ? null : v.toString();
	}
}
