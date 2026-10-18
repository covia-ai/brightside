package brightside.ui.files;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;

import brightside.files.DirectoryCache;
import brightside.files.DirectoryCache.Location;
import brightside.files.FileSource;
import brightside.files.FileSource.Entry;
import brightside.files.FileSource.Listing;
import brightside.files.FileSource.Root;
import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/** Lazy directory tree, folder contents and literal previews, independent of storage providers. */
@SuppressWarnings("serial")
public final class FileBrowser extends JPanel {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
	private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
	private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
	private final JTree tree = new JTree(treeModel);
	private final DefaultListModel<Entry> entries = new DefaultListModel<>();
	private final JList<Entry> files = new JList<>(entries);
	private final JTextField filter = new JTextField();
	private final JPanel breadcrumbs = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
	private final JLabel folderTitle = Labels.section("Your files");
	private final JLabel count = Labels.small("Choose a folder to get started");
	private final JLabel status = Labels.small(" ");
	private final JLabel empty = Labels.muted("Choose a folder from the sidebar.");
	private final JPanel contents = new JPanel(new CardLayout());
	private final JPanel detail = panel(new BorderLayout());
	private final JButton back = iconButton("arrow-left", "Back (Alt+Left)");
	private final JButton up = iconButton("arrow-up", "Parent folder (Alt+Up)");
	private final JButton refresh = iconButton("refresh-cw", "Refresh files (F5)");
	private final JButton previewToggle = iconButton("eye", "Hide file preview");
	private final JSplitPane folderSplit;
	private final ArrayDeque<Location> history = new ArrayDeque<>();
	private final Set<Location> expanded = new HashSet<>();
	private DirectoryCache reads;
	private Location current;
	private Listing listing;
	private long epoch;
	private long navigation;
	private long previewVersion;
	private boolean selectingTree;
	private boolean refreshing;
	private boolean previewShown = true;
	private boolean wasCompact;

