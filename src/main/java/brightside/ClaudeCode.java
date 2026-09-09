package brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.claudecode.ClaudeCodeAdapter;
import covia.venue.Engine;

/**
 * Claude Code as one of the assistant's abilities: Covia's {@code claudecode}
 * adapter, running in Brightside's venue, drives the {@code claude} CLI already
 * installed on this computer in the project directories the owner declares
 * under {@code venue.adapters.claudecode.projects} in {@code config.json}.
 *
 * <p>Brightside adds two things to the adapter's own rules, both applied by
 * {@link #own} once the venue is up and the owner's identity is bound to it:</p>
 * <ul>
 *   <li><b>A project belongs to the owner</b> unless it names another user.
 *       On a shared venue the adapter defaults a project to the venue
 *       identity, which only the operator's agents may use; here the venue is
 *       the owner's own and the assistant acts as their user.</li>
 *   <li><b>Brightside's own source is a project by default</b>: {@value #PROJECT}
 *       at {@code <home>/code/brightside}, a directory Brightside creates empty
 *       for the assistant to clone {@value #REPOSITORY} into, where Claude Code
 *       works in its {@code auto} permission mode with the everyday developer
 *       tools allowed ({@link #DEVELOPER_TOOLS}): headless, nobody can answer
 *       a prompt, so building, testing, git and editing go through without
 *       one, and Claude Code's own safety checks judge anything else —
 *       network transfers, deletions, the unusual. That is how the assistant
 *       improves Brightside itself. The owner keeps the name by
 *       declaring a {@value #PROJECT} project of their own (a developer's
 *       checkout, say), which replaces the default.</li>
 * </ul>
 *
 * <p>Naming any other directory is still the owner's decision, made in the
 * file. The {@code code} directory is also a file root ({@link AppConfig}), so
 * the assistant can read a checkout directly.</p>
 */
public final class ClaudeCode {

	private static final Logger log = LoggerFactory.getLogger(ClaudeCode.class);

	/** The adapter's name, and its block under {@code venue.adapters}. */
	public static final String ADAPTER = ClaudeCodeAdapter.NAME;

	/** The default project: Brightside's own source checkout. */
	public static final String PROJECT = "brightside";
	/** Where the assistant clones Brightside's source from. */
	public static final String REPOSITORY = "https://github.com/covia-ai/brightside";
	/** The checkouts directory under the Brightside home — also the {@code code} file root. */
	public static final String CODE_DIR = "code";

