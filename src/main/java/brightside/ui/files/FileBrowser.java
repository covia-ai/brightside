package brightside.ui.files;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
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
import brightside.files.FileChanges;
import brightside.files.FileChanges.Item;
import brightside.files.FileChanges.Outcome;
import brightside.files.FileSource;
import brightside.files.FileSource.Entry;
import brightside.files.FileSource.Listing;
import brightside.files.FileSource.Root;
import brightside.ui.components.Badge;
import brightside.ui.components.Borders;
import brightside.ui.components.Buttons;
import brightside.ui.components.Card;
import brightside.ui.components.EmptyState;
import brightside.ui.components.EyeButton;
import brightside.ui.components.Labels;
import brightside.ui.components.Loading;
import brightside.ui.components.Lucide;
import brightside.ui.components.Panels;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Toast;
import brightside.ui.components.Tone;
import net.miginfocom.swing.MigLayout;

/** Lazy directory tree, folder contents and literal previews, independent of storage providers. */
@SuppressWarnings("serial")
public final class FileBrowser extends JPanel {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
	private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
	private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
	private final JTree tree = new JTree(treeModel);
	private final DefaultListModel<Entry> entries = new DefaultListModel<>();
	private final JList<Entry> files = new JList<>(entries) {
		/** A row that opens says how; the list answers for its rows, so the renderer needn't. */
		@Override
		public String getToolTipText(MouseEvent e) {
			int row = rowAt(e.getPoint());
			if (row < 0 || current == null) return null;
			Entry entry = entries.get(row);
			return (entry.directory() || current.child(entry.name()).local() != null) ? "Double-click or press Enter to open" : null;
		}
	};
	private final FileActions actions = new FileActions(this);
	private final JTextField filter = new JTextField();
	private final JPanel breadcrumbs = panel(new MigLayout("insets 0, gap 0, aligny center", "", ""));
	/** The line under the list: how many items, and any warning the listing carries. */
	private final JLabel status = Labels.small(" ");
	/** What the folder area shows instead of the list: loading, empty, or why it can't be read. */
	private final JPanel empty = panel(new java.awt.GridBagLayout());
	private final JPanel contents = new JPanel(new CardLayout());
	private final JPanel detail = panel(new BorderLayout());
	private final JButton back = iconButton("arrow-left", "Back (Alt+Left)");
	private final JButton up = iconButton("arrow-up", "Parent folder (Alt+Up)");
	private final JButton refresh = iconButton("refresh-cw", "Refresh files (F5)");
	private final EyeButton previewToggle = new EyeButton("Show file preview", "Hide file preview", 18);
	private final JPanel sidebar = panel(new BorderLayout());
	/** Under the sidebar: folder changes saved here wait for the next start. */
	private final JLabel restart = Labels.html(Labels.escape("Folder changes apply when Brightside restarts."), 180, javax.swing.SwingConstants.LEFT);
	private final PlaceActions placeActions;
	private final JSplitPane folderSplit;
	private final ArrayDeque<Location> history = new ArrayDeque<>();
	private final Set<Location> expanded = new HashSet<>();
	private DirectoryCache reads;
	private FileChanges changes;
	private Location current;
	private Listing listing;
	/** The places the sidebar shows, as last read. */
	private List<Root> roots = List.of();
	/** A place to open once the next refresh has read the places: one just made. */
	private String opening;
	private long epoch;
	private long navigation;
	private long previewVersion;
	private boolean selectingTree;
	private boolean refreshing;
	private boolean previewShown = true;
	private boolean wasCompact;

