package covia.brightside;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.Utils;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * Brightside's own skill library, seeded into the venue's shared {@code v/}
 * namespace so the assistant ships with abilities and can grow more.
 *
 * <p>Namespaces (see {@code docs/DESIGN.md}):</p>
 * <ul>
 *   <li>{@code v/skills/brightside/…} — Brightside's default, shipped skills
 *       (this class). Written as the venue principal; readable by everyone.</li>
 *   <li>{@code w/skills} — the user's own skills, which the assistant authors
 *       and refines over time.</li>
 *   <li>{@code n/…} — the assistant's private scratch space, incl. {@code n/memory}.</li>
 * </ul>
 *
 * <p>The shipped skills are hierarchical: <b>introduction</b> (how it presents
 * itself) is always loaded; <b>skills</b> (how it grows) is always loaded and
 * gates <b>skill-authoring</b> as a sub-skill — the how-to plus the write tool,
 * revealed only when the assistant actually goes to author a skill. So the
 * ability to write into {@code w/skills} is present but not in the way.
 *
 * <p>Seeded on startup as the venue principal (only the venue may write
 * {@code v/}); overwriting each launch keeps the shipped skills up to date.
 */
public final class BrightsideSkills {

	private static final Logger log = LoggerFactory.getLogger(BrightsideSkills.class);

	/** Brightside's default skillset under the venue namespace. */
	public static final String SKILLSET = "v/skills/brightside";

	/** Always-loaded: how the assistant introduces itself. */
	public static final String INTRODUCTION = SKILLSET + "/introduction";
	/** Always-loaded: how the assistant grows new abilities; gates skill-authoring. */
	public static final String SKILLS = SKILLSET + "/skills";
	/** Gated sub-skill: how to author a skill, and the write tool to do it. */
	public static final String SKILL_AUTHORING = SKILLSET + "/skill-authoring";

	private static final String WRITE_OP = "v/ops/covia/write";
	private static final long TIMEOUT_SECONDS = 30;

	private BrightsideSkills() {
	}

	/**
	 * Writes Brightside's default skills into {@code v/}. The client must act as
	 * the venue principal. Best-effort per skill: a failure is logged and the
	 * others still install.
	 */
	public static void seed(Venue venueClient) {
		writeSkill(venueClient, INTRODUCTION, "introduction", "/brightside/introduction.md",
			"How to introduce Brightside and what it can help with. Load when greeting the user, "
			+ "when asked who or what you are, or when they seem unsure what you can do.",
			Maps.empty());

		// The write tool arrives only with skill-authoring — the ability to change
		// w/skills is gated behind deliberately loading this sub-skill.
		writeSkill(venueClient, SKILL_AUTHORING, "skill-authoring", "/brightside/skill-authoring.md",
			"How to author a new skill into w/skills. Load when creating or refining one of your own skills.",
			Maps.of("tools", Vectors.of(
				Strings.create("v/ops/covia/write"),
				Strings.create("v/ops/covia/read"),
				Strings.create("v/ops/covia/list"))));

		// The meta-skill reveals skill-authoring as a sub-skill when loaded.
		writeSkill(venueClient, SKILLS, "skills", "/brightside/skills.md",
			"How you grow new abilities by authoring skills. Load when asked to learn, improve, "
			+ "teach yourself, or 'upgrade', or when a repeatable task is worth capturing.",
			Maps.of("skills", Vectors.of(Strings.create(SKILL_AUTHORING))));
	}

	private static void writeSkill(Venue venueClient, String path, String name, String bodyResource,
			String description, AMap<AString, ACell> facet) {
		try {
			String body = Utils.readResourceAsString(bodyResource);
			AMap<AString, ACell> skill = Maps.of(
				"name", name,
				"description", description,
				"content", Maps.of("contentType", "text/markdown", "inline", body),
				"skill", facet);
			Job job = venueClient.invoke(WRITE_OP, Maps.of("path", path, "value", skill))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			log.info("Seeded default skill {}", path);
		} catch (Exception e) {
			log.warn("Could not seed default skill {}: {}", path, e.toString());
		}
	}
}
