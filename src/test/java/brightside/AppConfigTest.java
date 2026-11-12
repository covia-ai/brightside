package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import convex.core.data.Maps;
import covia.api.Fields;
import covia.venue.Config;

class AppConfigTest {

	@TempDir
	Path home;

	@Test
	void emptyConfigIsAllDefaults() {
		AppConfig c = AppConfig.parse("{}", home);
		assertEquals(AppConfig.DEFAULT_PORT, c.port());
		assertEquals(AppConfig.DEFAULT_THEME, c.theme());
		assertEquals(AppConfig.DEFAULT_AGENT_ID, c.chat().agentId());
		assertEquals(AppConfig.DEFAULT_OPERATION, c.chat().operation());
		assertEquals(AppConfig.DEFAULT_LLM_OPERATION, c.chat().llmOperation());
		assertEquals(AppConfig.DEFAULT_SYSTEM_PROMPT, c.chat().systemPrompt());
		assertEquals(AppConfig.DEFAULT_VENUE_NAME, c.venueConfig().get(Fields.NAME).toString());
		assertEquals(home.resolve("venue.etch").toString(), c.venueConfig().get(Config.STORE).toString());
		assertEquals("127.0.0.1", c.venueConfig().get(Config.BIND_ADDRESS).toString());
		assertEquals(home.resolve("code").toAbsolutePath().normalize().toString(),
			convex.core.lang.RT.getIn(c.venueConfig(), Config.FILE, Config.ROOTS, "code", "path").toString(),
			"the code root, where checkouts live");
		Config venue = new Config(c.venueConfig());
		assertFalse(venue.isPublicAccess(), "anonymous venue access is disabled");
		assertTrue(venue.isMCPAuthRequired(), "MCP requires a bearer-authenticated caller");
		assertTrue(venue.getMaxToolIterations() > new Config(Maps.empty()).getMaxToolIterations(),
			"a turn may make more tool calls than Covia's default allows");
	}

	@Test
	void userKeysReplaceDefaultsKeyForKey() {
		AppConfig c = AppConfig.parse("""
			{ theme: "light",
			  venue: { port: 9999, name: "Test Venue", store: "temp" },
			  chat: { llmOperation: "v/test/ops/llm", timeout: 5 } }
			""", home);
		assertEquals("light", c.theme());
		assertEquals(9999, c.port());
		assertEquals("Test Venue", c.venueConfig().get(Fields.NAME).toString());
		assertEquals("temp", c.venueConfig().get(Config.STORE).toString());
		// defaults the user did not touch survive
		assertEquals("127.0.0.1", c.venueConfig().get(Config.BIND_ADDRESS).toString());
		assertEquals("v/test/ops/llm", c.chat().llmOperation());
		// "timeout" is left over from older configs: a reply has no deadline
		// now, and the stale key is ignored rather than rejected.
		assertEquals(AppConfig.DEFAULT_AGENT_ID, c.chat().agentId());
		assertEquals(AppConfig.DEFAULT_SYSTEM_PROMPT, c.chat().systemPrompt());
	}

	@Test
	void authOptionsPreservePrivateDefaults() {
		for (String auth : new String[] {"{}", "{tokenExpiry:3600}", "{public:{caps:[]}}"}) {
			Config venue = new Config(AppConfig.parse("{venue:{auth:" + auth + "}}", home).venueConfig());
			assertFalse(venue.isPublicAccess(), auth);
			assertTrue(venue.isMCPAuthRequired(), auth);
		}
		Config venue = new Config(AppConfig.parse("{venue:{auth:{tokenExpiry:3600}}}", home).venueConfig());
		assertEquals(3600, venue.getTokenExpiry());
	}

	@Test
	void anonymousAccessStillRequiresAnExplicitOptIn() {
		Config venue = new Config(AppConfig.parse("{venue:{auth:{public:{enabled:true}}}}", home).venueConfig());
		assertTrue(venue.isPublicAccess());
	}

	@Test
	void templateChoosesANewAssistantWithTheSameRuntimeDefaults() {
		AppConfig fromTemplate = AppConfig.parse(AppConfig.DEFAULT_TEMPLATE, home);
		AppConfig fromEmpty = AppConfig.parse("{}", home);
		assertEquals(fromEmpty.theme(), fromTemplate.theme());
		assertEquals(fromEmpty.venueConfig(), fromTemplate.venueConfig());
		assertEquals(BrightSide.APP_NAME, fromTemplate.chat().agentId());
		assertEquals(fromEmpty.chat().operation(), fromTemplate.chat().operation());
		assertEquals(fromEmpty.chat().llmOperation(), fromTemplate.chat().llmOperation());
		assertEquals(AppConfig.parse("{chat:{agentId:'Tau'}}", home).chat(), fromTemplate.chat());
	}

	@Test
	void configuredAssistantIdentitySurvivesProductNaming() {
		AppConfig legacy = AppConfig.parse("{chat:{agentId:'Brightside'}}", home);
		assertEquals(AppConfig.parse("{}", home).chat(), legacy.chat());
		AppConfig custom = AppConfig.parse("{chat:{agentId:'Robin',systemPrompt:'Owner-defined persona'}}", home);
		assertEquals("Robin", custom.chat().agentId());
		assertEquals("Owner-defined persona", custom.chat().systemPrompt());
	}

	@Test
	void loadWritesTheTemplateWhenMissing() throws IOException {
		Path file = home.resolve("cfg").resolve("config.json");
		AppConfig c = AppConfig.load(file);
		assertTrue(Files.exists(file));
		assertEquals(AppConfig.DEFAULT_TEMPLATE, Files.readString(file));
		assertEquals(BrightSide.APP_NAME, c.chat().agentId());
		assertEquals(c.chat(), AppConfig.load(file).chat());
		assertEquals(file.getParent(), c.home());
		assertEquals(file.getParent().resolve("venue.etch").toString(), c.venueConfig().get(Config.STORE).toString());
	}

	@Test
	void loadingAnExistingConfigKeepsItsImplicitAssistant() throws IOException {
		Path file = home.resolve("config.json");
		String original = "{chat:{llmOperation:'v/test/ops/llm'}}";
		Files.writeString(file, original);
		assertEquals("Brightside", AppConfig.load(file).chat().agentId());
		assertEquals(original, Files.readString(file));
	}

	@Test
	void rejectsNonObjectConfig() {
		assertThrows(IllegalArgumentException.class, () -> AppConfig.parse("[1, 2]", home));
	}

}
