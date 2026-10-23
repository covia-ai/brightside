package brightside.ui.files;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import brightside.files.FileSource.Root;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Fields;
import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * Adding and managing the places files live, from the Files sidebar. A
 * personal drive appears at once. A folder on this computer — added, made
 * read-only or removed — takes effect when Brightside next starts, and the
 * sidebar says so until then.
 */
final class PlaceActions {

	private final FileBrowser browser;
	private final Places places;
	/** The folders saved for the next start, as last read; empty until read. */
	private Map<String, Boolean> saved = Map.of();

	PlaceActions(FileBrowser browser, Places places) {
		this.browser = browser;
		this.places = places;
	}

	/** The sidebar's + menu. */
	JPopupMenu addMenu() {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem folder = new JMenuItem("Folder on this computer…");
		folder.addActionListener(e -> addFolder());
		menu.add(folder);
		JMenuItem drive = new JMenuItem("Personal drive…");
		drive.addActionListener(e -> newDrive());
		menu.add(drive);
		return menu;
	}

	/** Adds to a location's menu what can be changed about the location itself: for a folder, its access. */
	void extend(JPopupMenu menu, Root root) {
		Boolean readOnly = saved.get(root.name());
		if (!root.id().startsWith("file:") || readOnly == null) return;
		String name = root.name();
		menu.addSeparator();
		JCheckBoxMenuItem ro = new JCheckBoxMenuItem("Read only", readOnly);
		ro.addActionListener(e -> {
			boolean selected = ro.isSelected();
			change(p -> p.setReadOnly(name, selected), "“" + name + "” will be " + (selected ? "read-only" : "writable") + " after a restart");
		});
		menu.add(ro);
		JMenuItem remove = new JMenuItem("Remove from Brightside…");
		remove.addActionListener(e -> {
			if (!Dialogs.confirmDanger(browser, "Remove folder", column(
				Labels.text("Remove “" + name + "” from Brightside?"),
				Labels.small("Its files stay on this computer. Your assistant loses access after a restart.")))) return;
			change(p -> p.removeFolder(name), "“" + name + "” goes after a restart");
		});
		menu.add(remove);
	}

	/** Reads the saved folders again, as the browser refreshes; a failure leaves the menus as they were. */
	void reload() {
		run(p -> { }, () -> { }, true);
	}

	void addFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Add a folder on this computer");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		if (chooser.showDialog(browser, "Choose") != JFileChooser.APPROVE_OPTION) return;
		Path folder = chooser.getSelectedFile().toPath();
		JTextField field = Labels.plain(new JTextField(places.suggestFolderName(folder), 24));
		JCheckBox readOnly = new JCheckBox("Read only: your assistant can look but not change", true);
		readOnly.setOpaque(false);
		JLabel problem = Labels.small(" ", Styles.ERROR);
		problem.setVisible(false);
		JPanel form = column(Labels.small(folder.toString()), Labels.text("Name your assistant will use"), field, problem, readOnly);
		while (Dialogs.form(browser, "Add a folder", form)) {
			String name = field.getText().strip();
			String why = places.folderNameProblem(name);
			if (why == null && saved.keySet().stream().anyMatch(name::equalsIgnoreCase)) why = "That name is already in use.";
			if (why == null) {
				boolean ro = readOnly.isSelected();
				change(p -> p.addFolder(name, folder, ro), "“" + name + "” appears after a restart");
				return;
			}
			flag(problem, why, field);
		}
	}

	void newDrive() {
		JTextField field = Labels.plain(new JTextField(24));
		JLabel problem = Labels.small(" ", Styles.ERROR);
		problem.setVisible(false);
		JPanel form = column(Labels.text("Name"), field, problem,
			Labels.small("Stored inside Brightside, for you and your agents."));
		List<String> drives = browser.roots().stream().filter(r -> r.id().startsWith("dlfs:")).map(Root::name).toList();
		while (Dialogs.form(browser, "New personal drive", form)) {
			String name = field.getText().strip();
			String why = places.driveNameProblem(name);
			if (why == null && drives.contains(name)) why = "That drive already exists.";
			if (why == null) {
				run(p -> p.createDrive(name), () -> browser.refreshTo("dlfs:" + name), false);
				return;
			}
			flag(problem, why, field);
		}
	}

	/** A folder change, saved for the next start: the sidebar says it waits for a restart. */
	private void change(Step step, String toast) {
		run(step, () -> {
			browser.restartPending();
			browser.sidebarToast(toast);
		}, false);
	}

	/**
	 * Runs {@code work} off the event thread, reads the saved folders again,
	 * then does {@code then} on it; a failure is reported unless {@code quiet}.
	 */
	private void run(Step work, Runnable then, boolean quiet) {
		Thread.ofVirtual().start(() -> {
			try {
				work.run(places);
				Map<String, Boolean> now = places.savedFolders();
				SwingUtilities.invokeLater(() -> {
					saved = now;
					then.run();
				});
			} catch (Exception e) {
				if (!quiet) SwingUtilities.invokeLater(() -> Dialogs.warn(browser, "Couldn't change storage", Labels.text(reason(e))));
			}
		});
	}

	private static void flag(JLabel problem, String why, JTextField field) {
		problem.setText(why);
		problem.setVisible(true);
		Fields.flag(field);
	}

	private static JPanel column(java.awt.Component... parts) {
		JPanel p = new JPanel(new MigLayout("insets 0, wrap 1, gapy 6, fillx", "[grow,fill]"));
		p.setOpaque(false);
		for (java.awt.Component c : parts) p.add(c);
		return p;
	}

	private static String reason(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return (error.getMessage() == null) ? error.getClass().getSimpleName() : error.getMessage();
	}

	private interface Step { void run(Places places) throws Exception; }
}