	/**
	 * What a coding agent needs without asking, as Claude Code allow rules: its
	 * own file, search and sub-agent tools, and the shell commands of building,
	 * testing, package management, version control and looking around a
	 * checkout, across the common languages. The list is deliberately broad:
	 * an agent stopped for permission it cannot be given is no developer tool.
	 * Deliberately absent — so that Claude Code's own safety checks judge them
	 * — are the ways to move data out ({@code curl}, {@code wget}, {@code ssh},
	 * {@code scp}, fetching a URL, environment dumps), to destroy ({@code rm},
	 * {@code rmdir}, {@code chmod}, {@code kill}) and to wrap a command in
	 * another shell; git and the build tools can still do damage, which is
	 * what the assistant's git skill is for.
	 *
	 * <p>The rules address Claude Code's Bash tool. Its PowerShell tool (on
	 * Windows) accepts no per-command rules in the current CLI — only the whole
	 * tool — and allowing that would let any command round the checks, so
	 * PowerShell commands are left to them; Claude Code reaches for Bash
	 * first.</p>
	 */
	public static final List<String> DEVELOPER_TOOLS = List.of(
		"Read", "Glob", "Grep", "Edit", "Write", "MultiEdit", "NotebookEdit", "Task", "Agent", "TodoWrite", "WebSearch",
		// version control
		"Bash(git:*)", "Bash(gh:*)", "Bash(patch:*)",
		// build and test, by ecosystem
		"Bash(mvn:*)", "Bash(./mvnw:*)", "Bash(mvnw:*)", "Bash(gradle:*)", "Bash(./gradlew:*)", "Bash(gradlew:*)",
		"Bash(ant:*)", "Bash(java:*)", "Bash(javac:*)", "Bash(jar:*)", "Bash(jshell:*)", "Bash(kotlin:*)", "Bash(kotlinc:*)",
		"Bash(sbt:*)", "Bash(scala:*)", "Bash(lein:*)", "Bash(clojure:*)", "Bash(clj:*)",
		"Bash(make:*)", "Bash(cmake:*)", "Bash(ninja:*)", "Bash(meson:*)", "Bash(bazel:*)",
		"Bash(gcc:*)", "Bash(g++:*)", "Bash(clang:*)", "Bash(clang++:*)", "Bash(zig:*)",
		"Bash(node:*)", "Bash(npm:*)", "Bash(npx:*)", "Bash(pnpm:*)", "Bash(yarn:*)", "Bash(bun:*)", "Bash(deno:*)", "Bash(tsc:*)",
		"Bash(python:*)", "Bash(python3:*)", "Bash(pip:*)", "Bash(pip3:*)", "Bash(pipx:*)", "Bash(uv:*)", "Bash(poetry:*)",
		"Bash(conda:*)", "Bash(pytest:*)",
		"Bash(cargo:*)", "Bash(rustc:*)", "Bash(rustup:*)", "Bash(go:*)", "Bash(dotnet:*)", "Bash(swift:*)",
		"Bash(ruby:*)", "Bash(gem:*)", "Bash(bundle:*)", "Bash(php:*)", "Bash(composer:*)", "Bash(perl:*)",
		"Bash(mix:*)", "Bash(elixir:*)", "Bash(ghc:*)", "Bash(cabal:*)", "Bash(stack:*)",
		// looking around, and ordinary file handling inside the checkout
		"Bash(ls:*)", "Bash(dir:*)", "Bash(pwd)", "Bash(cd:*)", "Bash(tree:*)", "Bash(find:*)", "Bash(which:*)", "Bash(where:*)",
		"Bash(type:*)", "Bash(command:*)", "Bash(stat:*)", "Bash(file:*)", "Bash(du:*)", "Bash(df:*)",
		"Bash(basename:*)", "Bash(dirname:*)", "Bash(realpath:*)", "Bash(readlink:*)",
		"Bash(cat:*)", "Bash(head:*)", "Bash(tail:*)", "Bash(less:*)", "Bash(wc:*)", "Bash(diff:*)", "Bash(grep:*)", "Bash(rg:*)",
		"Bash(sort:*)", "Bash(uniq:*)", "Bash(cut:*)", "Bash(tr:*)", "Bash(sed:*)", "Bash(awk:*)", "Bash(jq:*)", "Bash(xargs:*)",
		"Bash(tee:*)", "Bash(echo:*)", "Bash(printf:*)", "Bash(date:*)", "Bash(test:*)", "Bash(true)", "Bash(false)",
		"Bash(sleep:*)", "Bash(timeout:*)", "Bash(ps:*)", "Bash(export:*)",
		"Bash(mkdir:*)", "Bash(touch:*)", "Bash(cp:*)", "Bash(mv:*)",
		"Bash(tar:*)", "Bash(zip:*)", "Bash(unzip:*)", "Bash(gzip:*)", "Bash(gunzip:*)");

	private static final AString PROJECTS = Strings.create("projects");
	private static final AString PATH = Strings.create("path");
	private static final AString USER = Strings.create("user");
	private static final AString DESCRIPTION = Strings.create("description");
	private static final AString OPTIONS = Strings.create("options");

	private ClaudeCode() {
	}

	/** The directory Brightside's own checkout lives in: {@code <home>/code/brightside}. */
	public static Path checkoutDir(Path home) {
		return home.resolve(CODE_DIR).resolve(PROJECT).toAbsolutePath().normalize();
	}

