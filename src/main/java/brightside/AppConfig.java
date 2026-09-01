package brightside;

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
 *   <li>{@code theme} — {@code "dark"} (default) or {@code "light"}, the mode to start
 *       in; the mode and themes chosen in Settings → Theme are kept in
 *       {@code prefs.properties} and take precedence</li>
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

	public static final String DEFAULT_AGENT_ID = "Brightside";
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
		+ "user across conversations. Messages may also reach you from connected channels such as "
		+ "Discord: those carry via metadata saying where they came from and who wrote them, and your "
		+ "reply is delivered back to that same place. In a public channel everyone can read it — write "
		+ "for that audience, and keep anything meant only for the user out of it (raise it with them "
		+ "privately instead).";
	/**
	 * Chat settings: which agent the window talks to and how it is configured.
	 * A reply has no deadline: a turn takes as long as its model and tool calls
	 * take, each of which the venue bounds itself, and the user can stop waiting
	 * from the chat.
	 */
	public record Chat(String agentId, String operation, String llmOperation, String systemPrompt) {
	}

	/** Written to a fresh config file so users have a commented starting point. */
	public static final String DEFAULT_TEMPLATE = """
		{
			// Brightside configuration — edit and restart Brightside to apply.
			// Every key is optional; delete this file to restore the defaults.
			// Your chosen name lives separately in identity.json (not here).

			// Look and feel to start in: "dark" or "light". The mode and themes chosen
			// in the app (Settings → Theme) are remembered in prefs.properties and win over this.
			"theme": "dark",

			// The embedded Covia venue. Any Covia venue config key is accepted here
			// and replaces Brightside's default for that key. Defaults: bound to
			// 127.0.0.1, encrypted persistent store at ~/.brightside/venue.etch,
			// anonymous access disabled, users auto-created, MCP endpoint enabled,
			// plus confined files (writable) and logs (read-only) roots.
			"venue": {
				"name": "Brightside",
				"port": 8085
				// Model API keys do NOT belong in this file. Enter them in the app
				// (Settings → Model) — they are stored encrypted in the vault
				// and provisioned into the encrypted venue store at launch. Exporting
				// e.g. ANTHROPIC_API_KEY before launching also works.
			},

			// The agent the chat window talks to. Created on first use at
			// <your DID>/g/<agentId>; the settings below are re-applied on startup.
			"chat": {
				"agentId": "Brightside",
				"operation": "v/ops/llmagent/chat",
				// Model operation used for replies. The model chosen in the app
				// (onboarding or Settings → Model) is kept in model.txt
				// beside this file and takes precedence over this key.
				// Use "v/test/ops/llm" for an offline echo bot.
				"llmOperation": "v/models/anthropic/claude-sonnet-5"
				// How the assistant should behave. Leave it out to use Brightside's
				// default persona (a warm, private personal assistant):
				// ,"systemPrompt": "You are Brightside, ..."
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
		// The chosen model persists in a small side-file (model.txt), so changing
		// it in onboarding/Settings never rewrites the commented config.json.
		String model = readModel(home);
		if (model == null) model = string(c, "llmOperation", DEFAULT_LLM_OPERATION);
		this.chat = new Chat(
			string(c, "agentId", DEFAULT_AGENT_ID),
			string(c, "operation", DEFAULT_OPERATION),
			model,
			string(c, "systemPrompt", DEFAULT_SYSTEM_PROMPT));
	}

	/** File holding the chosen model operation, so it survives restarts without a config rewrite. */
	static final String MODEL_FILE = "model.txt";

	/** Persists the chosen model operation ({@code v/models/<provider>/<id>}). Best-effort. */
	public void persistModel(String llmOperation) {
		try {
			Files.createDirectories(home);
			Files.writeString(home.resolve(MODEL_FILE), llmOperation);
		} catch (IOException e) {
			log.warn("Could not save the chosen model", e);
		}
	}

	private static String readModel(Path home) {
		try {
			Path f = home.resolve(MODEL_FILE);
			if (Files.isRegularFile(f)) {
				String s = Files.readString(f).trim();
				if (!s.isEmpty()) return s;
			}
		} catch (IOException ignored) {
			// fall back to config.json / default
		}
		return null;
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
		Path files = ensureDirectory(home.resolve("files"));
		Path logs = ensureDirectory(home.resolve("logs"));
		return Maps.of(
			Fields.NAME, DEFAULT_VENUE_NAME,
			Fields.HOSTNAME, "localhost",
			Fields.PORT, DEFAULT_PORT,
			// Local desktop venue: never listen beyond this machine by default.
			Config.BIND_ADDRESS, "127.0.0.1",
			// Persistent Etch store. Vault injects its encryption key and identity
			// seed in memory before launch; neither is written here in plaintext.
			Config.STORE, home.resolve("venue.etch").toString(),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			// This is a private personal venue: HTTP and MCP require authentication.
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)),
			Config.ALLOW_PRIVATE_NETWORK, true,
			// Confined host-file roots: a durable area Brightside may manage and a
			// server-enforced read-only view of its operational logs.
			Config.FILE, Maps.of(Config.ROOTS, Maps.of(
				"files", Maps.of(
					"path", files.toString(),
					"description", "Brightside-managed local files"),
				"logs", Maps.of(
					"path", logs.toString(),
					Config.READ_ONLY, true,
					"description", "Brightside operational logs (read-only)"))),
			// MCP endpoint so local agent tooling can connect to the venue.
			Fields.MCP, Maps.of());
	}

	/** Creates an app-owned file root without making configuration failure fatal. */
	private static Path ensureDirectory(Path path) {
		Path normal = path.toAbsolutePath().normalize();
		try {
			return Files.createDirectories(normal);
		} catch (IOException e) {
			// FileAdapter will skip a missing root and report it in the venue log.
			log.warn("Could not create Brightside file directory {}", normal, e);
			return normal;
		}
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

	/** Data directory (the config file's directory). */
	public Path home() {
		return home;
	}

	/**
	 * Where the user drops filesystem skills (agentskills.io {@code SKILL.md}
	 * folders or single {@code .md} files); imported into the agent's own
	 * {@code w/skills} on start. Defaults to {@code <home>/skills}.
	 */
	public Path skillsDir() {
		return home.resolve("skills");
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
