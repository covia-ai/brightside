package brightside.ui.files;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import brightside.files.FileSource;
import brightside.files.FileSource.Entry;
import brightside.files.FileSource.Root;
import brightside.ui.components.Labels;
import brightside.ui.components.Buttons;
import brightside.ui.components.Scrolls;

/** Embeddable read-only browser. All storage access lives behind FileSource. */
@SuppressWarnings("serial")
public final class FileBrowser extends JPanel {

	private final JComboBox<Root> roots = new JComboBox<>();
	private final JTextField location = new JTextField();
	private final JButton back = Buttons.secondary("Back");
	private final JButton up = Buttons.secondary("Up");
	private final JButton refresh = Buttons.secondary("Refresh");
	private final JLabel status = Labels.small("Open a folder or personal drive.");
	private final JLabel previewStatus = Labels.small("Select a file to preview.");
	private final JTextArea preview = new JTextArea();
	private final Entries model = new Entries();
	private final JTable table = new JTable(model);
	private final ArrayDeque<String> history = new ArrayDeque<>();
	private FileSource source;
	private Root root;
	private String path = "";
	private boolean updating;
	private long generation;
	private long previewGeneration;
	private SwingWorker<?, ?> navigation;
	private SwingWorker<?, ?> reading;

	public FileBrowser(Runnable storageSettings) {
		super(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
		JPanel heading = new JPanel(new BorderLayout(12, 0));
		heading.add(Labels.heading("Files"), BorderLayout.WEST);
		heading.add(roots, BorderLayout.CENTER);
		JButton settings = Buttons.secondary("Storage settings");
		settings.addActionListener(e -> storageSettings.run());
		heading.add(settings, BorderLayout.EAST);
		roots.setRenderer(new DefaultListCellRenderer() {
			@Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
					Object value, int index, boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				putClientProperty("html.disable", true);
				return this;
			}
		});
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		controls.add(back);
		controls.add(up);
		controls.add(refresh);
		location.setEditable(false);
		JPanel toolbar = new JPanel(new BorderLayout(8, 0));
		toolbar.add(controls, BorderLayout.WEST);
		toolbar.add(location, BorderLayout.CENTER);
		JPanel top = new JPanel(new BorderLayout(0, 10));
		top.add(heading, BorderLayout.NORTH);
		top.add(toolbar, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setShowGrid(false);
		table.setFillsViewportHeight(true);
		table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
			@Override protected void setValue(Object value) {
				putClientProperty("html.disable", true);
				super.setValue(value);
			}
		});
		table.getColumnModel().getColumn(0).setPreferredWidth(240);
		table.getColumnModel().getColumn(3).setPreferredWidth(145);
		table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectFile(); });
		table.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) openDirectory();
			}
		});
		table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-directory");
		table.getActionMap().put("open-directory", new AbstractAction() {
			@Override public void actionPerformed(ActionEvent e) { openDirectory(); }
		});
		preview.setEditable(false);
		preview.setLineWrap(true);
		preview.setWrapStyleWord(true);
		preview.setMargin(new java.awt.Insets(10, 10, 10, 10));
		JPanel detail = new JPanel(new BorderLayout(0, 8));
		detail.add(previewStatus, BorderLayout.NORTH);
		detail.add(Scrolls.both(preview), BorderLayout.CENTER);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Scrolls.both(table), detail);
		split.setResizeWeight(0.65);
		split.setDividerLocation(0.65);
		split.getLeftComponent().setMinimumSize(new Dimension(250, 100));
		detail.setMinimumSize(new Dimension(150, 100));
		add(split, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);

		roots.addActionListener(e -> {
			if (updating) return;
			root = (Root) roots.getSelectedItem();
			history.clear();
			if (root != null) navigate("", false);
		});
		back.addActionListener(e -> { if (!history.isEmpty()) navigate(history.pop(), false); });
		up.addActionListener(e -> {
			int slash = path.lastIndexOf('/');
			navigate(slash < 0 ? "" : path.substring(0, slash), true);
		});
		refresh.addActionListener(e -> refresh());
		buttons();
	}

	/** Called on the event thread whenever the acting principal or venue changes. */
	public void bind(FileSource source) {
		clear();
		this.source = source;
	}

	public void clear() {
		generation++;
		if (navigation != null) navigation.cancel(true);
		clearPreview();
		source = null;
		root = null;
		path = "";
		history.clear();
		updating = true;
		roots.removeAllItems();
		updating = false;
		model.set(List.of());
		location.setText("");
		status.setText("Open a folder or personal drive.");
		buttons();
	}

	public void refresh() {
		if (source == null) return;
		FileSource captured = source;
		Root previous = root;
		String previousPath = path;
		long version = ++generation;
		if (navigation != null) navigation.cancel(true);
		clearPreview();
		model.set(List.of());
		status.setText("Loading storage…");
		roots.setEnabled(false);
		back.setEnabled(false);
		up.setEnabled(false);
		navigation = work(captured::roots, available -> {
			updating = true;
			roots.removeAllItems();
			for (Root r : available) roots.addItem(r);
			root = available.stream().filter(r -> r.equals(previous)).findFirst()
				.orElse(available.isEmpty() ? null : available.get(0));
			roots.setSelectedItem(root);
			updating = false;
			if (root == null) {
				path = "";
				history.clear();
				location.setText("");
				status.setText("No storage available. Add a folder or personal drive in Storage settings.");
				buttons();
			} else {
				if (!root.equals(previous)) history.clear();
				navigate(root.equals(previous) ? previousPath : "", false);
			}
		}, e -> { status.setText(error(e)); buttons(); }, () -> generation == version);
	}

	private void navigate(String next, boolean remember) {
		if (source == null || root == null) return;
		if (remember && !path.equals(next)) history.push(path);
		path = next;
		location.setText(root.name() + "/" + path);
		long version = ++generation;
		if (navigation != null) navigation.cancel(true);
		clearPreview();
		model.set(List.of());
		status.setText("Loading folder…");
		buttons();
		FileSource captured = source;
		Root selected = root;
		navigation = work(() -> captured.list(selected, next), listing -> {
			model.set(listing.entries());
			status.setText(listing.warning().isEmpty()
				? (listing.entries().isEmpty() ? "This folder is empty." : listing.entries().size() + " items · Double-click a folder to open it.")
				: listing.warning());
		}, e -> status.setText(error(e)), () -> generation == version);
	}

	private Entry selected() {
		int row = table.getSelectedRow();
		return row < 0 || row >= model.entries.size() ? null : model.entries.get(row);
	}

	private String child(Entry entry) { return path.isEmpty() ? entry.name() : path + "/" + entry.name(); }

	private void openDirectory() {
		Entry entry = selected();
		if (entry != null && entry.directory()) navigate(child(entry), true);
	}

	private void selectFile() {
		clearPreview();
		Entry entry = selected();
		if (entry == null) return;
		previewStatus.setText(entry.name());
		if (entry.directory()) { preview.setText("Open this folder to view its contents."); return; }
		if (!"file".equals(entry.type())) { preview.setText("Preview is unavailable for this type of file."); return; }
		FileSource captured = source;
		Root selected = root;
		String selectedPath = child(entry);
		long version = previewGeneration;
		preview.setText("Loading preview…");
		reading = work(() -> captured.preview(selected, selectedPath), result -> {
			preview.setText(result.binary() ? "Preview is available for UTF-8 text files only."
				: result.text().isEmpty() ? "This file is empty." : result.text());
			preview.setCaretPosition(0);
			previewStatus.setText(entry.name() + (result.truncated() ? " · First 64 KiB" : ""));
		}, e -> preview.setText(error(e)), () -> previewGeneration == version);
	}

	private void clearPreview() {
		previewGeneration++;
		if (reading != null) reading.cancel(true);
		preview.setText("");
		previewStatus.setText("Select a file to preview.");
	}

	private void buttons() {
		roots.setEnabled(source != null);
		refresh.setEnabled(source != null);
		back.setEnabled(root != null && !history.isEmpty());
		up.setEnabled(root != null && !path.isEmpty());
	}

	private static String error(Exception e) {
		Throwable cause = e;
		while (cause.getCause() != null) cause = cause.getCause();
		return "Could not read: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
	}

	private static <T> SwingWorker<T, Void> work(Callable<T> task, Consumer<T> success,
			Consumer<Exception> failure, java.util.function.BooleanSupplier current) {
		SwingWorker<T, Void> worker = new SwingWorker<>() {
			@Override protected T doInBackground() throws Exception { return task.call(); }
			@Override protected void done() {
				if (isCancelled() || !current.getAsBoolean()) return;
				try { success.accept(get()); } catch (Exception e) { failure.accept(e); }
			}
		};
		worker.execute();
		return worker;
	}

	private static final class Entries extends AbstractTableModel {
		private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());
		private List<Entry> entries = List.of();
		void set(List<Entry> entries) { this.entries = entries; fireTableDataChanged(); }
		@Override public int getRowCount() { return entries.size(); }
		@Override public int getColumnCount() { return 4; }
		@Override public String getColumnName(int column) { return new String[] { "Name", "Kind", "Bytes", "Modified" }[column]; }
		@Override public Object getValueAt(int row, int column) {
			Entry e = entries.get(row);
			return switch (column) {
				case 0 -> e.name();
				case 1 -> e.directory() ? "Folder" : e.type();
				case 2 -> e.directory() ? "" : String.format("%,d", e.size());
				case 3 -> e.modified() <= 0 ? "" : DATE.format(Instant.ofEpochMilli(e.modified()));
				default -> "";
			};
		}
	}
}
