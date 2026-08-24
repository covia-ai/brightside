package covia.brightside;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.util.Utils;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * BrightSide's own skills, seeded into the venue's shared {@code v/} namespace.
 *
 * <p>Namespaces (see {@code docs/DESIGN.md}):</p>
 * <ul>
 *   <li>{@code v/skills/brightside/…} — BrightSide's default, shipped skills
 *       (this class). Written as the venue principal, readable by everyone.</li>
 *   <li>{@code w/skills} — the user's own skills, developed over time.</li>
 *   <li>{@code n/…} — the assistant's private scratch space, including
 *       {@code n/memory}.</li>
 * </ul>
 *
 * <p>Seeded on startup as the venue principal (only the venue may write
 * {@code v/}); overwriting each launch keeps the shipped skills up to date.
 */
public final class DefaultSkills {

	private static final Logger log = LoggerFactory.getLogger(DefaultSkills.class);

	/** BrightSide's default skillset under the venue namespace. */
	public static final String SKILLSET = "v/skills/brightside";

	/** The introduction skill — how the assistant presents itself. */
	public static final String INTRODUCTION = SKILLSET + "/introduction";

	private static final String WRITE_OP = "v/ops/covia/write";
	private static final long TIMEOUT_SECONDS = 30;

	private DefaultSkills() {
	}

	/**
	 * Writes BrightSide's default skills into {@code v/}. The client must act as
	 * the venue principal. Best-effort per skill: a failure is logged and the
	 * others still install.
	 */
	public static void seed(Venue venueClient) {
		writeSkill(venueClient, INTRODUCTION, "introduction", "/brightside/introduction.md",
			"How to introduce BrightSide and what it can help with. Load when greeting the user, "
			+ "when asked who or what you are, or when they seem unsure what you can do.");
	}

	private static void writeSkill(Venue venueClient, String path, String name, String bodyResource,
			String description) {
		try {
			String body = Utils.readResourceAsString(bodyResource);
			AMap<AString, ACell> skill = Maps.of(
				"name", name,
				"description", description,
				"content", Maps.of("contentType", "text/markdown", "inline", body),
				"skill", Maps.of());
			Job job = venueClient.invoke(WRITE_OP, Maps.of("path", path, "value", skill))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			log.info("Seeded default skill {}", path);
		} catch (Exception e) {
			log.warn("Could not seed default skill {}: {}", path, e.toString());
		}
	}
}
