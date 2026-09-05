package brightside.ui.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.SkillIndex;

/** The tree over a flat index: roots in search order, reveals beneath their revealer, and no endless routes. */
class SkillTreeTest {

	private static SkillIndex.Skill skill(String dir, String key, List<String> children, boolean shadowed) {
		return new SkillIndex.Skill(key, dir + "/" + key, dir, "about " + key, List.of(), children, shadowed);
	}

	@Test
	void rootsAreTheSkillsetsInSearchOrderEvenWhenEmpty() {
		List<SkillIndex.Skill> index = List.of(skill("v/skills/root", "workspace", List.of(), false));
		List<SkillTree.Node> roots = SkillTree.build(List.of("w/skills", "v/skills/root"), index);
		assertEquals(List.of("w/skills", "v/skills/root"), roots.stream().map(SkillTree.Node::path).toList());
		assertEquals(SkillTree.Kind.SKILLSET, roots.get(0).kind());
		assertTrue(roots.get(0).children().isEmpty(), "an empty skillset is still a root");
		assertEquals("workspace", roots.get(1).children().get(0).label());
	}

	@Test
	void whatASkillRevealsSitsBeneathItSkillsAndWholeDirectoriesAlike() {
		SkillIndex.Skill convex = skill("v/skills/brightside", "convex",
			List.of("v/skills/brightside/convex/accounts", "v/skills/lattice"), false);
		SkillIndex.Skill accounts = skill("v/skills/brightside/convex", "accounts", List.of(), false);
		SkillIndex.Skill lattice = skill("v/skills/lattice", "reads", List.of(), false);
		List<SkillTree.Node> roots = SkillTree.build(List.of("v/skills/brightside"), List.of(convex, accounts, lattice));

		SkillTree.Node top = roots.get(0).children().get(0);
		assertSame(convex, top.skill());
		assertEquals(List.of(SkillTree.Kind.SKILL, SkillTree.Kind.SKILLSET),
			top.children().stream().map(SkillTree.Node::kind).toList());
		assertSame(accounts, top.children().get(0).skill());
		SkillTree.Node dir = top.children().get(1);
		assertEquals("v/skills/lattice", dir.path());
		assertSame(lattice, dir.children().get(0).skill());
	}

	@Test
	void aRouteThatReturnsToItselfStopsAndAShadowedSkillRevealsNothing() {
		SkillIndex.Skill a = skill("w/skills", "a", List.of("w/skills/b"), false);
		SkillIndex.Skill b = skill("w/skills", "b", List.of("w/skills/a"), false);
		SkillIndex.Skill shadow = skill("v/skills/root", "a", List.of("v/skills/root/child"), true);
		SkillIndex.Skill child = skill("v/skills/root", "child", List.of(), false);
		List<SkillTree.Node> roots = SkillTree.build(List.of("w/skills", "v/skills/root"), List.of(a, b, shadow, child));

		SkillTree.Node nodeA = roots.get(0).children().get(0);
		SkillTree.Node nodeB = nodeA.children().get(0);
		assertSame(b, nodeB.skill());
		SkillTree.Node back = nodeB.children().get(0);
		assertSame(a, back.skill());
		assertTrue(back.children().isEmpty(), "the route back to a stops there");

		SkillTree.Node shadowed = roots.get(1).children().stream()
			.filter(n -> n.skill() == shadow).findFirst().orElseThrow();
		assertTrue(shadowed.children().isEmpty(), "a shadowed skill is never loaded, so reveals nothing");
	}

	@Test
	void aRevealTheIndexLacksIsAMissingLeaf() {
		SkillIndex.Skill s = skill("w/skills", "s", List.of("w/skills/gone"), false);
		List<SkillTree.Node> roots = SkillTree.build(List.of("w/skills"), List.of(s));
		SkillTree.Node missing = roots.get(0).children().get(0).children().get(0);
		assertEquals(SkillTree.Kind.MISSING, missing.kind());
		assertEquals("w/skills/gone", missing.label());
	}
}
