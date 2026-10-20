package brightside.ui.skills;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.SkillIndex;
import brightside.ui.components.Chip;
import brightside.ui.components.Labels;
import brightside.ui.components.ScreenHeader;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The <b>Skills</b> screen: the skills the assistant can discover, as an
 * expandable tree — each skillset it searches at the root, the skills in it,
 * and under each skill what it reveals ({@link SkillTree}) — beside a
 * {@link Readout} of whatever is selected: what a skill does, the tools it
 * grants and what it reveals; what a directory is. Directories, shadowed
 * skills and reveals the index could not read are muted.
 *
 * <p>Dumb: it shows the index it is given, re-read by the app on every visit.
 * A refresh keeps what was expanded and selected, by path.
 */
@SuppressWarnings("serial")
public final class SkillsScreen extends JPanel {

	private static final int TREE_WIDTH = 340;

	private final ScreenHeader header = new ScreenHeader("Skills");
	/** Whose skills these are. */
	private final Chip agent = new Chip(" ");
	private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
	private final DefaultTreeModel model = new DefaultTreeModel(root);
	private final JTree tree = new JTree(model);
	private final JPanel detail = new JPanel(new BorderLayout());
	private boolean shown;
	/** A path asked for before the tree held it; shown once it does. */
	private String pendingReveal;

