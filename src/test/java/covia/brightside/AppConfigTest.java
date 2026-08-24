package covia.brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		assertEquals(AppConfig.DEFAULT_TIMEOUT_SECONDS, c.chat().timeoutSeconds());
		assertEquals(AppConfig.DEFAULT_VENUE_NAME, c.venueConfig().get(Fields.NAME).toString());
		assertEquals(home.resolve("venue.etch").toString(), c.venueConfig().get(Config.STORE).toString());
		assertEquals("127.0.0.1", c.venueConfig().get(Config.BIND_ADDRESS).toString());
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
		assertEquals(5, c.chat().timeoutSeconds());
		assertEquals(AppConfig.DEFAULT_AGENT_ID, c.chat().agentId());
		assertEquals(AppConfig.DEFAULT_SYSTEM_PROMPT, c.chat().systemPrompt());
	}

	@Test
	void templateMatchesTheDefaults() {
		AppConfig fromTemplate = AppConfig.parse(AppConfig.DEFAULT_TEMPLATE, home);
		AppConfig fromEmpty = AppConfig.parse("{}", home);
		assertEquals(fromEmpty.theme(), fromTemplate.theme());
		assertEquals(fromEmpty.venueConfig(), fromTemplate.venueConfig());
		assertEquals(fromEmpty.chat(), fromTemplate.chat());
	}

	@Test
	void loadWritesTheTemplateWhenMissing() throws IOException {
		Path file = home.resolve("cfg").resolve("config.json");
		AppConfig c = AppConfig.load(file);
		assertTrue(Files.exists(file));
		assertEquals(AppConfig.DEFAULT_TEMPLATE, Files.readString(file));
		assertEquals(file.getParent(), c.home());
		assertEquals(file.getParent().resolve("venue.etch").toString(), c.venueConfig().get(Config.STORE).toString());
	}

	@Test
	void rejectsNonObjectConfig() {
		assertThrows(IllegalArgumentException.class, () -> AppConfig.parse("[1, 2]", home));
	}
}
