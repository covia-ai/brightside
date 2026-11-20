package brightside.ui.record;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.formdev.flatlaf.util.UIScale;

import brightside.JobList;
import brightside.ui.binding.Watcher;
import brightside.ui.components.Buttons;
import brightside.ui.components.Density;
import brightside.ui.components.Labels;
import brightside.ui.components.ScreenHeader;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import covia.venue.Jobs;

/**
 * The Record: every job run for you on this computer, newest first, each row
 * ordered, timestamped, and attributed (who started it, which agent ran it,
 * what it ran, how it ended). Read-only by design: rows can be opened in
 * Jobs, copied, and exported, never edited, deleted, or undone. Signing is
 * shown as coming, since the venue does not sign these entries yet.
 *
 * <p>Dumb: the window hands it the jobs to watch and opens a job in Jobs.
 */
@SuppressWarnings("serial")
public final class RecordScreen extends JPanel {

	/** The most rows shown; Export writes the same rows. */
	static final int MAX_ROWS = 500;
	private static final String[] COLUMNS = { "Time", "Started by", "Agent", "What", "Status" };

	public interface Host {
		void openInJobs(String jobId);

		void copy(String text);
	}

	/** One row of the record. */
	public record Row(String id, long time, String startedBy, String agent, String what, String status, String json) {
	}

	private final Host host;
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JLabel state = Labels.small(" ");
	private Watcher.Watch<?> watch;
	private Jobs jobs;
	private String owner;
	private int generation;