	public FileBrowser(Runnable storageSettings) {
		super(new BorderLayout());
		JPanel heading = panel(new BorderLayout(12, 0));
		heading.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 16));
		JPanel titles = panel(new BorderLayout(0, 3));
		titles.add(Labels.section("Files"), BorderLayout.NORTH);
		titles.add(Labels.small("Your folders and personal drives"), BorderLayout.SOUTH);
		heading.add(titles, BorderLayout.CENTER);
		JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton settings = Buttons.secondary("Storage settings");
		settings.addActionListener(e -> storageSettings.run());
		actions.add(settings);
		actions.add(previewToggle);
		actions.add(refresh);
		heading.add(actions, BorderLayout.EAST);
		add(heading, BorderLayout.NORTH);

		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setRowHeight(UIScale.scale(32));
		tree.setBorder(BorderFactory.createEmptyBorder(4, 6, 12, 8));
		tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
		Styles.classes(tree, "files");
		tree.setCellRenderer(new TreeRenderer());
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		ToolTipManager.sharedInstance().registerComponent(tree);
		tree.addTreeSelectionListener(e -> {
			if (!selectingTree && tree.getLastSelectedPathComponent() instanceof Directory node) navigate(node.location, true);
		});
		tree.addTreeWillExpandListener(new TreeWillExpandListener() {
			@Override public void treeWillExpand(TreeExpansionEvent e) {
				if (e.getPath().getLastPathComponent() instanceof Directory node) {
					expanded.add(node.location);
					if (!selectingTree) load(node);
				}
			}
			@Override public void treeWillCollapse(TreeExpansionEvent e) {
				if (e.getPath().getLastPathComponent() instanceof Directory node) expanded.remove(node.location);
			}
		});
		JPanel sidebar = panel(new BorderLayout());
		sidebar.setBorder(Borders.hairlineRight());
		JLabel locations = Labels.caption("LOCATIONS");
		locations.setBorder(BorderFactory.createEmptyBorder(12, 18, 8, 12));
		sidebar.add(locations, BorderLayout.NORTH);
		sidebar.add(Scrolls.both(tree), BorderLayout.CENTER);
		sidebar.setMinimumSize(new Dimension(UIScale.scale(150), 0));

		JPanel toolbar = panel(new BorderLayout(8, 0));
		toolbar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 12));
		JPanel travel = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		travel.add(back);
		travel.add(up);
		toolbar.add(travel, BorderLayout.WEST);
		toolbar.add(breadcrumbs, BorderLayout.CENTER);

		JPanel directory = panel(new BorderLayout(0, 10));
		directory.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
		JPanel folderHeading = panel(new BorderLayout(0, 10));
		JPanel folderLabels = panel(new BorderLayout(0, 4));
		folderLabels.add(folderTitle, BorderLayout.NORTH);
		folderLabels.add(count, BorderLayout.SOUTH);
		folderHeading.add(folderLabels, BorderLayout.NORTH);
		filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter this folder…");
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Lucide.icon("search", 16, Theme::muted));
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		filter.getAccessibleContext().setAccessibleName("Filter this folder");
		folderHeading.add(filter, BorderLayout.SOUTH);
		directory.add(folderHeading, BorderLayout.NORTH);
		files.setCellRenderer(new FileRenderer());
		files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		files.setFixedCellHeight(UIScale.scale(56));
		files.setOpaque(false);
		files.getAccessibleContext().setAccessibleName("Folder contents");
		files.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectFile(); });
		files.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				int row = files.locationToIndex(e.getPoint());
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && row >= 0
						&& files.getCellBounds(row, row).contains(e.getPoint())) openDirectory();
			}
		});
		files.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
		files.getActionMap().put("open", action(this::openDirectory));
		contents.setOpaque(false);
		contents.add(Scrolls.vertical(files), "files");
		JPanel emptyPanel = panel(new java.awt.GridBagLayout());
		emptyPanel.add(empty);
		contents.add(emptyPanel, "empty");
		directory.add(contents, BorderLayout.CENTER);
		directory.setMinimumSize(new Dimension(UIScale.scale(200), UIScale.scale(180)));
		detail.setMinimumSize(new Dimension(UIScale.scale(220), UIScale.scale(140)));
		folderSplit = split(directory, detail, 0.55);
		JPanel workspace = panel(new BorderLayout());
		workspace.add(toolbar, BorderLayout.NORTH);
		workspace.add(folderSplit, BorderLayout.CENTER);
		JSplitPane layout = split(sidebar, workspace, 0);
		layout.setDividerLocation(UIScale.scale(230));
		add(layout, BorderLayout.CENTER);
		status.setBorder(BorderFactory.createEmptyBorder(8, 18, 10, 18));
		add(status, BorderLayout.SOUTH);
		addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) { responsive(); }
		});
		back.addActionListener(e -> goBack());
		up.addActionListener(e -> { if (current != null) navigate(current.parent(), true); });
		refresh.addActionListener(e -> refresh());
		previewToggle.addActionListener(e -> setPreviewShown(!previewShown));
		shortcut("alt LEFT", "back", this::goBack);
		shortcut("alt UP", "up", () -> { if (current != null) navigate(current.parent(), true); });
		shortcut("F5", "refresh", this::refresh);
		filter.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { filter(); }
			@Override public void removeUpdate(DocumentEvent e) { filter(); }
			@Override public void changedUpdate(DocumentEvent e) { filter(); }
		});
		clear();
	}

	/** All public view operations run on the event thread. */
	public void bind(FileSource source) { clear(); reads = new DirectoryCache(source); buttons(); }

	public void clear() {
		epoch++;
		navigation++;
		reads = null;
		current = null;
		listing = null;
		refreshing = false;
		history.clear();
		expanded.clear();
		treeRoot.removeAllChildren();
		treeModel.reload();
		entries.clear();
		filter.setText("");
		breadcrumbs.removeAll();
		folderTitle.setText("Your files");
		count.setText("Choose a folder to get started");
		message("Choose a folder from the sidebar.");
		resetPreview();
		status.setText(" ");
		buttons();
	}

	public void refresh() {
		if (reads == null) return;
		long version = ++epoch;
		navigation++;
		refreshing = true;
		reads.clear();
		listing = null;
		entries.clear();
		resetPreview();
		message("Loading your storage…");
		buttons();
		reads.roots().whenComplete((roots, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != epoch) return;
			refreshing = false;
			buttons();
			if (failure != null) {
				treeRoot.removeAllChildren();
				treeModel.reload();
				message(error(failure));
				status.setText("Refresh to try again.");
				return;
			}
			selectingTree = true;
			treeRoot.removeAllChildren();
			for (String kind : List.of("Personal drive", "Folder")) {
				DefaultMutableTreeNode group = new DefaultMutableTreeNode(kind.equals("Folder") ? "On this computer" : "Personal drives");
				for (Root root : roots) if (root.kind().equals(kind)) group.add(new Directory(new Location(root, "")));
				if (group.getChildCount() > 0) treeRoot.add(group);
			}
			for (Root root : roots) if (!root.kind().equals("Folder") && !root.kind().equals("Personal drive")) treeRoot.add(new Directory(new Location(root, "")));
			treeModel.reload();
			for (int i = 0; i < treeRoot.getChildCount(); i++) {
				var group = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
				if (!(group instanceof Directory)) tree.expandPath(new TreePath(group.getPath()));
			}
			selectingTree = false;
			for (Directory node : rootNodes()) if (expanded.contains(node.location)) { load(node); tree.expandPath(new TreePath(node.getPath())); }
			if (roots.isEmpty()) {
				current = null;
				history.clear();
				breadcrumbs.removeAll();
				folderTitle.setText("Your files");
				count.setText("No storage available");
				message("Add a folder or drive in Storage settings.");
				buttons();
			} else {
				if (current == null || !roots.contains(current.root())) {
					history.clear();
					current = rootNodes().get(0).location;
				}
				navigate(current, false);
			}
		}));
	}

	private List<Directory> rootNodes() {
		java.util.ArrayList<Directory> nodes = new java.util.ArrayList<>();
		for (var e = treeRoot.breadthFirstEnumeration(); e.hasMoreElements();) {
			if (e.nextElement() instanceof Directory node && node.location.path().isEmpty()) nodes.add(node);
		}
		return nodes;
	}

	/** Completes after the event-thread model update, so selection can follow the loaded branch. */
	private CompletableFuture<Void> load(Directory node) {
		if (node.loaded) return CompletableFuture.completedFuture(null);
		if (node.pending != null) return node.pending;
		if (reads == null) return CompletableFuture.failedFuture(new IllegalStateException("Storage is unavailable."));
		long version = epoch;
		CompletableFuture<Void> pending = new CompletableFuture<>();
		node.pending = pending;
		reads.list(node.location).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != epoch || node.getRoot() != treeRoot) { pending.cancel(false); return; }
			node.pending = null;
			node.removeAllChildren();
			if (failure == null) {
				node.loaded = true;
				for (Entry entry : result.entries()) if (entry.directory()) node.add(new Directory(node.location.child(entry.name())));
			} else node.add(new DefaultMutableTreeNode("Cannot read · collapse and retry", false));
			boolean open = expanded.contains(node.location);
			boolean selected = tree.getLastSelectedPathComponent() == node;
			selectingTree = true;
			treeModel.nodeStructureChanged(node);
			if (open) tree.expandPath(new TreePath(node.getPath()));
			if (selected) tree.setSelectionPath(new TreePath(node.getPath()));
			selectingTree = false;
			if (failure == null) {
				for (int i = 0; i < node.getChildCount(); i++) {
					Directory child = (Directory) node.getChildAt(i);
					if (expanded.contains(child.location)) { load(child); tree.expandPath(new TreePath(child.getPath())); }
				}
				pending.complete(null);
			} else pending.completeExceptionally(failure);
		}));
		return pending;
	}

	private void navigate(Location next, boolean remember) {
		if (reads == null || refreshing) return;
		if (compact() && previewShown) setPreviewShown(false);
		if (remember && current != null && !current.equals(next)) history.push(current);
		current = next;
		long version = ++navigation;
		listing = null;
		entries.clear();
		filter.setText("");
		resetPreview();
		folderTitle.setText(next.name());
		count.setText(next.root().kind());
		crumbs();
		message("Loading folder…");
		status.setText(" ");
		buttons();
		reads.list(next).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != navigation) return;
			if (failure != null) { message(error(failure)); status.setText("Use Up to return, or Refresh to try again."); return; }
			listing = result;
			filter();
			status.setText(result.warning().isEmpty() ? "Double-click a folder to open it · Select a file to preview" : result.warning());
		}));
		for (Directory node : rootNodes()) if (node.location.root().equals(next.root())) reveal(node, next, version);
	}

	private void reveal(Directory node, Location target, long version) {
		if (version != navigation) return;
		if (node.location.equals(target)) {
			selectingTree = true;
			TreePath path = new TreePath(node.getPath());
			tree.setSelectionPath(path);
			tree.scrollPathToVisible(path);
			selectingTree = false;
			load(node);
			return;
		}
		load(node).whenComplete((ignored, failure) -> {
			if (version != navigation || failure != null) return;
			for (int i = 0; i < node.getChildCount(); i++) {
				Directory child = (Directory) node.getChildAt(i);
				if (target.path().equals(child.location.path()) || target.path().startsWith(child.location.path() + "/")) {
					tree.expandPath(new TreePath(node.getPath()));
					reveal(child, target, version);
					return;
				}
			}
		});
	}

	private void filter() {
		if (listing == null) return;
		Entry selected = files.getSelectedValue();
		String query = filter.getText().strip().toLowerCase(Locale.ROOT);
		entries.clear();
		for (Entry entry : listing.entries()) if (entry.name().toLowerCase(Locale.ROOT).contains(query)) entries.addElement(entry);
		if (selected != null && entries.contains(selected)) files.setSelectedValue(selected, true);
		count.setText(query.isEmpty() ? listing.entries().size() + " items" : entries.size() + " of " + listing.entries().size() + " items");
		if (entries.isEmpty()) message(query.isEmpty() ? "This folder is empty." : "No files match this filter.");
		else ((CardLayout) contents.getLayout()).show(contents, "files");
	}

	private void openDirectory() {
		Entry entry = files.getSelectedValue();
		if (current != null && entry != null && entry.directory()) navigate(current.child(entry.name()), true);
	}

	private void selectFile() {
		resetPreview();
		if (!previewShown) return;
		Entry entry = files.getSelectedValue();
		if (current == null || entry == null) return;
		Location target = current.child(entry.name());
		Readout info = metadata(entry);
		if (entry.directory()) { showDetail(info.link("Open folder", () -> navigate(target, true))); return; }
		if (!"file".equals(entry.type())) { showDetail(info.note("Preview is unavailable for this type of file.")); return; }
		showDetail(info.note("Loading preview…"));
		long version = previewVersion;
		reads.preview(target).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != previewVersion) return;
			Readout d = metadata(entry);
			if (failure != null) d.note(error(failure));
			else if (result.binary()) d.note("Text preview is unavailable for this file.");
			else {
				d.section("Preview");
				if (result.truncated()) d.note("Showing the first 64 KiB.");
				if (result.text().isEmpty()) d.note("This file is empty.");
				else d.code(result.text());
			}
			showDetail(d);
		}));
	}

	private static Readout metadata(Entry entry) {
		Readout d = new Readout().section(entry.name())
			.caption(kind(entry) + (entry.directory() ? "" : " · " + displaySize(entry.size())));
		if (entry.modified() > 0) d.note("Modified " + DATE.format(Instant.ofEpochMilli(entry.modified())));
		return d;
	}

	private void resetPreview() {
		previewVersion++;
		showDetail(new Readout().caption("FILE DETAILS").section("A closer look")
			.note("Select a file to see its details and a text preview."));
	}

	private void showDetail(Readout readout) {
		detail.removeAll();
		Card surface = new Card();
		surface.setLayout(new BorderLayout());
		surface.add(Scrolls.vertical(readout));
		detail.setBorder(BorderFactory.createEmptyBorder(10, 4, 8, 12));
		detail.add(surface);
		detail.revalidate();
		detail.repaint();
	}

	private void crumbs() {
		breadcrumbs.removeAll();
		Location root = new Location(current.root(), "");
		breadcrumbs.add(Buttons.link(root.name(), () -> navigate(root, true)));
		String[] parts = current.path().isEmpty() ? new String[0] : current.path().split("/");
		Location path = root;
		for (int i = 0; i < parts.length; i++) {
			path = path.child(parts[i]);
			Location target = path;
			if (i < parts.length - 2) {
				if (i == parts.length - 3) breadcrumbs.add(Buttons.link("…", () -> navigate(target, true)));
				continue;
			}
			breadcrumbs.add(Labels.icon(Lucide.icon("chevron-right", 12, Theme::muted)));
			breadcrumbs.add(Buttons.link(parts[i], () -> navigate(target, true)));
		}
		breadcrumbs.setToolTipText(current.root().name() + "/" + current.path());
		breadcrumbs.revalidate();
		breadcrumbs.repaint();
	}

	private void goBack() { if (!refreshing && !history.isEmpty()) navigate(history.pop(), false); }
	private void buttons() {
		tree.setEnabled(reads != null && !refreshing);
		refresh.setEnabled(reads != null && !refreshing);
		back.setEnabled(!refreshing && !history.isEmpty());
		up.setEnabled(!refreshing && current != null && !current.path().isEmpty());
		filter.setEnabled(!refreshing && current != null);
	}
	private void message(String text) { empty.setText(text); ((CardLayout) contents.getLayout()).show(contents, "empty"); }
	private void responsive() {
		int orientation = getWidth() < UIScale.scale(900) ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
		if (folderSplit.getOrientation() != orientation) {
			folderSplit.setOrientation(orientation);
			folderSplit.setResizeWeight(0.55);
			placePreview();
		}
		boolean compact = compact();
		if (compact && !wasCompact) setPreviewShown(false);
		else if (compact != wasCompact) placePreview();
		wasCompact = compact;
	}
	private boolean compact() {
		return getWidth() > 0 && getHeight() > 0 && getWidth() < UIScale.scale(900) && getHeight() < UIScale.scale(480);
	}
	private void setPreviewShown(boolean show) {
		previewShown = show;
		placePreview();
		String label = show ? "Hide file preview" : "Show file preview";
		previewToggle.setToolTipText(label);
		previewToggle.getAccessibleContext().setAccessibleName(label);
		previewToggle.setIcon(Lucide.icon(show ? "eye" : "eye-off", 18, Theme::muted));
		selectFile();
	}
	private void placePreview() {
		detail.setVisible(previewShown);
		folderSplit.getLeftComponent().setVisible(!compact() || !previewShown);
		folderSplit.setDividerSize(previewShown && !compact() ? UIScale.scale(6) : 0);
		folderSplit.setDividerLocation(previewShown ? (compact() ? 0.0 : 0.55) : 1.0);
	}
	private void shortcut(String key, String name, Runnable task) {
		getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
		getActionMap().put(name, action(task));
	}
	private static AbstractAction action(Runnable task) { return new AbstractAction() {
		@Override public void actionPerformed(ActionEvent e) { task.run(); }
	}; }
	private static JPanel panel(java.awt.LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
	private static JButton iconButton(String icon, String tip) {
		JButton button = Buttons.icon(Lucide.icon(icon, 18, Theme::muted), tip);
		button.getAccessibleContext().setAccessibleName(tip);
		return button;
	}
	private static JSplitPane split(Component left, Component right, double weight) {
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setBorder(null);
		split.setContinuousLayout(true);
		split.setResizeWeight(weight);
		split.setDividerSize(UIScale.scale(6));
		return split;
	}
	private static String error(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return "Could not read: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
	}
	private static String kind(Entry entry) { return entry.directory() ? "Folder" : "file".equals(entry.type()) ? "File" : entry.type(); }
	private static String displaySize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
	}

	private static final class Directory extends DefaultMutableTreeNode {
		final Location location;
		boolean loaded;
		CompletableFuture<Void> pending;
		Directory(Location location) {
			super(location.name());
			this.location = location;
			add(new DefaultMutableTreeNode("Loading…", false));
		}
	}

	private static final class TreeRenderer extends DefaultTreeCellRenderer {
		private final Icon folder = Lucide.icon("folder", 17, Theme::accentText);
		private final Icon drive = Lucide.icon("hard-drive", 17, Theme::accentText);
		@Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
				boolean open, boolean leaf, int row, boolean focus) {
			Labels.plain(this);
			super.getTreeCellRendererComponent(tree, value, selected, open, leaf, row, focus);
			if (value instanceof Directory node) {
				setIcon(node.location.path().isEmpty() && node.location.root().kind().equals("Personal drive") ? drive : folder);
				setToolTipText(node.location.root().name() + "/" + node.location.path());
			} else { setIcon(null); setToolTipText(null); }
			if (!selected) setForeground(value instanceof Directory ? Theme.foreground() : Theme.muted());
			setIconTextGap(UIScale.scale(8));
			return this;
		}
	}

	private static final class FileRenderer extends Card implements ListCellRenderer<Entry> {
		private final JLabel name = Labels.heading("");
		private final JLabel meta = Labels.small("");
		private final JLabel icon = Labels.icon(null);
		private final JLabel trailing = Labels.icon(null);
		private final Icon folder = Lucide.icon("folder", 24, Theme::accentText);
		private final Icon file = Lucide.icon("file-text", 24, Theme::muted);
		private final Icon chevron = Lucide.icon("chevron-right", 16, Theme::muted);
		FileRenderer() {
			setLayout(new BorderLayout(12, 0));
			setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
			JPanel labels = panel(new BorderLayout(0, 4));
			labels.add(name, BorderLayout.NORTH);
			labels.add(meta, BorderLayout.SOUTH);
			add(icon, BorderLayout.WEST);
			add(labels, BorderLayout.CENTER);
			add(trailing, BorderLayout.EAST);
		}
		@Override public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry,
				int index, boolean selected, boolean focus) {
			name.setText(entry.name());
			meta.setText((entry.directory() ? "Folder" : displaySize(entry.size()))
				+ (entry.modified() > 0 ? "  ·  " + DATE.format(Instant.ofEpochMilli(entry.modified())) : ""));
			icon.setIcon(entry.directory() ? folder : file);
			trailing.setIcon(entry.directory() ? chevron : null);
			fill(selected ? () -> Theme.blend(Theme.panel(), Theme.accent(), 0.13f) : Theme::panel);
			outline(focus ? Theme::accent : () -> null);
			return this;
		}
	}
}
