package covia.brightside.skills;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * Imports skills from the user's filesystem into their agent's own
 * {@code w/skills}, so a skill can be a portable, editable, shareable file
 * rather than only a lattice entry.
 *
 * <p><b>Format.</b> Compatible with the
 * <a href="https://agentskills.io">agentskills.io</a> Agent Skills format: a
 * folder per skill containing a {@code SKILL.md} (YAML frontmatter — at least
 * {@code name} and {@code description} — followed by a markdown body). A single
 * {@code <name>.md} file is also accepted for convenience. This is the same
 * markdown-with-frontmatter shape Covia's own skill resolver understands, so no
 * lossy translation is needed: the raw {@code SKILL.md} is stored verbatim as
 * the skill asset's {@code content.inline}, with {@code name}/{@code description}
 * lifted into the asset metadata. Covia strips the frontmatter from the body at
 * resolve time and reads any {@code tools}/{@code skills}/{@code skillsets}
 * frontmatter lists into the skill facet — so a plain agentskills.io skill loads
 * as instructions, and one that also lists Covia operation paths under
 * {@code tools:} grants those.
 *
 * <p><b>Write target.</b> Each skill is written to {@code w/skills/<name>} as the
 * acting user — the same namespace the agent authors its own skills into and
 * already discovers — so imported skills are available by name with no config
 * change. Import is a non-destructive upsert: it never deletes other
 * {@code w/skills} entries, so it leaves agent-authored skills alone.
 *
 * <p>Bundled resource directories ({@code scripts/}, {@code references/},
 * {@code assets/}) are not yet imported — Covia has no directory-bundle facet —
 * so for now only the {@code SKILL.md} instructions are brought across.
 */
public final class FilesystemSkills {

	private static final Logger log = LoggerFactory.getLogger(FilesystemSkills.class);
	private static final long TIMEOUT_SECONDS = 30;

	/** The per-folder skill file, agentskills.io style. */
	public static final String SKILL_FILE = "SKILL.md";
	private static final int MAX_NAME = 64;

	/** What a sync imported and what it skipped (name → reason). */
	public record Result(List<String> loaded, Map<String, String> skipped) {
	}

	/** A parsed skill ready to write: sanitised {@code name}, {@code description}, raw markdown {@code content}. */
	public record Parsed(String name, String description, String content) {
	}

	private FilesystemSkills() {
	}

	/**
	 * Imports every skill found under {@code dir} into {@code client}'s
	 * {@code w/skills}. Creates {@code dir} with a short README the first time
	 * (so the feature is discoverable) and never throws — a bad skill is skipped
	 * with a reason, the rest still import.
	 */
	public static Result sync(Venue client, Path dir) {
		List<String> loaded = new ArrayList<>();
		Map<String, String> skipped = new LinkedHashMap<>();
		if (!Files.isDirectory(dir)) {
			seed(dir);
			return new Result(loaded, skipped);
		}
		for (Source source : discover(dir)) {
			try {
				Parsed parsed = parse(Files.readString(source.file()), source.name());
				if (parsed == null) {
					skipped.put(source.name(), "needs a non-empty 'description' in its frontmatter");
					continue;
				}
				write(client, parsed);
				loaded.add(parsed.name());
			} catch (Exception e) {
				skipped.put(source.name(), e.toString());
			}
		}
		if (!loaded.isEmpty() || !skipped.isEmpty()) {
			log.info("Imported {} filesystem skill(s) into w/skills{}", loaded.size(),
				skipped.isEmpty() ? "" : " — skipped " + skipped.size() + ": " + skipped);
		}
		return new Result(loaded, skipped);
	}

	private record Source(String name, Path file) {
	}