	/**
	 * Adds the default {@value #PROJECT} project when none of that name is
	 * declared, and makes every project without an explicit {@code user} the
	 * owner's, by re-configuring the running adapter. A no-op when the adapter
	 * is not on the venue (not registered, disabled, or its configuration was
	 * rejected at launch) or when nothing changes. Best-effort: a rejected
	 * configuration is logged, never thrown. {@code home} is the Brightside
	 * data directory the checkout lives under; null skips the default project.
	 */
	public static void own(EmbeddedVenue venue, String userDID, Path home) {
		if (venue == null || userDID == null) return;
		Engine engine = venue.engine();
		if (engine.getAdapter(ADAPTER) == null) return;
		AMap<AString, ACell> current = engine.adapterConfig(ADAPTER);
		AMap<AString, ACell> owned = owned(withDefaultProject(current, home), userDID);
		if (owned.equals(current)) return;
		try {
			engine.configureAdapter(ADAPTER, owned);
			AMap<AString, ACell> projects = RT.castMap(owned.get(PROJECTS));
			long n = (projects == null) ? 0 : projects.count();
			ACell own = (projects == null) ? null : RT.getIn(projects, PROJECT, PATH);
			log.info("Claude Code: {} project(s) for {}{}", n, userDID,
				own == null ? "" : "; Brightside's own source at " + own);
		} catch (RuntimeException e) {
			log.warn("Could not make the Claude Code projects the owner's: {}", e.getMessage());
		}
	}

	/**
	 * The adapter configuration with the default {@value #PROJECT} project added
	 * when none of that name is declared: {@link #checkoutDir}, created empty if
	 * need be, with Claude Code in its {@code auto} permission mode and the
	 * developer tools allowed there.
	 * Unchanged when {@code home} is null or the directory cannot be created.
	 */
	static AMap<AString, ACell> withDefaultProject(AMap<AString, ACell> config, Path home) {
		if (home == null) return config;
		if (config == null) config = Maps.empty();
		AMap<AString, ACell> projects = RT.castMap(config.get(PROJECTS));
		if (projects == null) projects = Maps.empty();
		if (projects.get(Strings.create(PROJECT)) != null) return config;
		Path dir = checkoutDir(home);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			log.warn("Could not create the Brightside checkout directory {}: {}", dir, e.getMessage());
			return config;
		}
		AMap<AString, ACell> spec = Maps.of(
			PATH, Strings.create(dir.toString()),
			DESCRIPTION, Strings.create("Brightside's own source, worked on without asking (auto mode, developer tools allowed). Empty until cloned from "
				+ REPOSITORY + " (git clone " + REPOSITORY + " .); then build with mvn package and run its tests with mvn test."),
			OPTIONS, Maps.of(
				Strings.create("permissionMode"), Strings.create("auto"),
				Strings.create("allowedTools"), RT.cvm(DEVELOPER_TOOLS),
				// Headless: a git operation that needs a credential nobody can type
				// must fail at once with a clear error, not hang a turn on a prompt.
				Strings.create("env"), Maps.of(
					Strings.create("GIT_TERMINAL_PROMPT"), Strings.create("0"),
					Strings.create("GCM_INTERACTIVE"), Strings.create("never"))));
		return config.assoc(PROJECTS, projects.assoc(Strings.create(PROJECT), spec));
	}

	/**
	 * The adapter configuration with {@code userDID} as the {@code user} of every
	 * project that does not name one. A project given as a bare path string
	 * becomes {@code {path, user}}; anything else is left exactly as written.
	 */
	static AMap<AString, ACell> owned(AMap<AString, ACell> config, String userDID) {
		if (config == null) config = Maps.empty();
		AMap<AString, ACell> projects = RT.castMap(config.get(PROJECTS));
		if (projects == null || projects.isEmpty()) return config;
		AString owner = Strings.create(userDID);
		AMap<AString, ACell> out = projects;
		long n = projects.count();
		for (long i = 0; i < n; i++) {
			MapEntry<AString, ACell> e = projects.entryAt(i);
			ACell v = e.getValue();
			if (v instanceof AString path) {
				out = out.assoc(e.getKey(), Maps.of(PATH, path, USER, owner));
			} else if (RT.castMap(v) instanceof AMap<?, ?> m && m.get(USER) == null) {
				out = out.assoc(e.getKey(), RT.castMap(v).assoc(USER, owner));
			}
		}
		return config.assoc(PROJECTS, out);
	}
}
