package covia.brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Config;

/**
 * BrightSide configuration: a JSON5 file (default {@code ~/.brightside/config.json})
 * with three sections.
 *
 * <ul>
 *   <li>{@code theme} — {@code "dark"} (default) or {@code "light"}</li>
 *   <li>{@code venue} — a Covia venue config map. Keys given here replace
 *       BrightSide's defaults key-for-key (a shallow merge), so any venue
 *       option the Covia runtime understands can be set without BrightSide
 *       knowing about it.</li>
 *   <li>{@code chat} — the agent the chat window talks to.</li>
 * </ul>
 *
 * <p>Everything the file omits falls back to a default, so an empty object is
 * a complete configuration. {@link #load} writes {@link #DEFAULT_TEMPLATE} when
 * the file does not exist yet, so the user has something commented to edit.
 */
public final class AppConfig {

	private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

	/** Default data directory: config, venue store, identity key and logs. */
	public static final Path HOME = Path.of(System.getProperty("user.home"), ".brightside");

	/** Default configuration file. */
	public static final Path DEFAULT_FILE = HOME.resolve("config.json");

	public static final int DEFAULT_PORT = 8085;
	public static final String DEFAULT_VENUE_NAME = "Brightside";
	public static final String DEFAULT_THEME = "dark";

	public static final String DEFAULT_AGENT_ID = "brightside";
	/** Plain persona chat transition — the recommended choice for a chat agent. */
	public static final String DEFAULT_OPERATION = "v/ops/llmagent/chat";
	/** The venue's own default model operation (see AbstractLLMAdapter). */
	public static final String DEFAULT_LLM_OPERATION = "v/models/anthropic/claude-sonnet-5";
	/** Test LLM that echoes the last user message — an offline chat backend. */
	public static final String ECHO_LLM_OPERATION = "v/test/ops/llm";
	public static final String DEFAULT_SYSTEM_PROMPT =
		"You are Brightside, a private personal AI assistant running on the user's own computer, "
		+ "powered by the Covia Grid (keep that under the hood unless they ask). Be warm, concise and "
		+ "genuinely helpful, and use the user's name naturally rather than in every message. Follow "
		+ "your loaded skills, and use your memory to recall and quietly record useful things about the "
		+ "user across conversations.";
	public static final long DEFAULT_TIMEOUT_SECONDS = 120;

	/** Chat settings: which agent the window talks to and how it is configured. */
	public record Chat(String agentId, String operation, String llmOperation,
			String systemPrompt, long timeoutSeconds) {
	}

	/** Written to a fresh config file so users have a commented starting point. */
	public static final String DEFAULT_TEMPLATE = """
		{
			// Brightside configuration — edit and restart Brightside to apply.
			// Every key is optional; delete this file to restore the defaults.
			// Your chosen name lives separately in identity.json (not here).

			// Look and feel: "dark" or "light"
			"theme": "dark",

			// The embedded Covia venue. Any Covia venue config key is accepted here
			// and replaces Brightside's default for that key. Defaults: bound to
			// 127.0.0.1, persistent store at ~/.brightside/venue.etch (identity in
			// venue.key next to it), users auto-created, MCP endpoint enabled.
			"venue": {
				"name": "Brightside",
				"port": 8085
				// The model needs an API key. Either export ANTHROPIC_API_KEY before
				// launching, or provision it into the venue's shared secret store here
				// (loopback-only venue — do not commit this file):
				// ,"secrets": { "public": { "ANTHROPIC_API_KEY": "sk-ant-..." } }
			},

			// The agent the chat window talks to. Created on first use at
			// <your DID>/g/<agentId>; the settings below are re-applied on startup.
			"chat": {
				"agentId": "brightside",
				"operation": "v/ops/llmagent/chat",
				// Model operation used for replies. Needs the provider's API key in the
				// environment (e.g. ANTHROPIC_API_KEY) or in the venue's secret store.
				// Use "v/test/ops/llm" for an offline echo bot.
				"llmOperation": "v/models/anthropic/claude-sonnet-5",
				// How the assistant should behave. Leave it out to use Brightside's
				// default persona (a warm, private personal assistant).
				// "systemPrompt": "You are Brightside, ...",
				// Seconds to wait for a reply before giving up
				"timeout": 120
			}
		}
		""";

