package brightside.ui.skills;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import brightside.SkillIndex;

/**
 * The skills an agent can discover, as a tree: each skillset it searches at
 * the root, the skills in it beneath, and under each skill what it reveals —
 * single skills and whole directories — as far as {@link SkillIndex} reached.
 * A skill two parents reveal appears under both; a route that comes back to
 * a skill already on it stops there, so a cycle of reveals ends. A shadowed
 * skill shows nothing beneath it, as the index never offers it by name.
 * Plain data, built off the event thread if need be; {@link SkillsScreen}
 * shows it.
 */
public final class SkillTree {

	/** What a node stands for. */
	public enum Kind {
		/** A directory of skills: a configured skillset, or one a skill reveals. */
		SKILLSET,
		/** One skill. */
		SKILL,
		/** A reveal the index has nothing for: unreadable, or the same skill listed under another path. */
		MISSING
	}

	/** One node: its lattice {@code path}, the {@code skill} it is (null unless a {@link Kind#SKILL}), and what is beneath it. */
	public record Node(Kind kind, String path, SkillIndex.Skill skill, List<Node> children) {

		/** The name to show: a skill's name, otherwise the path. */
		public String label() {
			return (skill != null) ? skill.name() : path;
		}

		/** Every node beneath this one, depth first, this one included. */
		void collect(List<Node> into) {
			into.add(this);
			for (Node c : children) c.collect(into);
		}
	}

	private SkillTree() {
	}

	/**
	 * The tree for {@code skillsets} (the roots, in the order the agent
	 * searches them) over {@code index} (what {@link SkillIndex#of} read).
	 */
	public static List<Node> build(List<String> skillsets, List<SkillIndex.Skill> index) {
		Map<String, SkillIndex.Skill> byPath = new HashMap<>();
		Map<String, List<SkillIndex.Skill>> byDirectory = new LinkedHashMap<>();
		for (SkillIndex.Skill s : index) {
			byPath.putIfAbsent(s.path(), s);
			byDirectory.computeIfAbsent(s.skillset(), k -> new ArrayList<>()).add(s);
		}
		Builder b = new Builder(byPath, byDirectory);
		List<Node> roots = new ArrayList<>();
		for (String dir : skillsets) roots.add(b.directory(dir));
		return List.copyOf(roots);
	}

	private static final class Builder {
		private final Map<String, SkillIndex.Skill> byPath;
		private final Map<String, List<SkillIndex.Skill>> byDirectory;
		/** The paths on the route from a root to the node being built: where a reveal would loop. */
		private final Set<String> route = new HashSet<>();

		Builder(Map<String, SkillIndex.Skill> byPath, Map<String, List<SkillIndex.Skill>> byDirectory) {
			this.byPath = byPath;
			this.byDirectory = byDirectory;
		}

		Node directory(String dir) {
			List<Node> children = new ArrayList<>();
			if (route.add(dir)) {
				for (SkillIndex.Skill s : byDirectory.getOrDefault(dir, List.of())) children.add(skill(s));
				route.remove(dir);
			}
			return new Node(Kind.SKILLSET, dir, null, List.copyOf(children));
		}

		Node skill(SkillIndex.Skill s) {
			List<Node> children = new ArrayList<>();
			if (!s.shadowed() && route.add(s.path())) {
				for (String child : s.children()) {
					SkillIndex.Skill revealed = byPath.get(child);
					if (revealed != null) children.add(skill(revealed));
					else if (byDirectory.containsKey(child)) children.add(directory(child));
					else children.add(new Node(Kind.MISSING, child, null, List.of()));
				}
				route.remove(s.path());
			}
			return new Node(Kind.SKILL, s.path(), s, List.copyOf(children));
		}
	}
}