	public FileBrowser(Places places) {
		super(new BorderLayout());
		placeActions = new PlaceActions(this, places);
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setRowHeight(UIScale.scale(26));
		tree.setBorder(BorderFactory.createEmptyBorder(4, 6, 12, 8));
		tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
		Styles.classes(tree, "files");
		tree.setCellRenderer(new TreeRenderer());
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		ToolTipManager.sharedInstance().registerComponent(tree);
		tree.addTreeSelectionListener(e -> {
			if (!selectingTree && tree.getLastSelectedPathComponent() instanceof Directory node) navigate(node.location, true);
		});
		tree.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) { treeMenu(e); }
			@Override public void mouseReleased(MouseEvent e) { treeMenu(e); }
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
		sidebar.setBorder(Borders.hairlineRight());
		// The places files live, with + to add one: a folder on this computer or a personal drive.
		JPanel header = panel(new MigLayout("insets 6 18 2 8, fillx, aligny center", "[grow]4[]", ""));
		header.add(Labels.overline("Locations"));
		JButton add = iconButton("plus", "Add a folder or a drive");
		add.addActionListener(e -> placeActions.addMenu().show(add, 0, add.getHeight()));
		header.add(add);
		sidebar.add(header, BorderLayout.NORTH);
		sidebar.add(Scrolls.both(tree), BorderLayout.CENTER);
		Styles.classes(restart, Styles.SMALL, Styles.MUTED);
		restart.setBorder(BorderFactory.createEmptyBorder(8, 18, 10, 12));
		restart.setVisible(false);
		sidebar.add(restart, BorderLayout.SOUTH);
		sidebar.setMinimumSize(new Dimension(UIScale.scale(150), 0));