	/** A folder with a {@code SKILL.md}, or a top-level {@code <name>.md} (README aside). */
	private static List<Source> discover(Path dir) {
		List<Source> out = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
			for (Path entry : entries) {
				String fileName = entry.getFileName().toString();
				if (Files.isDirectory(entry)) {
					Path skill = entry.resolve(SKILL_FILE);
					if (Files.isRegularFile(skill)) out.add(new Source(fileName, skill));
				} else if (fileName.toLowerCase().endsWith(".md") && !"README.md".equalsIgnoreCase(fileName)) {
					out.add(new Source(fileName.substring(0, fileName.length() - 3), entry));
				}
			}
		} catch (IOException e) {
			log.warn("Could not scan skills directory {}: {}", dir, e.toString());
		}
		return out;
	}

	/**
	 * Builds a writable skill from a {@code SKILL.md} and its folder name, or
	 * null when it has no usable {@code description} (which Covia requires). The
	 * raw text is kept as the body; {@code name} comes from the folder (the
	 * agentskills.io rule that the folder name is canonical).
	 */
	public static Parsed parse(String raw, String folderName) {
		String name = sanitiseName(folderName);
		if (name.isEmpty()) return null;
		String description = frontmatterValue(raw, "description");
		if (description == null || description.isBlank()) return null;
		return new Parsed(name, description.strip(), raw);
	}

	/** The scalar value of a frontmatter key from the leading {@code ---} block, or null. */
	static String frontmatterValue(String raw, String key) {
		if (raw == null || !raw.startsWith("---")) return null;
		String[] lines = raw.split("\r?\n", -1);
		for (int i = 1; i < lines.length; i++) {
			if (lines[i].strip().equals("---")) break; // end of frontmatter
			int colon = lines[i].indexOf(':');
			if (colon < 0) continue;
			if (lines[i].substring(0, colon).strip().equalsIgnoreCase(key)) {
				return unquote(lines[i].substring(colon + 1).strip());
			}
		}
		return null;
	}

	private static String unquote(String s) {
		if (s.length() >= 2
			&& ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
				|| (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}

	/** Folds a folder name to a valid skill/path segment (agentskills.io name rules). */
	static String sanitiseName(String raw) {
		String name = raw.toLowerCase().strip()
			.replaceAll("[^a-z0-9-]+", "-")
			.replaceAll("-{2,}", "-")
			.replaceAll("^-+|-+$", "");
		if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME).replaceAll("-+$", "");
		return name;
	}

	/** Writes the skill to {@code w/skills/<name>} as the acting user (an upsert). */
	private static void write(Venue client, Parsed parsed) throws Exception {
		AMap<AString, ACell> asset = Maps.of(
			"name", parsed.name(),
			"description", parsed.description(),
			"content", Maps.of("contentType", "text/markdown", "inline", parsed.content()));
		AMap<AString, ACell> input = Maps.of("path", "w/skills/" + parsed.name(), "value", asset);
		Job job = client.invoke("v/ops/covia/write", input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static void seed(Path dir) {
		try {
			Files.createDirectories(dir);
			Path readme = dir.resolve("README.md");
			if (!Files.exists(readme)) Files.writeString(readme, SEED_README);
		} catch (IOException e) {
			log.warn("Could not create skills directory {}: {}", dir, e.toString());
		}
	}

	private static final String SEED_README = """
		# Brightside skills

		Drop a skill in here and Brightside imports it for your agent the next time
		it starts. Two layouts work, both in the open agentskills.io format:

		- a folder with a `SKILL.md`:   `my-skill/SKILL.md`
		- a single markdown file:       `my-skill.md`

		A `SKILL.md` is YAML frontmatter followed by markdown instructions:

		    ---
		    name: weekly-report
		    description: How I like my weekly report written. Load when drafting the weekly report.
		    ---

		    Keep it to five bullets, lead with wins, plain language, no filler.

		`name` and `description` are required — the description is the trigger, so say
		what the skill does *and* when to use it. To grant Covia tools, add a `tools:`
		list of operation paths (e.g. `tools: [v/ops/http/get]`). Imported skills land
		in your agent's own `w/skills`, so it can discover and load them by name.
		""";
}
