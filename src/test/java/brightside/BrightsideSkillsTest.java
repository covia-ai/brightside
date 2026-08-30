package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.BrightsideSkillsAdapter;
import brightside.EmbeddedVenue;
import brightside.Identity;
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

/**
 * Mechanism test for the shipped skill library: every skill Brightside ships
 * must actually work when an agent loads it.
 *
 * <p>Driven entirely by {@link BrightsideSkillsAdapter#SHIPPED} and by what
 * Covia's own resolver reports for each skill, so adding, renaming or
 * re-tooling a skill needs no change here — a skill that declares an operation
 * the venue does not have, reveals a child that is not a skill, or names an
 * empty skillset fails by name.</p>
 *
 * <p>Two real mechanisms are exercised. {@code skills:read} (the resolver, as
 * the owner) says what a skill declares; {@code agent:context} on an agent that
 * pins the skill shows the palette the model would be offered — every declared
 * operation present and nothing unavailable. Nothing here inspects prose.</p>
 */
class BrightsideSkillsTest {

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
		userDID = Identity.of("skills-test").userDID(venue.did());
		client = venue.clientAs(userDID);
	}

	@AfterAll
	static void shutdown() {
		if (venue != null) venue.close();
	}

	@TestFactory
	List<DynamicTest> everyShippedSkillWiresUp() {
		List<DynamicTest> tests = new ArrayList<>();
		for (String path : BrightsideSkillsAdapter.SHIPPED) {
			String name = path.substring(path.lastIndexOf('/') + 1);
			// Label by the path under the skillset: the same resource may be
			// installed at more than one address (a shared child).
			String rel = relative(path);
			tests.add(DynamicTest.dynamicTest(rel + ": declarations resolve",
				() -> declarationsResolve(path, name)));
			tests.add(DynamicTest.dynamicTest(rel + ": loaded palette offers every declared tool",
				() -> loadedPaletteIsComplete(path, name)));
		}
		// Guard against the per-skill checks going hollow: the resolver must be
		// reporting declarations at all (a renamed field would otherwise make
		// every loop above pass vacuously).
		tests.add(DynamicTest.dynamicTest("the resolver reports declared tools and children",
			BrightsideSkillsTest::resolverReportsDeclarations));
		return tests;
	}

	private static void resolverReportsDeclarations() throws Exception {
		int tools = 0;
		int children = 0;
		for (String path : BrightsideSkillsAdapter.SHIPPED) {
			ACell skill = readSkill(path, path + " is not a loadable skill");
			tools += strings(RT.getIn(skill, "tools")).size();
			children += strings(RT.getIn(skill, "skills")).size()
				+ strings(RT.getIn(skill, "skillsets")).size();
		}
		assertTrue(tools > 0, "no shipped skill reports any tool — is the resolver's tools field still 'tools'?");
		assertTrue(children > 0, "no shipped skill reports any child — is the resolver's skills field still 'skills'?");
	}

	/**
	 * What the resolver reports for the skill holds up: each declared tool is
	 * an operation on this venue, each revealed child is a skill, and each
	 * revealed skillset lists at least one skill.
	 */
	private static void declarationsResolve(String path, String name) throws Exception {
		ACell skill = readSkill(path, name + " is not a loadable skill");
		assertEquals(name, str(RT.getIn(skill, "name")), "resolved name matches the path segment of " + path);
		assertFalse(str(RT.getIn(skill, "description")).isBlank(), path + " has no index line");
		for (String op : strings(RT.getIn(skill, "tools"))) {
			ACell asset = venue.resolve(userDID, op);
			assertNotNull(asset, name + " declares a tool that does not resolve: " + op);
			assertTrue(RT.getIn(asset, Fields.OPERATION) instanceof AMap,
				name + " declares a tool that is not an operation: " + op);
		}
		for (String child : strings(RT.getIn(skill, "skills"))) {
			readSkill(child, name + " reveals a child that is not a skill: " + child);
		}
		for (String skillset : strings(RT.getIn(skill, "skillsets"))) {
			ACell listed = run("v/ops/skills/list", Maps.of("skillset", skillset));
			assertTrue(listed instanceof AMap<?, ?> m && m.count() > 0,
				name + " reveals a skillset with no skills: " + skillset);
		}
	}

	/**
	 * The palette an agent actually gets: an agent pinning the skill through
	 * {@code config.loads} is offered every operation the skill declares, and
	 * nothing it declares is reported unavailable.
	 */
	private static void loadedPaletteIsComplete(String path, String name) throws Exception {
		String agentId = "skill-" + relative(path).replace('/', '-');
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
			"llmOperation", AppConfig.ECHO_LLM_OPERATION,
			"systemPrompt", "Skill wiring test.",
			"defaultTools", false,
			Fields.LOADS, Maps.of(path, Maps.of("skill", true, "budget", 8000L, "label", name)));
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config));

		ACell context = run("v/ops/agent/context", Maps.of(Fields.AGENT_ID, agentId));
		assertNotNull(context, "agent:context assembles for " + agentId);
		ACell palette = RT.getIn(context, "palette");

		List<String> unavailable = new ArrayList<>();
		if (RT.getIn(palette, "unavailable") instanceof AVector<?> un) {
			for (long i = 0; i < un.count(); i++) unavailable.add(String.valueOf(un.get(i)));
		}
		assertTrue(unavailable.isEmpty(), name + " loads with unavailable tools: " + unavailable);

		Set<String> offered = new HashSet<>();
		if (RT.getIn(palette, "tools") instanceof AVector<?> ts) {
			for (long i = 0; i < ts.count(); i++) {
				String op = str(RT.getIn((ACell) ts.get(i), Fields.OPERATION));
				if (!op.isEmpty()) offered.add(op);
			}
		}
		ACell skill = readSkill(path, name + " is not a loadable skill");
		for (String op : strings(RT.getIn(skill, "tools"))) {
			assertTrue(offered.contains(op),
				name + " declares " + op + " but the loaded palette offers only " + offered);
		}
	}

	/** The skill's path below the Brightside skillset, e.g. {@code convex/accounts}. */
	private static String relative(String path) {
		return path.substring(BrightsideSkillsAdapter.SKILLSET.length() + 1);
	}

	/** {@code skills:read} for a ref that must be a skill; fails with {@code why} otherwise. */
	private static ACell readSkill(String ref, String why) throws Exception {
		try {
			ACell skill = run("v/ops/skills/read", Maps.of("skill", ref));
			assertNotNull(skill, why);
			return skill;
		} catch (Exception e) {
			fail(why + " (" + e.getMessage() + ")");
			return null;
		}
	}

	private static ACell run(String operation, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(operation, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s == null) ? "" : s.toString();
	}

	private static List<String> strings(ACell cell) {
		List<String> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				String s = str((ACell) v.get(i));
				if (!s.isEmpty()) out.add(s);
			}
		}
		return out;
	}
}