		// One toolbar over the folder: where you are on the left, the filter and
		// the view's actions on the right. The breadcrumb is the folder's title.
		JPanel toolbar = panel(new BorderLayout(8, 0));
		toolbar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 12));
		JPanel travel = panel(new MigLayout("insets 0, gap 0, aligny center", "", ""));
		travel.add(back);
		travel.add(up);
		toolbar.add(travel, BorderLayout.WEST);
		toolbar.add(breadcrumbs, BorderLayout.CENTER);
		filter.setColumns(14);
		filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter this folder…");
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Lucide.icon("search", 16, Theme::muted));
		filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
		filter.getAccessibleContext().setAccessibleName("Filter this folder");
		JPanel tools = panel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		tools.add(filter);
		tools.add(Box.createHorizontalStrut(UIScale.scale(4)));
		tools.add(refresh);
		previewToggle.setShown(previewShown);
		tools.add(previewToggle);
		toolbar.add(tools, BorderLayout.EAST);

		JPanel directory = panel(new BorderLayout());
		directory.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
		files.setCellRenderer(new FileRenderer());
		files.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		// Every row is as tall as this one; the list measures it again when the theme changes.
		files.setPrototypeCellValue(new Entry("Prototype", "file", 1, 1));
		files.setOpaque(false);
		files.getAccessibleContext().setAccessibleName("Folder contents");
		ToolTipManager.sharedInstance().registerComponent(files);
		files.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectFile(); });
		files.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && rowAt(e.getPoint()) >= 0) openSelection();
			}
		});
		// Right-click: on a row, its menu (selecting it first if it isn't);
		// anywhere else in the folder, the folder's own.
		MouseAdapter menus = new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) { folderMenu(e); }
			@Override public void mouseReleased(MouseEvent e) { folderMenu(e); }
		};
		files.addMouseListener(menus);
		key(files, "ENTER", "open", this::openSelection);
		key(files, "F2", "rename", () -> { List<Item> items = selection(); if (items.size() == 1) actions.rename(items.get(0)); });
		key(files, "DELETE", "delete", () -> actions.delete(selection()));
		key(files, "shift F10", "menu", this::menuAtSelection);
		key(files, "CONTEXT_MENU", "menu", this::menuAtSelection);
		// Drag rows onto a folder, in the list or the sidebar; drop files from
		// this computer anywhere in the folder, an empty one included.
		files.setDragEnabled(true);
		files.setDropMode(DropMode.ON);
		files.setTransferHandler(new FileTransfer(this::selection, this::listTarget, actions));
		contents.setTransferHandler(new FileTransfer(List::of, s -> (changes == null || refreshing) ? null : current, actions));
		tree.setDropMode(DropMode.ON);
		tree.setTransferHandler(new FileTransfer(List::of, this::treeTarget, actions));
		contents.setOpaque(false);
		JScrollPane list = Scrolls.vertical(files);
		list.getViewport().addMouseListener(menus);
		empty.addMouseListener(menus);
		contents.add(list, "files");
		contents.add(empty, "empty");
		directory.add(contents, BorderLayout.CENTER);
		// How many items, under the list they count, its left edge on the rows' icons.
		status.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));
		directory.add(status, BorderLayout.SOUTH);
		directory.setMinimumSize(new Dimension(UIScale.scale(200), UIScale.scale(180)));
		detail.setMinimumSize(new Dimension(UIScale.scale(220), UIScale.scale(140)));
		folderSplit = split(directory, detail, 0.55);
		JPanel workspace = panel(new BorderLayout());
		workspace.add(toolbar, BorderLayout.NORTH);
		workspace.add(folderSplit, BorderLayout.CENTER);
		JSplitPane layout = split(sidebar, workspace, 0);
		layout.setDividerLocation(UIScale.scale(230));
		add(layout, BorderLayout.CENTER);
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
		shortcut("ctrl shift N", "newFolder", () -> { if (current != null && !refreshing) actions.newFolder(current); });
		filter.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { filter(); }
			@Override public void removeUpdate(DocumentEvent e) { filter(); }
			@Override public void changedUpdate(DocumentEvent e) { filter(); }
		});
		clear();
	}

	/** All public view operations run on the event thread. */
	public void bind(FileSource source) { clear(); reads = new DirectoryCache(source); changes = new FileChanges(source); buttons(); }

	public void clear() {
		epoch++;
		navigation++;
		reads = null;
		changes = null;
		current = null;
		listing = null;
		refreshing = false;
		roots = List.of();
		history.clear();
		expanded.clear();
		treeRoot.removeAllChildren();
		treeModel.reload();
		entries.clear();
		filter.setText("");
		breadcrumbs.removeAll();
		show(new EmptyState("folder", Tone.MUTED, "Your files", "Choose a folder from the sidebar.").bare());
		resetPreview();
		status(null);
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
		loading("Loading your storage…");
		buttons();
		placeActions.reload();
		reads.roots().whenComplete((roots, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != epoch) return;
			refreshing = false;
			this.roots = (failure == null) ? roots : List.of();
			buttons();
			if (failure != null) {
				treeRoot.removeAllChildren();
				treeModel.reload();
				show(unreadable("Couldn't read your storage", failure, "Refresh to try again."));
				status(null);
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
				show(new EmptyState("hard-drive", Tone.ACCENT, "No storage yet",
					"Add a folder on this computer, or make a personal drive with + beside Locations.")
					.bare().action("Add a folder…", placeActions::addFolder));
				status(null);
				buttons();
			} else {
				Root made = roots.stream().filter(r -> r.id().equals(opening)).findFirst().orElse(null);
				opening = null;
				if (made != null) {
					current = new Location(made, "");
				} else if (current == null || !roots.contains(current.root())) {
					history.clear();
					current = rootNodes().get(0).location;
				}
				navigate(current, false);
			}
		}));
	}

	/** Reads the places again and opens the one with {@code rootId} — a drive just made. */
	void refreshTo(String rootId) {
		opening = rootId;
		refresh();
	}

	/** The places the sidebar shows. */
	List<Root> roots() { return roots; }

	/** A folder change was saved: the sidebar says it waits for the next start. */
	void restartPending() {
		restart.setVisible(true);
		sidebar.revalidate();
	}

	void sidebarToast(String text) { Toast.show(sidebar, text, "check", Tone.SUCCESS); }

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
		int generation = node.generation;
		CompletableFuture<Void> pending = new CompletableFuture<>();
		node.pending = pending;
		reads.list(node.location).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != epoch || node.getRoot() != treeRoot || generation != node.generation) { pending.cancel(false); return; }
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
		crumbs();
		loading("Loading folder…");
		status(next.root().kind());
		buttons();
		reads.list(next).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != navigation) return;
			if (failure != null) {
				show(unreadable("Couldn't read this folder", failure, "Use Up to return, or Refresh to try again."));
				status(null);
				return;
			}
			listing = result;
			filter();
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

	private void filter() { filter(List.of()); }

	/** Shows the entries the filter matches, keeping what was selected — or selecting {@code select} when given. */
	private void filter(List<String> select) {
		if (listing == null) return;
		Set<String> keep = new HashSet<>(select.isEmpty() ? names(files.getSelectedValuesList()) : select);
		String query = query();
		entries.clear();
		for (Entry entry : listing.entries()) if (entry.name().toLowerCase(Locale.ROOT).contains(query)) entries.addElement(entry);
		int[] chosen = IntStream.range(0, entries.size()).filter(i -> keep.contains(entries.get(i).name())).toArray();
		if (chosen.length > 0) {
			files.setSelectedIndices(chosen);
			files.ensureIndexIsVisible(chosen[0]);
		}
		counts();
		if (entries.isEmpty()) {
			show(query.isEmpty() ? new EmptyState("folder", Tone.MUTED, "This folder is empty", null).bare()
				: new EmptyState("search", Tone.MUTED, "No matches", "Nothing in this folder matches the filter.").bare());
		} else ((CardLayout) contents.getLayout()).show(contents, "files");
	}

	/** The bottom line: how many items, how many the filter matches, and any warning the listing carries. */
	private void counts() {
		if (listing == null) { status(null); return; }
		int all = listing.entries().size();
		String count = query().isEmpty() ? items(all) : entries.size() + " of " + items(all);
		status(listing.warning().isEmpty() ? count : count + "  ·  " + listing.warning());
	}

	private String query() { return filter.getText().strip().toLowerCase(Locale.ROOT); }

	private static String items(int n) {
		return n + (n == 1 ? " item" : " items");
	}

	private static List<String> names(List<Entry> list) { return list.stream().map(Entry::name).toList(); }

	/** The selected entries, as items in the open folder. */
	private List<Item> selection() {
		if (current == null) return List.of();
		return files.getSelectedValuesList().stream().map(e -> new Item(current.child(e.name()), e.directory())).toList();
	}

	private void openSelection() {
		List<Item> items = selection();
		if (items.size() == 1) actions.open(items.get(0));
	}

	/** The list row at {@code p}, or -1 off the rows. */
	private int rowAt(Point p) {
		int row = files.locationToIndex(p);
		return (row >= 0 && files.getCellBounds(row, row).contains(p)) ? row : -1;
	}

	private void folderMenu(MouseEvent e) {
		if (!e.isPopupTrigger() || current == null || changes == null || refreshing) return;
		int row = (e.getComponent() == files) ? rowAt(e.getPoint()) : -1;
		if (row < 0) files.clearSelection();
		else if (!files.isSelectedIndex(row)) files.setSelectedIndex(row);
		showMenu(e.getComponent(), e.getX(), e.getY());
	}

	/** The menu from the keyboard, beside the row in focus. */
	private void menuAtSelection() {
		if (current == null || changes == null || refreshing) return;
		int lead = files.getLeadSelectionIndex();
		Rectangle cell = (lead >= 0 && files.isSelectedIndex(lead)) ? files.getCellBounds(lead, lead) : null;
		if (cell == null) showMenu(files, UIScale.scale(24), UIScale.scale(24));
		else showMenu(files, cell.x + UIScale.scale(48), cell.y + cell.height / 2);
	}

	private void showMenu(Component c, int x, int y) {
		List<Item> items = selection();
		(items.isEmpty() ? actions.folderMenu(current, false) : actions.itemsMenu(items)).show(c, x, y);
	}

	/** Where a drop on the list lands: into a folder row, else into the open folder. */
	private Location listTarget(TransferHandler.TransferSupport support) {
		if (current == null || changes == null || refreshing) return null;
		int row = rowAt(support.getDropLocation().getDropPoint());
		return (row >= 0 && entries.get(row).directory()) ? current.child(entries.get(row).name()) : current;
	}

	/** Where a drop on the sidebar lands: into the folder under it, and nowhere else. */
	private Location treeTarget(TransferHandler.TransferSupport support) {
		if (changes == null || refreshing) return null;
		TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
		return (path != null && path.getLastPathComponent() instanceof Directory node) ? node.location : null;
	}

	private void treeMenu(MouseEvent e) {
		if (!e.isPopupTrigger() || changes == null || refreshing) return;
		// The whole row answers, as the whole row shows the selection.
		TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
		Rectangle row = (path == null) ? null : tree.getPathBounds(path);
		if (row == null || e.getY() < row.y || e.getY() >= row.y + row.height) return;
		if (!(path.getLastPathComponent() instanceof Directory node)) return;
		JPopupMenu menu = actions.folderMenu(node.location, true);
		// A place itself — a folder on this computer — also says what access Brightside has to it.
		if (node.location.path().isEmpty()) placeActions.extend(menu, node.location.root());
		menu.show(tree, e.getX(), e.getY());
	}

	FileChanges changes() { return changes; }

	/** Changes as the binding changes: a change finishing after it is not applied. */
	long binding() { return epoch; }

	Location current() { return current; }

	void open(Location folder) { navigate(folder, true); }

	/** The status line says what a change is doing until it is done. */
	void working(String doing) { status(doing); }

	void toast(String text) { Toast.show(contents, text, "check", Tone.SUCCESS); }

	/** The names {@code folder} holds, when it is the open folder; empty for another. */
	List<String> namesIn(Location folder) {
		return (listing != null && folder.equals(current)) ? names(listing.entries()) : List.of();
	}

	/**
	 * After a change: read again only the folders it touched, step out of a
	 * folder that is gone, and select what it made in the open folder.
	 */
	void apply(Outcome outcome, Location into) {
		if (reads == null) return;
		for (Location gone : outcome.gone()) {
			reads.forget(gone);
			expanded.removeIf(l -> l.within(gone));
			history.removeIf(l -> l.within(gone));
		}
		for (Location folder : outcome.changed()) reads.forget(folder);
		reloadTree(outcome.changed());
		if (current == null) return;
		Location away = null;
		for (Location gone : outcome.gone()) {
			if (!current.within(gone)) continue;
			// One folder renamed or moved is followed to where it is now; one deleted leaves its parent open.
			boolean followed = into != null && outcome.gone().size() == 1 && outcome.done().size() == 1;
			away = followed ? new Location(into.root(), into.child(outcome.done().get(0)).path()
				+ current.path().substring(gone.path().length())) : gone.parent();
		}
		if (away != null) navigate(away, false);
		else if (outcome.changed().contains(current)) relist(current.equals(into) ? outcome.done() : List.of());
		else counts();
	}

	/** The open folder read again in place, without the loading state between. */
	private void relist(List<String> select) {
		long version = navigation;
		reads.list(current).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != navigation) return;
			if (failure != null) {
				show(unreadable("Couldn't read this folder", failure, "Use Up to return, or Refresh to try again."));
				status(null);
				return;
			}
			listing = result;
			filter(select);
		}));
	}

	/** Folders in the sidebar whose contents changed are read again, so their folders and arrows stay true. */
	private void reloadTree(Set<Location> changed) {
		List<Directory> nodes = new java.util.ArrayList<>();
		for (var e = treeRoot.breadthFirstEnumeration(); e.hasMoreElements();) {
			if (e.nextElement() instanceof Directory node && changed.contains(node.location)) nodes.add(node);
		}
		if (nodes.isEmpty()) return;
		for (Directory node : nodes) {
			if (node.getRoot() != treeRoot) continue; // gone with a folder reloaded before it
			node.generation++;
			node.pending = null;
			node.loaded = false;
			load(node);
		}
		if (current != null) for (Directory node : rootNodes()) if (node.location.root().equals(current.root())) reveal(node, current, navigation);
	}

	private void selectFile() {
		resetPreview();
		if (!previewShown) return;
		List<Entry> selected = files.getSelectedValuesList();
		if (current == null || selected.isEmpty()) return;
		if (selected.size() > 1) { showDetail(null, several(selected)); return; }
		Entry entry = selected.get(0);
		Location target = current.child(entry.name());
		if (entry.directory()) {
			JButton open = Buttons.plain("Open folder");
			open.addActionListener(e -> navigate(target, true));
			showDetail(entry, Panels.centred(open));
			return;
		}
		if (!"file".equals(entry.type())) { showDetail(entry, quiet("Preview is unavailable for this type of file.")); return; }
		showDetail(entry, Panels.centred(new Loading("Loading preview…")));
		long version = previewVersion;
		reads.preview(target).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
			if (version != previewVersion) return;
			if (failure != null) {
				showDetail(entry, new EmptyState("circle-alert", Tone.ERROR, "Couldn't preview this file",
					Labels.escape(reason(failure))).bare());
			} else if (result.binary()) {
				showDetail(entry, quiet("There is no text preview for this file."));
			} else if (result.text().isEmpty()) {
				showDetail(entry, quiet("This file is empty."));
			} else {
				Readout d = new Readout();
				if (result.truncated()) d.note("Showing the first 64 KiB.");
				d.code(result.text());
				showDetail(entry, Scrolls.vertical(d));
			}
		}));
	}

	private void resetPreview() {
		previewVersion++;
		showDetail(null, new EmptyState("file-text", Tone.MUTED, "No file selected", "Select a file to preview it.").bare());
	}

	/** The detail pane: a header naming {@code entry} (none when null) over {@code body}, on a card. */
	private void showDetail(Entry entry, JComponent body) {
		detail.removeAll();
		detail.setBorder(BorderFactory.createEmptyBorder(10, 4, 8, 12));
		if (entry != null) detail.add(header(entry), BorderLayout.NORTH);
		Card surface = new Card();
		surface.setLayout(new BorderLayout());
		surface.add(body);
		detail.add(surface, BorderLayout.CENTER);
		detail.revalidate();
		detail.repaint();
	}

	/** What is selected: its kind's badge, its name, then its kind, size and when it last changed. */
	private static JComponent header(Entry entry) {
		FileKind kind = FileKind.of(entry.name(), entry.directory());
		JPanel h = panel(new MigLayout("insets 2 4 12 4, fillx, gapy 2", "[]12[grow,fill]", ""));
		h.add(new Badge(kind.icon, (kind == FileKind.FOLDER) ? Tone.ACCENT : Tone.MUTED, 40), "spany 2, aligny center");
		JLabel name = Labels.lead(entry.name());
		name.setToolTipText(entry.name());
		h.add(name, "wmin 0, wrap");
		StringBuilder meta = new StringBuilder(kind(entry));
		if (!entry.directory()) meta.append("  ·  ").append(displaySize(entry.size()));
		if (entry.modified() > 0) meta.append("  ·  ").append(DATE.format(Instant.ofEpochMilli(entry.modified())));
		h.add(Labels.small(meta.toString()), "wmin 0");
		return h;
	}

	/** What several selected items come to: how many folders, how many files and their size. */
	private static JComponent several(List<Entry> selected) {
		List<String> parts = new java.util.ArrayList<>();
		long folders = selected.stream().filter(Entry::directory).count();
		if (folders > 0) parts.add(folders + (folders == 1 ? " folder" : " folders"));
		List<Entry> plain = selected.stream().filter(e -> !e.directory()).toList();
		if (!plain.isEmpty()) {
			parts.add(plain.size() + (plain.size() == 1 ? " file, " : " files, ")
				+ displaySize(plain.stream().mapToLong(Entry::size).sum()));
		}
		return new EmptyState("files", Tone.MUTED, selected.size() + " items selected", String.join("  ·  ", parts)).bare();
	}

	/** A quiet line in the middle of the preview: why there is nothing to show. */
	private static JComponent quiet(String text) {
		return Panels.centred(Labels.small(text));
	}

	/**
	 * The path as the folder's title: each folder above a link to it, the one
	 * open in bold. A deep path shows its root, an ellipsis to the folder above
	 * the last two, and the last two.
	 */
	private void crumbs() {
		breadcrumbs.removeAll();
		Location root = new Location(current.root(), "");
		String[] parts = current.path().isEmpty() ? new String[0] : current.path().split("/");
		crumb(root.name(), root, parts.length == 0);
		Location path = root;
		for (int i = 0; i < parts.length; i++) {
			path = path.child(parts[i]);
			Location target = path;
			if (i < parts.length - 2) {
				if (i == parts.length - 3) {
					breadcrumbs.add(Labels.icon(Lucide.icon("chevron-right", 12, Theme::muted)));
					breadcrumbs.add(Buttons.link("…", () -> navigate(target, true)));
				}
				continue;
			}
			breadcrumbs.add(Labels.icon(Lucide.icon("chevron-right", 12, Theme::muted)));
			crumb(parts[i], target, i == parts.length - 1);
		}
		breadcrumbs.setToolTipText(current.root().name() + "/" + current.path());
		breadcrumbs.revalidate();
		breadcrumbs.repaint();
	}

	private void crumb(String name, Location target, boolean open) {
		breadcrumbs.add(open ? Labels.heading(name) : Buttons.link(name, () -> navigate(target, true)));
	}

	private void goBack() { if (!refreshing && !history.isEmpty()) navigate(history.pop(), false); }
	private void buttons() {
		tree.setEnabled(reads != null && !refreshing);
		refresh.setEnabled(reads != null && !refreshing);
		back.setEnabled(!refreshing && !history.isEmpty());
		up.setEnabled(!refreshing && current != null && !current.path().isEmpty());
		filter.setEnabled(!refreshing && current != null);
	}
	/** The folder area shows {@code state} in place of the list. */
	private void show(JComponent state) {
		empty.removeAll();
		empty.add(state);
		empty.revalidate();
		empty.repaint();
		((CardLayout) contents.getLayout()).show(contents, "empty");
	}
	private void loading(String text) { show(new Loading(text)); }
	/** Why a read failed, and what to do about it. */
	private static EmptyState unreadable(String title, Throwable failure, String then) {
		return new EmptyState("circle-alert", Tone.ERROR, title, Labels.escape(reason(failure)) + "<br>" + then).bare();
	}
	/** The bottom line, hidden while there is nothing to say. */
	private void status(String text) {
		status.setText(text);
		status.setVisible(text != null && !text.isBlank());
	}
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
		previewToggle.setShown(show);
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
	/** A key for {@code c} alone, while it has the focus — Delete in the filter field deletes a letter. */
	private static void key(JComponent c, String key, String name, Runnable task) {
		c.getInputMap().put(KeyStroke.getKeyStroke(key), name);
		c.getActionMap().put(name, action(task));
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
	private static String reason(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}
	private static String kind(Entry entry) {
		return (entry.directory() || "file".equals(entry.type())) ? FileKind.of(entry.name(), entry.directory()).word : entry.type();
	}
	private static String displaySize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
	}

	/**
	 * A folder in the sidebar. It has no arrow until it has been read and found
	 * to hold folders: knowing sooner would mean reading every folder shown.
	 */
	private static final class Directory extends DefaultMutableTreeNode {
		final Location location;
		boolean loaded;
		CompletableFuture<Void> pending;
		/** Bumped when its contents change, so a read begun before is not shown. */
		int generation;
		Directory(Location location) {
			super(location.name());
			this.location = location;
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
			setIconTextGap(UIScale.scale(6));
			return this;
		}
	}

	private static final class FileRenderer extends Card implements ListCellRenderer<Entry> {
		// The row's looks, one instance each, so a cell that looks as the last did changes nothing.
		private static final Supplier<Color> TINTED = Theme::tinted, ACCENT = Theme::accent, NONE = () -> null;
		private final JLabel name = Labels.heading("");
		private final JLabel meta = Labels.small("");
		private final JLabel icon = Labels.icon(null);
		private final JLabel trailing = Labels.icon(null);
		/** Each kind's icon: folders in the accent, files muted. */
		private final Map<FileKind, Icon> icons = new EnumMap<>(FileKind.class);
		private final Icon chevron = Lucide.icon("chevron-right", 16, Theme::muted);
		FileRenderer() {
			setLayout(new BorderLayout(10, 0));
			setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
			// The name and what it is, one line straight under the other.
			JPanel labels = panel(new BorderLayout());
			labels.add(name, BorderLayout.NORTH);
			labels.add(meta, BorderLayout.SOUTH);
			add(icon, BorderLayout.WEST);
			add(labels, BorderLayout.CENTER);
			add(trailing, BorderLayout.EAST);
			for (FileKind k : FileKind.values()) {
				icons.put(k, Lucide.icon(k.icon, 20, (k == FileKind.FOLDER) ? Theme::accentText : Theme::muted));
			}
		}
		@Override public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry,
				int index, boolean selected, boolean focus) {
			name.setText(entry.name());
			meta.setText((entry.directory() ? "Folder" : displaySize(entry.size()))
				+ (entry.modified() > 0 ? "  ·  " + DATE.format(Instant.ofEpochMilli(entry.modified())) : ""));
			icon.setIcon(icons.get(FileKind.of(entry.name(), entry.directory())));
			trailing.setIcon(entry.directory() ? chevron : null);
			// The selected row sits on the accent tint the tree's selection and the navigation use;
			// a folder a drag would drop into is tinted and outlined.
			JList.DropLocation drop = list.getDropLocation();
			boolean into = entry.directory() && drop != null && drop.getIndex() == index;
			fill(selected || into ? TINTED : NONE);
			outline(focus || into ? ACCENT : NONE);
			return this;
		}
	}
}
