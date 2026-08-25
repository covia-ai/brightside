package covia.brightside.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.brightside.AppConfig;
import covia.brightside.EmbeddedVenue;
import covia.brightside.Identity;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Config;

/** Filesystem skill import: parse SKILL.md, and round-trip into the user's w/skills. */
class FilesystemSkillsTest {

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
	void parsesFrontmatterAndKeepsRawBody() {
		String md = "---\nname: whatever\ndescription: \"Do a thing. Load when X.\"\n---\n\nBody here.\n";
		FilesystemSkills.Parsed p = FilesystemSkills.parse(md, "My Skill");
		assertNotNull(p);
		assertEquals("my-skill", p.name(), "the folder name is canonical and sanitised");
		assertEquals("Do a thing. Load when X.", p.description(), "unquoted from frontmatter");
		assertEquals(md, p.content(), "the raw SKILL.md is kept verbatim as the body");
	}

	@Test
	void requiresADescription() {
		assertNull(FilesystemSkills.parse("---\nname: x\n---\nbody", "x"), "no description → skipped");
		assertNull(FilesystemSkills.parse("no frontmatter at all", "x"), "no frontmatter → skipped");
	}

	@Test
	void sanitisesNames() {
		assertEquals("weekly-report", FilesystemSkills.sanitiseName("Weekly Report"));
		assertEquals("a-b", FilesystemSkills.sanitiseName("--a  b--"));
	}

	@Test
	void importsSkillsIntoUserSkills(@TempDir Path skillsDir) throws Exception {
		Path skill = Files.createDirectories(skillsDir.resolve("weekly-report"));
		Files.writeString(skill.resolve("SKILL.md"),
			"---\nname: weekly-report\ndescription: How I like my weekly report. Load when drafting it.\n---\n\nFive bullets, wins first.\n");

		String userDID = Identity.of("skiller").userDID(venue.did());
		Venue client = venue.clientAs(userDID);
		FilesystemSkills.Result result = FilesystemSkills.sync(client, skillsDir);
		assertTrue(result.loaded().contains("weekly-report"), "the skill imported");
		assertTrue(result.skipped().isEmpty(), "nothing skipped: " + result.skipped());

		// It resolves back from the user's own w/skills as a skill asset.
		ACell value = read(client, "w/skills/weekly-report");
		assertNotNull(value, "written to w/skills");
		assertTrue(str(RT.getIn(value, "description")).contains("weekly report"));
		assertTrue(str(RT.getIn(RT.getIn(value, "content"), "inline")).contains("Five bullets"),
			"the markdown body is stored");
	}

	@Test
	void seedsAReadmeWhenTheFolderIsMissing(@TempDir Path parent) {
		Path dir = parent.resolve("skills"); // does not exist yet
		Venue client = venue.clientAs(Identity.of("seed").userDID(venue.did()));
		FilesystemSkills.sync(client, dir);
		assertTrue(Files.exists(dir.resolve("README.md")), "seeds a README explaining the format");
	}

	private static ACell read(Venue client, String path) throws Exception {
		Job job = client.invoke("v/ops/covia/read", Maps.of("path", path)).get(30, TimeUnit.SECONDS);
		return RT.getIn(job.future().get(30, TimeUnit.SECONDS), "value");
	}

	private static String str(ACell cell) {
		return (cell == null) ? "" : cell.toString();
	}
}
