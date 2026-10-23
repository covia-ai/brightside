package brightside.ui.files;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import brightside.files.DirectoryCache.Location;
import brightside.files.FileChanges;
import brightside.files.FileChanges.Failure;
import brightside.files.FileChanges.Item;
import brightside.files.FileChanges.Outcome;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Fields;
import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * What a person can do to files in the browser: the right-click menus, the
 * question a change asks first, and running it. A change runs through
 * {@link FileChanges}; the browser then reads again only what it touched.
 * Opening a file in its own app, or showing it in the system's file manager,
 * is offered only for files that are on this computer.
 */
final class FileActions {

	/** The system's own file manager, by name. */
	private static final String SYSTEM_FILES = systemFiles();
	private static final int LISTED_FAILURES = 8;
	/** Where the last file chooser was left, for the next. */
	private static File lastFolder;
	private final FileBrowser browser;

	FileActions(FileBrowser browser) {
		this.browser = browser;
	}

	/** The menu for selected items. */
	JPopupMenu itemsMenu(List<Item> items) {
		JPopupMenu menu = new JPopupMenu();
		Item one = (items.size() == 1) ? items.get(0) : null;
		boolean writable = !items.get(0).location().root().readOnly();
		if (one != null && (one.folder() || one.location().local() != null)) item(menu, "Open", "ENTER", () -> open(one));
		if (one != null && one.location().local() != null) item(menu, "Show in " + SYSTEM_FILES, null, () -> reveal(one.location()));
		if (one != null && !one.folder()) item(menu, "Save a copy…", null, () -> saveCopy(one.location()));
		if (menu.getComponentCount() > 0) menu.addSeparator();
		if (one != null) item(menu, "Rename…", "F2", () -> rename(one)).setEnabled(writable);
		item(menu, "Delete", "DELETE", () -> delete(items)).setEnabled(writable);
		return menu;
	}

	/** The menu for a folder: the open folder's background, or a folder in the sidebar. */
	JPopupMenu folderMenu(Location folder, boolean sidebar) {
		JPopupMenu menu = new JPopupMenu();
		boolean writable = !folder.root().readOnly();
		item(menu, "New folder…", sidebar ? null : "ctrl shift N", () -> newFolder(folder)).setEnabled(writable);
		item(menu, "Add files…", null, () -> addFiles(folder)).setEnabled(writable);
		if (folder.local() != null) item(menu, "Open in " + SYSTEM_FILES, null, () -> openOutside(folder));
		if (!sidebar) {
			menu.addSeparator();
			item(menu, "Refresh", "F5", browser::refresh);
		} else if (!folder.path().isEmpty()) {
			Item self = new Item(folder, true);
			menu.addSeparator();
			item(menu, "Rename…", null, () -> rename(self)).setEnabled(writable);
			item(menu, "Delete", null, () -> delete(List.of(self))).setEnabled(writable);
		}
		return menu;
	}

	/** A folder opens in the browser; a file on this computer opens in its own app. */
	void open(Item item) {
		if (item.folder()) browser.open(item.location());
		else if (item.location().local() != null) openOutside(item.location());
	}

	void newFolder(Location parent) {
		if (parent.root().readOnly()) return;
		List<String> names = browser.namesIn(parent);
		String name = askName("New folder", FileChanges.freeName("New folder", true, names), true, names, null);
		if (name != null) run("Creating a folder…", changes().createFolder(parent, name), parent, null, "Couldn't create the folder");
	}

	void rename(Item item) {
		Location at = item.location();
		if (at.root().readOnly()) return;
		String name = askName("Rename", at.name(), item.folder(), browser.namesIn(at.parent()), at.name());
		if (name != null && !name.equals(at.name())) {
			run("Renaming…", changes().rename(item, name), at.parent(), null, "Couldn't rename");
		}
	}

	void delete(List<Item> items) {
		if (items.isEmpty() || items.get(0).location().root().readOnly()) return;
		JPanel message = column();
		message.add(Labels.text("Delete " + count(items.stream().map(i -> i.location().name()).toList()) + "?"));
		message.add(Labels.small(items.stream().anyMatch(Item::folder)
			? "A folder goes with everything in it. This can't be undone." : "This can't be undone."));
		if (!Dialogs.confirmDanger(browser, "Delete", message)) return;
		run("Deleting…", changes().delete(items), null, null, "Couldn't delete");
	}

	/** Into {@code folder} within the items' own place. */
	void move(List<Item> items, Location folder) {
		run("Moving…", changes().move(items, folder), folder, o -> "Moved " + count(o.done()) + " to " + folder.name(), "Couldn't move");
	}

	/** Into {@code folder}, which may be in another place. */
	void copy(List<Item> items, Location folder) {
		run("Copying…", changes().copy(items, folder), folder, o -> "Copied " + count(o.done()) + " to " + folder.name(), "Couldn't copy");
	}

	/** Files and folders from this computer into {@code folder}. */
	void bringIn(List<Path> files, Location folder) {
		if (files.isEmpty() || folder.root().readOnly()) return;
		run("Adding…", changes().bringIn(files, folder), folder, o -> "Added " + count(o.done()) + " to " + folder.name(), "Couldn't add");
	}

	void addFiles(Location folder) {
		JFileChooser chooser = new JFileChooser(lastFolder);
		chooser.setDialogTitle("Add files to " + folder.name());
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		if (chooser.showDialog(browser, "Add") != JFileChooser.APPROVE_OPTION) return;
		lastFolder = chooser.getCurrentDirectory();
		bringIn(Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList(), folder);
	}