	private final Path home;
	private final String theme;
	private final AMap<AString, ACell> venue;
	private final Chat chat;

	private AppConfig(AMap<AString, ACell> raw, Path home) {
		this.home = home;
		this.theme = string(raw, "theme", DEFAULT_THEME);
		this.venue = merge(defaultVenue(home), RT.ensureMap(raw.get(Strings.create("venue"))));
		AMap<AString, ACell> c = RT.ensureMap(raw.get(Strings.create("chat")));
		this.chat = new Chat(
			string(c, "agentId", DEFAULT_AGENT_ID),
			string(c, "operation", DEFAULT_OPERATION),
			string(c, "llmOperation", DEFAULT_LLM_OPERATION),
			string(c, "systemPrompt", DEFAULT_SYSTEM_PROMPT),
			longValue(c, "timeout", DEFAULT_TIMEOUT_SECONDS));
	}

	/**
	 * Loads the configuration at {@code file}, writing the default template
	 * first if it does not exist. The file's directory becomes the data home
	 * (venue store, identity key).
	 */
	public static AppConfig load(Path file) throws IOException {
		Path abs = file.toAbsolutePath().normalize();
		Path home = abs.getParent();
		if (!Files.exists(abs)) {
			Files.createDirectories(home);
			Files.writeString(abs, DEFAULT_TEMPLATE);
			log.info("Wrote default configuration to {}", abs);
		}
		return parse(Files.readString(abs), home);
	}

	/** Parses JSON5 configuration text; {@code home} is the data directory. */
	public static AppConfig parse(String json5, Path home) {
		ACell parsed = JSON.parseJSON5(json5);
		AMap<AString, ACell> raw = RT.ensureMap(parsed);
		if (raw == null) throw new IllegalArgumentException("Configuration must be a JSON object");
		return new AppConfig(raw, home.toAbsolutePath().normalize());
	}

	/** BrightSide's venue defaults for a given data directory. */
	public static AMap<AString, ACell> defaultVenue(Path home) {
		return Maps.of(
			Fields.NAME, DEFAULT_VENUE_NAME,
			Fields.HOSTNAME, "localhost",
			Fields.PORT, DEFAULT_PORT,
			// Local desktop venue: never listen beyond this machine by default.
			Config.BIND_ADDRESS, "127.0.0.1",
			// Persistent Etch store; the venue generates and saves an identity
			// seed to venue.key beside it on first launch (stable DID).
			Config.STORE, home.resolve("venue.etch").toString(),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ALLOW_PRIVATE_NETWORK, true,
			// MCP endpoint so local agent tooling can connect to the venue.
			Fields.MCP, Maps.of());
	}

	/** Shallow merge: every key in {@code over} replaces the same key in {@code base}. */
	static AMap<AString, ACell> merge(AMap<AString, ACell> base, AMap<AString, ACell> over) {
		if (over == null) return base;
		AMap<AString, ACell> result = base;
		long n = over.count();
		for (long i = 0; i < n; i++) {
			MapEntry<AString, ACell> e = over.entryAt(i);
			result = result.assoc(e.getKey(), e.getValue());
		}
		return result;
	}

	private static String string(AMap<AString, ACell> m, String key, String dflt) {
		if (m == null) return dflt;
		ACell v = m.get(Strings.create(key));
		if (v == null) return dflt;
		return (v instanceof AString s) ? s.toString() : v.toString();
	}

	private static long longValue(AMap<AString, ACell> m, String key, long dflt) {
		if (m == null) return dflt;
		ACell v = m.get(Strings.create(key));
		return (v instanceof CVMLong l) ? l.longValue() : dflt;
	}

	/** Data directory (the config file's directory). */
	public Path home() {
		return home;
	}

	/** {@code "dark"} or {@code "light"}. */
	public String theme() {
		return theme;
	}

	/** The complete venue config to launch: defaults with the user's keys applied. */
	public AMap<AString, ACell> venueConfig() {
		return venue;
	}

	/** HTTP port the venue will listen on. */
	public int port() {
		ACell p = venue.get(Fields.PORT);
		return (p instanceof CVMLong l) ? (int) l.longValue() : DEFAULT_PORT;
	}

	public Chat chat() {
		return chat;
	}
}