	public SkillsScreen() {
		super(new BorderLayout());
		agent.setToolTipText("The agent whose skills these are");
		agent.setVisible(false);
		header.detail(agent);
		add(header, BorderLayout.NORTH);

		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setCellRenderer(new Renderer());
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
		tree.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
		tree.addTreeSelectionListener(e -> showSelected());
		ToolTipManager.sharedInstance().registerComponent(tree);

		detail.setOpaque(false);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Scrolls.vertical(tree), detail);
		split.setBorder(null);
		split.setContinuousLayout(true);
		split.setResizeWeight(0.0);
		split.setDividerLocation(TREE_WIDTH);
		add(split, BorderLayout.CENTER);
		showSelected();
	}

	/**
	 * Replace the tree with {@code agentName}'s discoverable skills: its
	 * {@code skillsets} (the roots, in search order) over {@code index}.
	 */
	public void setSkills(String agentName, List<String> skillsets, List<SkillIndex.Skill> index) {
		agent.setText((agentName != null) ? agentName : " ");
		agent.setVisible(agentName != null);
		Set<String> expanded = expandedPaths();
		String selected = selectedPath();

		root.removeAllChildren();
		for (SkillTree.Node n : SkillTree.build(skillsets, index)) root.add(treeNode(n));
		model.reload();

		List<DefaultMutableTreeNode> all = new ArrayList<>();
		for (Enumeration<?> e = root.depthFirstEnumeration(); e.hasMoreElements();) {
			if (e.nextElement() instanceof DefaultMutableTreeNode n && n != root) all.add(n);
		}
		if (!shown) {
			// First sight: the roots open, so the library is in view at once.
			for (int i = 0; i < root.getChildCount(); i++) tree.expandPath(pathTo((DefaultMutableTreeNode) root.getChildAt(i)));
			shown = true;
		} else {
			for (DefaultMutableTreeNode n : all) {
				if (expanded.contains(nodeOf(n).path())) tree.expandPath(pathTo(n));
			}
		}
		if (selected != null) select(selected);
		if (pendingReveal != null && select(pendingReveal)) pendingReveal = null;
		showSelected();
	}

	/**
	 * Brings the skill or directory at {@code path} into view, selected —
	 * now, or as soon as the tree next holds it. The shallowest of several
	 * places it appears is the one shown.
	 */
	public void reveal(String path) {
		if (path == null) return;
		if (select(path)) pendingReveal = null;
		else pendingReveal = path;
	}

	/** Selects and scrolls to the shallowest node at {@code path}, opening the way to it; false when there is none. */
	private boolean select(String path) {
		for (Enumeration<?> e = root.breadthFirstEnumeration(); e.hasMoreElements();) {
			if (e.nextElement() instanceof DefaultMutableTreeNode n && n != root && path.equals(nodeOf(n).path())) {
				TreePath to = pathTo(n);
				tree.setSelectionPath(to);
				tree.scrollPathToVisible(to);
				return true;
			}
		}
		return false;
	}

	private static DefaultMutableTreeNode treeNode(SkillTree.Node n) {
		DefaultMutableTreeNode t = new DefaultMutableTreeNode(n, !n.children().isEmpty());
		for (SkillTree.Node c : n.children()) t.add(treeNode(c));
		return t;
	}

	private static SkillTree.Node nodeOf(DefaultMutableTreeNode t) {
		return (SkillTree.Node) t.getUserObject();
	}

	private static TreePath pathTo(DefaultMutableTreeNode n) {
		return new TreePath(n.getPath());
	}

	/** The paths of the nodes open in the tree — by lattice path, so a refresh can reopen them. */
	private Set<String> expandedPaths() {
		Set<String> out = new HashSet<>();
		Enumeration<TreePath> open = tree.getExpandedDescendants(new TreePath(root));
		if (open == null) return out;
		while (open.hasMoreElements()) {
			if (open.nextElement().getLastPathComponent() instanceof DefaultMutableTreeNode n && n != root) {
				out.add(nodeOf(n).path());
			}
		}
		return out;
	}

	private String selectedPath() {
		TreePath p = tree.getSelectionPath();
		return (p != null && p.getLastPathComponent() instanceof DefaultMutableTreeNode n && n != root)
			? nodeOf(n).path() : null;
	}

	// ------------------------------------------------------------------
	// The selected node, described
	// ------------------------------------------------------------------

	private void showSelected() {
		TreePath p = tree.getSelectionPath();
		SkillTree.Node n = (p != null && p.getLastPathComponent() instanceof DefaultMutableTreeNode t && t != root)
			? nodeOf(t) : null;
		Readout d = (n == null) ? nothing() : switch (n.kind()) {
			case SKILL -> skill(n.skill());
			case SKILLSET -> directory(n);
			case MISSING -> missing(n);
		};
		detail.removeAll();
		detail.add(Scrolls.vertical(d), BorderLayout.CENTER);
		detail.revalidate();
		detail.repaint();
	}

	private Readout nothing() {
		return new Readout().note(root.getChildCount() == 0
			? "The assistant names no skillsets, so there is nothing for it to discover."
			: "Choose a skill to see what it does, the tools it grants and what it reveals.");
	}

	private static Readout skill(SkillIndex.Skill s) {
		Readout d = new Readout();
		d.pair("Name", s.name()).tip("What the assistant loads the skill by.");
		d.pair("Path", s.path()).tip("Where the skill lives on the venue: what a load refers to.");
		d.pair("Directory", s.skillset()).tip("The skillset or parent skill it is filed under.");
		if (s.shadowed()) {
			d.note("Shadowed: an earlier skillset has a skill of this name, which the index keeps, so the "
				+ "assistant never sees this one by name.");
		}
		d.section("What it is for").tip("Its description: the one line the assistant's index carries, "
			+ "and all it sees before loading.");
		d.prose((s.description() != null && !s.description().isBlank()) ? s.description() : "(no description)");
		d.section("Tools it grants").tip("Operations the assistant can call once the skill is loaded, and not before.");
		if (s.tools().isEmpty()) d.note("None — it teaches judgement, not mechanics.");
		else d.lines(true, s.tools());
		d.section("What it reveals").tip("Skills and directories that enter the index only once this one is loaded.");
		if (s.children().isEmpty()) d.note("Nothing further.");
		else d.lines(true, s.children());
		return d;
	}

	private static Readout directory(SkillTree.Node n) {
		Readout d = new Readout();
		d.pair("Path", n.path()).tip("A directory of skills on the venue.");
		int count = n.children().size();
		d.pair("Skills", (count == 0) ? "none yet" : Integer.toString(count));
		d.note("A skillset: a directory of skills the assistant can discover. The ones it searches are at the "
			+ "root, in order; the first skill of a name wins, and a later namesake is shadowed. A directory "
			+ "beneath a skill is one that skill reveals when loaded.");
		return d;
	}

	private static Readout missing(SkillTree.Node n) {
		Readout d = new Readout();
		d.pair("Path", n.path(), Styles.MUTED);
		d.note("The revealing skill names this, but the index has nothing for it: it could not be read, "
			+ "or it is the same skill as one listed under another path.");
		return d;
	}

	/** Names alone, no icons; directories, shadowed skills and missing reveals muted. */
	private static final class Renderer extends DefaultTreeCellRenderer {
		@Override
		public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
				boolean leaf, int row, boolean focus) {
			// Skill names and paths are often an agent's writing: shown literally, never as HTML.
			Labels.plain(this);
			super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
			setIcon(null);
			if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof SkillTree.Node node) {
				boolean plain = node.kind() == SkillTree.Kind.SKILL && !node.skill().shadowed();
				setText(plain ? node.label()
					: node.kind() == SkillTree.Kind.SKILLSET ? node.path() + "  ·  " + node.children().size()
					: node.kind() == SkillTree.Kind.MISSING ? node.path() + "  ·  not found"
					: node.label() + "  ·  shadowed");
				if (!selected) setForeground(plain ? Theme.foreground() : Theme.muted());
				setToolTipText(node.kind() == SkillTree.Kind.SKILL ? node.skill().description() : node.path());
			}
			return this;
		}
	}
}