	public RecordScreen(Host host) {
		super(new BorderLayout());
		this.host = host;

		JButton verify = Buttons.secondary("Verify signatures");
		verify.setEnabled(false);
		verify.setToolTipText("Coming: entries are ordered, timestamped, and attributed today; signing them is not in this version yet");
		JButton export = Buttons.secondary("Export…");
		export.setToolTipText("Save these entries as a JSON file");
		export.addActionListener(e -> export());
		ScreenHeader header = new ScreenHeader("Record");
		header.action(verify);
		header.action(export);

		JLabel about = Labels.small("Every job run for you on this computer, newest first: when, who started it, "
			+ "which agent ran it, what it ran, and how it ended. Entries can't be edited or removed.");
		Styles.classes(about, Styles.SMALL, Styles.MUTED);
		about.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setShowVerticalLines(false);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new Cell());
		table.getAccessibleContext().setAccessibleName("Record");
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && selected() != null) host.openInJobs(selected().id());
			}

			@Override
			public void mousePressed(MouseEvent e) {
				popup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				popup(e);
			}

			private void popup(MouseEvent e) {
				if (!e.isPopupTrigger()) return;
				int r = table.rowAtPoint(e.getPoint());
				if (r >= 0) table.setRowSelectionInterval(r, r);
				Row row = selected();
				if (row != null) menuFor(row).show(table, e.getX(), e.getY());
			}
		});

		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.add(about, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
		body.add(scroll, BorderLayout.CENTER);
		state.setBorder(BorderFactory.createEmptyBorder(8, 20, 12, 20));
		Styles.classes(state, Styles.SMALL, Styles.MUTED);
		body.add(state, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		setRows(List.of());
	}

	/** {@code owner}'s jobs, watched while the Record is on screen; null to show none. */
	public void setJobs(Jobs jobs, String owner) {
		clear();
		if (jobs == null) return;
		this.jobs = jobs;
		this.owner = owner;
		watch = Watcher.watch(this, jobs, value -> rebuild((Index<Blob, ACell>) value));
	}

	public void clear() {
		if (watch != null) watch.close();
		watch = null;
		jobs = null;
		owner = null;
		generation++;
		setRows(List.of());
	}

	private void rebuild(Index<Blob, ACell> index) {
		if (index == null) return;
		int gen = ++generation;
		String who = owner;
		long now = System.currentTimeMillis();
		long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		new SwingWorker<List<Row>, Void>() {
			@Override
			protected List<Row> doInBackground() {
				JobList.View v = JobList.view(index, who, JobList.Filter.ALL, now, midnight);
				List<Row> out = new ArrayList<>();
				for (int i = 0; i < v.size() && out.size() < MAX_ROWS; i++) {
					JobList.Job j = v.job(i);
					if (j == null) continue;
					out.add(new Row(j.id(), j.created(), startedBy(j), j.agent() != null ? j.agent() : "",
						j.operation() != null ? j.operation() : j.title(), j.status() != null ? j.status() : "",
						j.record() != null ? j.record().toString() : "{}"));
				}
				return out;
			}

			@Override
			protected void done() {
				if (gen != generation) return;
				try {
					setRows(get());
				} catch (Exception e) {
					state.setText("Couldn't read the record just now. Nothing was changed; it reads again on the next update.");
				}
			}
		}.execute();
	}

	private static String startedBy(JobList.Job j) {
		if (j.starter() == null) return j.caller() != null ? j.caller() : "";
		return j.starter().label;
	}

	/** Replace the rows shown: for the watcher, and for tests. */
	public void setRows(List<Row> rows) {
		model.rows = List.copyOf(rows);
		model.fireTableDataChanged();
		table.setRowHeight(UIScale.scale(Density.compact() ? 24 : 30));
		state.setText(rows.isEmpty() ? "Nothing recorded yet. Every job your agents run appears here."
			: rows.size() + (rows.size() == 1 ? " entry" : " entries")
				+ (rows.size() >= MAX_ROWS ? " (the newest " + MAX_ROWS + ")" : "") + " · recorded on this computer");
	}

	public List<Row> rows() {
		return model.rows;
	}

	private Row selected() {
		int r = table.getSelectedRow();
		return (r < 0) ? null : model.rows.get(table.convertRowIndexToModel(r));
	}

	/** A row's actions: open, inspect, copy. Nothing that changes the record. */
	JPopupMenu menuFor(Row row) {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem open = new JMenuItem("Open in Jobs");
		open.addActionListener(e -> host.openInJobs(row.id()));
		menu.add(open);
		JMenuItem id = new JMenuItem("Copy id");
		id.addActionListener(e -> host.copy(row.id()));
		menu.add(id);
		JMenuItem json = new JMenuItem("Copy as JSON");
		json.addActionListener(e -> host.copy(row.json()));
		menu.add(json);
		return menu;
	}

	/** Saves the rows shown as a JSON array, to a file the person chooses. */
	private void export() {
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File("tau-record.json"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
		write(chooser.getSelectedFile().toPath(), model.rows, msg -> state.setText(msg));
	}

	static void write(java.nio.file.Path file, List<Row> rows, Consumer<String> note) {
		StringBuilder sb = new StringBuilder("[\n");
		for (int i = 0; i < rows.size(); i++) {
			sb.append("  ").append(rows.get(i).json());
			if (i < rows.size() - 1) sb.append(',');
			sb.append('\n');
		}
		sb.append("]\n");
		try {
			Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
			note.accept("Exported " + rows.size() + (rows.size() == 1 ? " entry" : " entries") + " to " + file.getFileName());
		} catch (java.io.IOException e) {
			note.accept("Couldn't write " + file.getFileName() + ": " + e.getMessage() + ". Nothing was exported.");
		}
	}

	private static final class Model extends AbstractTableModel {
		List<Row> rows = List.of();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int c) {
			return COLUMNS[c];
		}

		@Override
		public Object getValueAt(int r, int c) {
			Row row = rows.get(r);
			return switch (c) {
				case 0 -> new SimpleDateFormat("d MMM, HH:mm:ss").format(new Date(row.time()));
				case 1 -> row.startedBy();
				case 2 -> row.agent();
				case 3 -> row.what();
				default -> statusWord(row.status());
			};
		}
	}

	/** A job's status in plain words. */
	static String statusWord(String status) {
		return switch (status == null ? "" : status) {
			case "COMPLETE" -> "Completed";
			case "FAILED" -> "Failed";
			case "CANCELLED" -> "Cancelled";
			case "STARTED", "PENDING" -> "Running";
			case "PAUSED" -> "Paused";
			case "INPUT_REQUIRED", "AUTH_REQUIRED" -> "Waiting on you";
			default -> status;
		};
	}

	/** Times and what ran in mono; the rest plain on the card colour. */
	private static final class Cell extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r,
				int c) {
			super.getTableCellRendererComponent(t, value, selected, focus, r, c);
			setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
			if (c == 0 || c == 3) Styles.classes(this, Styles.MONOSPACED);
			else Styles.classes(this);
			if (!selected) setBackground(Theme.card());
			setForeground(Theme.foreground());
			return this;
		}
	}
}
