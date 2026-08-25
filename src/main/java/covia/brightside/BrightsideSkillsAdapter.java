package covia.brightside;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.venue.RequestContext;

/**
 * Installs Brightside's default skill library into the venue's {@code v/}
 * namespace — kept as its own adapter so the shipped skills are a self-contained
 * unit, separate from Brightside's operations ({@link BrightsideAdapter}).
 *
 * <p>Skills, under {@code v/skills/brightside/…}:</p>
 * <ul>
 *   <li><b>introduction</b> — how the assistant presents itself (pinned).</li>
 *   <li><b>conversations</b> — how it talks with the user and reviews past
 *       conversations (pinned); its facet grants the read-only past-session
 *       tools ({@code agent:sessions}, {@code agent:session-read}).</li>
 *   <li><b>skills</b> — how it grows new abilities (pinned); its
 *       {@code skill.skills} facet reveals <b>skill-authoring</b>.</li>
 *   <li><b>skill-authoring</b> — the gated sub-skill whose facet grants the
 *       {@code covia:write} tool, so the assistant can author skills into its
 *       own {@code w/skills}.</li>
 * </ul>
 *
 * <p>Registered on the embedded engine at launch ({@link EmbeddedVenue}); like
 * any Covia adapter, its skills live and die with it. This adapter has no
 * operations — it is purely a skill source.
 */
public class BrightsideSkillsAdapter extends AAdapter {

	/** Brightside's default skillset under the venue namespace. */
	public static final String SKILLSET = "v/skills/brightside";
	/** Always-loaded: how the assistant introduces itself. */
	public static final String INTRODUCTION = SKILLSET + "/introduction";
	/** Always-loaded: how it talks with the user and reviews past conversations. */
	public static final String CONVERSATIONS = SKILLSET + "/conversations";
	/** Always-loaded: how the assistant grows new abilities; gates skill-authoring. */
	public static final String SKILLS = SKILLSET + "/skills";
	/** Gated sub-skill: how to author a skill, and the write tool to do it. */
	public static final String SKILL_AUTHORING = SKILLSET + "/skill-authoring";

	@Override
	public String getName() {
		return "brightsideskills";
	}

	@Override
	public String getDescription() {
		return "Brightside's default skill library: the introduction, skills and "
			+ "skill-authoring skills under v/skills/brightside.";
	}

	@Override
	protected void installAssets() {
		installSkill("brightside/introduction", "/brightside/skills/introduction.json");
		installSkill("brightside/conversations", "/brightside/skills/conversations.json");
		installSkill("brightside/skill-authoring", "/brightside/skills/skill-authoring.json");
		installSkill("brightside/skills", "/brightside/skills/skills.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// A skill source has no operations.
		return CompletableFuture.failedFuture(
			new IllegalArgumentException("brightsideskills exposes no operations"));
	}
}