	void saveCopy(Location file) {
		JFileChooser chooser = new JFileChooser(lastFolder);
		chooser.setDialogTitle("Save a copy");
		chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), file.name()));
		if (chooser.showSaveDialog(browser) != JFileChooser.APPROVE_OPTION) return;
		lastFolder = chooser.getCurrentDirectory();
		Path target = chooser.getSelectedFile().toPath();
		if (Files.exists(target) && !Dialogs.confirmDanger(browser, "Replace",
			Labels.text("Replace “" + target.getFileName() + "” there with this copy?"))) return;
		run("Saving a copy…", changes().saveCopy(file, target), null, o -> "Saved a copy", "Couldn't save a copy of");
	}

	/** Opens {@code at} — a file in its own app, a folder in the system's file manager. */
	void openOutside(Location at) {
		Path path = at.local();
		if (path == null) return;
		outside(at.name(), () -> Desktop.getDesktop().open(path.toFile()));
	}

	/** Shows {@code at} picked out in the system's file manager. */
	private void reveal(Location at) {
		Path path = at.local();
		if (path == null) return;
		outside(at.name(), () -> {
			Desktop desktop = Desktop.getDesktop();
			if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(path.toFile());
			// Java can't pick a file out in Explorer; Explorer can, asked directly.
			else if (SYSTEM_FILES.equals("Explorer")) new ProcessBuilder("explorer.exe", "/select," + path).start();
			else desktop.open(path.getParent().toFile());
		});
	}

	/** Desktop integration, off the event thread; a failure is reported on it. */
	private void outside(String name, Step step) {
		Thread.ofVirtual().start(() -> {
			try {
				step.run();
			} catch (Exception e) {
				SwingUtilities.invokeLater(() -> report("Couldn't open", List.of(new Failure(name, reason(e)))));
			}
		});
	}

	/**
	 * Runs a change: the status line says what is happening; afterwards the
	 * browser reads again what it touched, a result out of sight gets a
	 * word of confirmation, and anything that failed is listed.
	 */
	private void run(String doing, CompletableFuture<Outcome> work, Location into, Function<Outcome, String> toast, String failed) {
		long binding = browser.binding();
		browser.working(doing);
		work.whenComplete((outcome, error) -> SwingUtilities.invokeLater(() -> {
			if (binding != browser.binding()) return;
			Outcome o = (error == null) ? outcome
				: new Outcome(List.of(), List.of(new Failure("", reason(error))), Set.of(), Set.of());
			browser.apply(o, into);
			if (toast != null && !o.done().isEmpty() && (into == null || !into.equals(browser.current()))) browser.toast(toast.apply(o));
			if (!o.failures().isEmpty()) report(failed, o.failures());
		}));
	}

	private void report(String failed, List<Failure> failures) {
		List<String> names = failures.stream().map(Failure::name).filter(n -> !n.isEmpty()).toList();
		JPanel message = column();
		if (failures.size() == 1) {
			message.add(Labels.text(failures.get(0).reason()));
		} else {
			for (Failure f : failures.subList(0, Math.min(failures.size(), LISTED_FAILURES))) {
				message.add(Labels.text(f.name() + " — " + f.reason()));
			}
			if (failures.size() > LISTED_FAILURES) message.add(Labels.small("and " + (failures.size() - LISTED_FAILURES) + " more"));
		}
		Dialogs.warn(browser, names.isEmpty() ? failed : failed + " " + count(names), message);
	}

	/**
	 * Asks for a name, beginning with {@code initial} and its stem selected,
	 * until the name is one a file can have and no other item in the open
	 * folder holds; null when cancelled.
	 */
	private String askName(String title, String initial, boolean folder, List<String> others, String self) {
		JTextField field = Labels.plain(new JTextField(initial, 26));
		JLabel problem = Labels.small(" ", Styles.ERROR);
		problem.setVisible(false);
		JPanel form = column();
		form.add(field, "growx");
		form.add(problem);
		field.addAncestorListener(new AncestorListener() {
			@Override public void ancestorAdded(AncestorEvent e) {
				SwingUtilities.invokeLater(() -> {
					String text = field.getText();
					int dot = folder ? -1 : text.lastIndexOf('.');
					field.requestFocusInWindow();
					field.select(0, (dot > 0) ? dot : text.length());
				});
			}
			@Override public void ancestorRemoved(AncestorEvent e) { }
			@Override public void ancestorMoved(AncestorEvent e) { }
		});
		while (Dialogs.form(browser, title, form)) {
			String name = field.getText().strip();
			String why = FileChanges.nameProblem(name);
			if (why == null && !name.equalsIgnoreCase(self == null ? "" : self)
				&& others.stream().anyMatch(name::equalsIgnoreCase)) why = "There is already an item with this name here.";
			if (why == null) return name;
			problem.setText(why);
			problem.setVisible(true);
			Fields.flag(field);
		}
		return null;
	}

	private FileChanges changes() {
		return browser.changes();
	}

	private static JMenuItem item(JPopupMenu menu, String text, String key, Runnable action) {
		JMenuItem item = new JMenuItem(text);
		if (key != null) item.setAccelerator(KeyStroke.getKeyStroke(key));
		item.addActionListener(e -> action.run());
		menu.add(item);
		return item;
	}

	private static JPanel column() {
		JPanel p = new JPanel(new MigLayout("insets 0, wrap 1, gapy 6, fillx", "[grow,fill]"));
		p.setOpaque(false);
		return p;
	}

	/** “name” for one, "3 items" for more. */
	private static String count(List<String> names) {
		return (names.size() == 1) ? "“" + names.get(0) + "”" : names.size() + " items";
	}

	private static String reason(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return (error.getMessage() == null) ? error.getClass().getSimpleName() : error.getMessage();
	}

	private static String systemFiles() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.startsWith("windows") ? "Explorer" : os.startsWith("mac") ? "Finder" : "file manager";
	}

	private interface Step { void run() throws Exception; }
}
