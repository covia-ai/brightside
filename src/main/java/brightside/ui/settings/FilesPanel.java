package brightside.ui.settings;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import brightside.FileRoots;
import brightside.PersonalDrives;
import brightside.ui.components.Buttons;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.SelectableText;
import net.miginfocom.swing.MigLayout;

/** Folder access and the acting user's personal drives, with all storage work off the event thread. */
@SuppressWarnings("serial")
public final class FilesPanel extends SettingsPage {

	public interface Host {
		FileRoots folders();
		PersonalDrives drives();
	}

	private final Host host;
	private final JPanel folders = new JPanel(new MigLayout("insets 0, fillx, wrap 4", "[]12[grow,fill]12[]8[]"));
	private final JPanel drives = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]"));
	private final JTextField folderName = new JTextField(20);
	private final JCheckBox readOnly = new JCheckBox("Read only", true);
	private final SelectableText folderPath = new SelectableText("Choose a folder").muted();
	private final JTextField driveName = new JTextField(20);
	private Path selectedFolder;
	private long version;

	public FilesPanel(Host host) {
		super("Add folder");
		this.host = host;
		folders.setOpaque(false);
		drives.setOpaque(false);
		readOnly.setOpaque(false);
		addSpan(Labels.section("Folders on this computer"));
		addSpan(SelectableText.description("Shared across this installation. Folder access changes take effect after restart."));
		addSpan(folders, "gapbottom 12");
		JPanel chooseRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]"));
		chooseRow.setOpaque(false);
		JButton choose = Buttons.plain("Choose folder…");
		choose.addActionListener(e -> chooseFolder());
		chooseRow.add(folderPath, "wmin 0");
		chooseRow.add(choose);
		addValueRow("Folder", "Only this folder and its contents will be available through the file tools.", chooseRow);
		addField("Name", "A short name to use when asking your assistant about this folder.", folderName);
		JPanel addRow = new JPanel(new MigLayout("insets 0", "[]12[]"));
		addRow.setOpaque(false);
		addRow.add(readOnly);
		addRow.add(primary);
		addSpanLeft(addRow);
		onPrimary(() -> {
			if (selectedFolder == null) { setNote("Choose a folder first.", true); return; }
			Path path = selectedFolder;
			String name = folderName.getText().trim();
			boolean ro = readOnly.isSelected();
			run((f, d) -> f.add(name, path, ro), "Folder saved. Restart Brightside to apply folder access changes.");
		});

		addSpan(Labels.section("Personal drives"), "gaptop 20");
		addSpan(SelectableText.description("Stored inside Brightside for this user. New drives are available immediately."));
		addSpan(drives, "gapbottom 12");
		JPanel createRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]"));
		createRow.setOpaque(false);
		JButton create = Buttons.plain("Create drive");
		create.addActionListener(e -> {
			String name = driveName.getText().trim();
			run((f, d) -> d.create(name), "Drive created.");
		});
		JButton refresh = Buttons.plain("Refresh");
		refresh.addActionListener(e -> refresh());
		createRow.add(driveName);
		createRow.add(create);
		createRow.add(refresh);
		addValueRow("Drive name", "Choose a name for a new personal drive.", createRow);
		clear();
	}

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Choose a folder for your assistant");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
		selectedFolder = chooser.getSelectedFile().toPath();
		folderPath.setText(selectedFolder.toString());
		if (folderName.getText().isBlank() && selectedFolder.getFileName() != null) {
			folderName.setText(selectedFolder.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
				.replaceAll("[^a-z0-9._-]+", "-"));
		}
	}

	public void refresh() {
		folders.removeAll();
		drives.removeAll();
		driveName.setText("");
		run((f, d) -> {}, "Folder access shown here applies after restart; personal drives are live.");
	}

	public void clear() {
		version++;
		folders.removeAll();
		drives.removeAll();
		folderName.setText("");
		driveName.setText("");
		selectedFolder = null;
		folderPath.setText("Choose a folder");
		clearNote();
		enable(this, false);
		revalidate();
		repaint();
	}

	private void show(List<FileRoots.Root> roots, List<String> names) {
		folders.removeAll();
		for (FileRoots.Root root : roots) {
			folders.add(new SelectableText(root.name()));
			folders.add(new SelectableText(root.location()).muted(), "wmin 0");
			JCheckBox ro = new JCheckBox("Read only", root.readOnly());
			ro.setOpaque(false);
			ro.addActionListener(e -> {
				boolean selected = ro.isSelected();
				run((f, d) -> f.setReadOnly(root.name(), selected), "Folder access saved. Restart Brightside to apply it.");
			});
			folders.add(ro);
			JButton remove = Buttons.small("Remove");
			remove.addActionListener(e -> {
				if (!Dialogs.confirmDanger(this, "Remove folder access",
					"Remove access to \"" + root.name() + "\" after restart? Its files will stay on this computer.")) return;
				run((f, d) -> f.remove(root.name()), "Folder access removed from settings. Restart Brightside to apply it.");
			});
			folders.add(remove);
		}
		if (roots.isEmpty()) folders.add(Labels.small("No folders configured."), "span 4");
		drives.removeAll();
		for (String name : names) {
			drives.add(new SelectableText(name));
		}
		if (names.isEmpty()) drives.add(Labels.small("No personal drives."));
		revalidate();
		repaint();
	}

	@FunctionalInterface
	private interface Action { void run(FileRoots folders, PersonalDrives drives) throws Exception; }
	private record Result(List<FileRoots.Root> roots, List<String> drives, String error) {}

	private void run(Action action, String success) {
		long request = ++version;
		FileRoots folderStore = host.folders();
		PersonalDrives driveStore = host.drives();
		enable(this, false);
		setNote("Loading…", false);
		new SwingWorker<Result, Void>() {
			@Override protected Result doInBackground() throws Exception {
				if (driveStore == null) throw new IllegalStateException("Unlock Brightside to manage files.");
				String error = null;
				try { action.run(folderStore, driveStore); }
				catch (Exception e) { error = message(e); }
				return new Result(folderStore.list(), driveStore.list(), error);
			}
			@Override protected void done() {
				if (version != request) return;
				enable(FilesPanel.this, true);
				try {
					Result result = get();
					show(result.roots(), result.drives());
					setNote(result.error() == null ? success : result.error(), result.error() != null);
				} catch (Exception e) { setNote(message(e), true); }
			}
		}.execute();
	}

	private static String message(Throwable e) {
		while (e.getCause() != null) e = e.getCause();
		return e.getMessage() == null ? e.toString() : e.getMessage();
	}

	private static void enable(Component component, boolean enabled) {
		component.setEnabled(enabled);
		if (component instanceof Container container) {
			for (Component child : container.getComponents()) enable(child, enabled);
		}
	}
}
