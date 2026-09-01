package brightside;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.lang.RT;

/**
 * The skills an agent can discover: every entry of every skillset its record
 * names, read straight from the in-process lattice as the owner — no job.
 * This is the discovery surface behind the model's {@code [Skills]} index:
 * the same names and descriptions, in skillset order, with a later skillset's
 * namesake marked {@linkplain Skill#shadowed shadowed} because the index keeps
 * the first. A skill's tools and the skillsets it reveals come from its
 * {@code skill} block.
 */
public final class SkillIndex {

	/**
	 * One discoverable skill.
	 *
	 * @param name        the skill's name — what the model loads it by
	 * @param path        its lattice path, {@code <skillset>/<key>} — what loads refer to
	 * @param skillset    the skillset it was found in
	 * @param description its one-line description, as the index shows it
	 * @param tools       the operations it grants when loaded
	 * @param children    the skillsets it reveals when loaded
	 * @param shadowed    an earlier skillset has a skill of this name, so the index shows that one
	 */
	public record Skill(String name, String path, String skillset, String description,
			List<String> tools, List<String> children, boolean shadowed) {
	}

	private SkillIndex() {
	}

	/** The skillsets an agent's record names, in configured order — empty when it names none. */
	public static List<String> skillsetsOf(ACell agentRecord) {
		return strings(RT.getIn(agentRecord, "config", "skillsets"));
	}

	/** Every skill of every skillset {@code agentRecord} names, resolved as {@code userDID}. */
	public static List<Skill> of(EmbeddedVenue venue, String userDID, ACell agentRecord) {
		List<Skill> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String skillset : skillsetsOf(agentRecord)) {
			ACell set = venue.resolve(userDID, skillset);
			if (!(set instanceof AMap<?, ?> m)) continue;
			@SuppressWarnings("unchecked")
			AMap<ACell, ACell> entries = (AMap<ACell, ACell>) m;
			List<Skill> here = new ArrayList<>();
			for (long i = 0; i < entries.count(); i++) {
				MapEntry<ACell, ACell> e = entries.entryAt(i);
				String key = str(e.getKey());
				ACell skill = e.getValue();
				if (key == null || !(skill instanceof AMap)) continue;
				String name = str(RT.getIn(skill, "name"));
				here.add(new Skill((name != null) ? name : key, skillset + "/" + key, skillset,
					str(RT.getIn(skill, "description")),
					strings(RT.getIn(skill, "skill", "tools")),
					strings(RT.getIn(skill, "skill", "skillsets")), false));
			}
			here.sort(Comparator.comparing(Skill::name));
			for (Skill s : here) {
				out.add(seen.add(s.name()) ? s
					: new Skill(s.name(), s.path(), s.skillset(), s.description(), s.tools(), s.children(), true));
			}
		}
		return List.copyOf(out);
	}

	private static List<String> strings(ACell cell) {
		if (!(cell instanceof AVector<?> v)) return List.of();
		List<String> out = new ArrayList<>();
		for (long i = 0; i < v.count(); i++) {
			String s = str((ACell) v.get(i));
			if (s != null) out.add(s);
		}
		return List.copyOf(out);
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}
}
