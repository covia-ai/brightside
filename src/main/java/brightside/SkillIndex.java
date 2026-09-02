package brightside;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.lang.RT;

/**
 * The skills an agent can discover: every entry of every skillset its record
 * names, then everything those skills would reveal once loaded, read straight
 * from the in-process lattice as the owner — no job. This is the discovery
 * surface behind the model's {@code [Skills]} index — the same names and
 * descriptions, in the order the index searches — followed by the hierarchy
 * beneath it, which the index shows only as parents are loaded. A later
 * namesake is marked {@linkplain Skill#shadowed shadowed} because the index
 * keeps the first; the same skill installed at a second address is listed
 * once. A skill's tools and what it reveals come from its {@code skill} block.
 */
public final class SkillIndex {

	/**
	 * One discoverable skill.
	 *
	 * @param name        the skill's name — what the model loads it by
	 * @param path        its lattice path, {@code <skillset>/<key>} — what loads refer to
	 * @param skillset    the directory it lives in: a configured skillset, a revealed
	 *                    one, or the parent skill whose map holds it
	 * @param description its one-line description, as the index shows it
	 * @param tools       the operations it grants when loaded
	 * @param children    what it reveals when loaded — its {@code skill.skills} (individual
	 *                    skills, which Brightside's routers use) then its {@code skill.skillsets}
	 *                    (directories of skills, which the venue's entry points use)
	 * @param shadowed    an earlier skill has this name, so the index shows that one
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

	/**
	 * Every skill of every skillset {@code agentRecord} names, then what those
	 * reveal, breadth-first, resolved as {@code userDID}. Skills come grouped by
	 * directory: the configured skillsets first, in order, then each revealed
	 * directory as it is reached. A shadowed skill's reveals are not followed —
	 * the index never offers it by name.
	 */
	public static List<Skill> of(EmbeddedVenue venue, String userDID, ACell agentRecord) {
		Walk walk = new Walk(venue, userDID);
		for (String skillset : skillsetsOf(agentRecord)) walk.pending.add(new Ref(true, skillset));
		walk.run();
		List<Skill> out = new ArrayList<>();
		walk.groups.values().forEach(out::addAll);
		return List.copyOf(out);
	}

	/** A discovery source: a directory of skills, or one skill. */
	private record Ref(boolean directory, String path) {
	}

	/** A skill as read, with its raw metadata (for spotting the same skill twice) and what it reveals. */
	private record Found(Skill skill, ACell cell, List<Ref> reveals) {
	}

	private static final class Walk {
		private final EmbeddedVenue venue;
		private final String userDID;
		final Map<String, List<Skill>> groups = new LinkedHashMap<>();
		final List<Ref> pending = new ArrayList<>();
		private final Map<String, ACell> named = new HashMap<>();
		private final Set<String> seenPaths = new HashSet<>();

		Walk(EmbeddedVenue venue, String userDID) {
			this.venue = venue;
			this.userDID = userDID;
		}

		void run() {
			// pending grows as skills reveal more; each ref is walked once.
			for (int i = 0; i < pending.size(); i++) {
				Ref ref = pending.get(i);
				if (ref.directory()) directory(ref.path());
				else skill(ref.path());
			}
		}

		private void directory(String dir) {
			ACell set = venue.resolve(userDID, dir);
			if (!(set instanceof AMap<?, ?> m)) return;
			@SuppressWarnings("unchecked")
			AMap<ACell, ACell> entries = (AMap<ACell, ACell>) m;
			List<Found> here = new ArrayList<>();
			for (long i = 0; i < entries.count(); i++) {
				MapEntry<ACell, ACell> entry = entries.entryAt(i);
				String key = str(entry.getKey());
				if (key == null || !(entry.getValue() instanceof AMap)) continue;
				String path = dir + "/" + key;
				if (seenPaths.add(path)) here.add(describe(key, path, dir, entry.getValue()));
			}
			here.sort(Comparator.comparing(f -> f.skill().name()));
			for (Found f : here) place(f);
		}

		private void skill(String path) {
			if (!seenPaths.add(path)) return;
			ACell cell = venue.resolve(userDID, path);
			if (!(cell instanceof AMap)) return;
			int slash = path.lastIndexOf('/');
			String key = (slash >= 0) ? path.substring(slash + 1) : path;
			String dir = (slash >= 0) ? path.substring(0, slash) : "";
			place(describe(key, path, dir, cell));
		}

		/** Files the skill under its directory, marks a namesake shadowed, and queues what it reveals. */
		private void place(Found f) {
			Skill s = f.skill();
			ACell prior = named.putIfAbsent(s.name(), f.cell());
			if (prior != null && prior.equals(f.cell())) return;   // the same skill at another address
			boolean shadowed = prior != null;
			groups.computeIfAbsent(s.skillset(), k -> new ArrayList<>()).add(shadowed
				? new Skill(s.name(), s.path(), s.skillset(), s.description(), s.tools(), s.children(), true)
				: s);
			if (!shadowed) pending.addAll(f.reveals());
		}
	}

	private static Found describe(String key, String path, String dir, ACell cell) {
		String name = str(RT.getIn(cell, "name"));
		List<String> skills = strings(RT.getIn(cell, "skill", "skills"));
		List<String> sets = strings(RT.getIn(cell, "skill", "skillsets"));
		List<String> children = new ArrayList<>(skills);
		children.addAll(sets);
		List<Ref> reveals = new ArrayList<>();
		for (String s : skills) reveals.add(new Ref(false, s));
		for (String s : sets) reveals.add(new Ref(true, s));
		return new Found(new Skill((name != null) ? name : key, path, dir,
			str(RT.getIn(cell, "description")), strings(RT.getIn(cell, "skill", "tools")),
			List.copyOf(children), false), cell, List.copyOf(reveals));
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
