package brightside.ui.jobs;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import brightside.JobList;
import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.EmptyState;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.Scrolls;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Times;
import brightside.ui.components.Tone;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import net.miginfocom.swing.MigLayout;

/**
 * The acting principal's jobs: everything that ran or is running on their
 * behalf, newest first, filtered by how it stands, who started it and when.
 *
 * <p>The list is a {@link JList} over a {@link JobListModel}, so only the rows
 * on screen are ever projected from the lattice, however long the history. A
 * row's job is selected by click or keys; the bar below the list, the
 * right-click menu and the detail act on it — pause, resume, cancel, send it a
 * message, open the agent conversation it worked in. Double-click or Enter
 * opens the detail, with a breadcrumb back to the list and through the jobs a
 * job ran inside.
 *
 * <p>The app feeds it snapshots of the principal's job index
 * ({@link #setJobs}); filtering runs off the event thread and the selection
 * and open detail follow their job by id across snapshots.
 */
@SuppressWarnings("serial")
public final class JobsScreen extends JPanel {

	/** What can be done to a job. */
	public enum Control {
		PAUSE, RESUME, CANCEL, MESSAGE
	}

	/** How an action went: a note, and whether it is an error. Called on the event thread. */
	public interface Result {
		void done(String note, boolean error);
	}

	/** What the screen asks of the app. Each answers on the event thread. */
	public interface Host {
		/** Does {@code control} to {@code job}, with {@code message} for {@link Control#MESSAGE}. */
		void control(JobList.Job job, Control control, String message, Result done);

		/** Opens agent {@code agentId}'s conversation {@code sessionId} in the chat. */
		void openConversation(String agentId, String sessionId);

		/** The screen has come on screen: read the jobs now rather than at the next poll. */
		void shown();
	}

	private static final String LIST = "list", DETAIL = "detail";
	private static final Object ANYONE = "Anyone";

	private final Host host;
	private final CardLayout cards = new CardLayout();
	private final JPanel deck = new JPanel(cards);
	private final JPanel crumbs = new JPanel(new MigLayout("insets 18 28 6 28, gap 6", "", ""));
	private final JLabel note = Labels.small(" ");

	private final Map<JobList.Group, JToggleButton> groups = new EnumMap<>(JobList.Group.class);
	private final JToggleButton allGroups = new JToggleButton("All");
	private final JComboBox<Object> starter = new JComboBox<>();
	private final JComboBox<JobList.Range> range = new JComboBox<>(JobList.Range.values());
	private final JLabel count = Labels.small(" ");

	private final JobListModel model = new JobListModel();
	private final JList<JobList.Job> list = new JList<>(model);
	private final JobRenderer renderer = new JobRenderer();
	private final JPanel listHolder = new JPanel(new BorderLayout());
	private final JScrollPane scroll = new JScrollPane(list);
	private final EmptyState empty = new EmptyState("list-checks", Tone.MUTED, "No jobs",
		"Nothing has run on your behalf in this range yet.");

	private final JButton openButton = action("Details", "panel-right-open", this::openSelected);
	private final JButton pauseButton = action("Pause", "circle-pause", () -> controlSelected(Control.PAUSE));
	private final JButton resumeButton = action("Resume", "play", () -> controlSelected(Control.RESUME));
	private final JButton messageButton = action("Message…", "message-square-text", () -> controlSelected(Control.MESSAGE));
	private final JButton cancelButton = action("Cancel…", "circle-x", () -> controlSelected(Control.CANCEL));

	private final JPanel detailHolder = new JPanel(new BorderLayout());
	private JobDetail detail;
	/** The jobs opened from the list down to the one in the detail, by id. */
	private final List<String> trail = new ArrayList<>();

	private Index<Blob, ACell> index;
	private String owner;
	private long generation;

	public JobsScreen(Host host) {
		super(new BorderLayout());
		this.host = host;
		setOpaque(false);

		// The breadcrumb, with the note from the last action beside it.
		crumbs.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(crumbs, BorderLayout.CENTER);
		note.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 0, 6, 28));
		top.add(note, BorderLayout.EAST);
		add(top, BorderLayout.NORTH);

		deck.setOpaque(false);
		deck.add(listPage(), LIST);
		detailHolder.setOpaque(false);
		deck.add(detailHolder, DETAIL);
		add(deck, BorderLayout.CENTER);
		showList();

		// Times on screen ("for 3 minutes") stay current while it is showing.
		Timer tick = new Timer(1000, e -> {
			long now = System.currentTimeMillis();
			renderer.now(now);
			list.repaint();
			if (detail != null) detail.tick(now);
		});
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) return;
			if (isShowing()) {
				tick.start();
				host.shown();
			} else {
				tick.stop();
			}
		});
	}

	// ------------------------------------------------------------------
	// What the app tells it
	// ------------------------------------------------------------------

	/** A new snapshot of {@code owner}'s job index; null when there is nothing to show. */
	public void setJobs(Index<Blob, ACell> jobs, String owner) {
		boolean switched = this.owner != null && !this.owner.equals(owner);
		this.index = jobs;
		this.owner = owner;
		if (switched) {
			trail.clear();
			showList();
		}
		refilter();
		refreshDetail();
	}

	/** The jobs could not be read; what is shown stays, and says so. */
	public void showReadFailure() {
		setNote("Couldn't read your jobs. Retrying…", true);
	}

	/** Drops everything shown, as when the principal changes or the app locks. */
	public void clear() {
		index = null;
		owner = null;
		generation++;
		model.setView(null);
		trail.clear();
		showList();
		setNote(" ", false);
	}

	/** Opens job {@code id}'s detail, as from a link elsewhere; the breadcrumb leads back to the list. */
	public void open(String id) {
		trail.clear();
		trail.add(id);
		showDetail();
	}

	// ------------------------------------------------------------------
	// The list page
	// ------------------------------------------------------------------

	private JComponent listPage() {
		JPanel page = new JPanel(new BorderLayout());
		page.setOpaque(false);

		JPanel filters = new JPanel(new MigLayout("insets 6 28 10 28, gap 6, fillx", "[][][][][][][]push[][]8[]", ""));
		filters.setOpaque(false);
		allGroups.setSelected(true);
		allGroups.addActionListener(e -> {
			for (JToggleButton b : groups.values()) b.setSelected(false);
			allGroups.setSelected(true);
			refilter();
		});
		filters.add(allGroups);
		for (JobList.Group g : JobList.Group.values()) {
			JToggleButton b = new JToggleButton(g.label);
			b.addActionListener(e -> {
				allGroups.setSelected(groups.values().stream().noneMatch(JToggleButton::isSelected));
				refilter();
			});
			groups.put(g, b);
			filters.add(b);
		}
		starter.addItem(ANYONE);
		for (JobList.Starter s : JobList.Starter.values()) starter.addItem(s);
		starter.setRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
				return super.getListCellRendererComponent(l, v instanceof JobList.Starter st ? st.label : v, i, s, f);
			}
		});
		starter.setToolTipText("Who started them. A scheduled run counts as whoever scheduled it.");
		starter.addActionListener(e -> refilter());
		range.setRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
				return super.getListCellRendererComponent(l, v instanceof JobList.Range r ? r.label : v, i, s, f);
			}
		});
		range.setSelectedItem(JobList.Range.WEEK);
		range.addActionListener(e -> refilter());
		filters.add(Labels.small("Started by"));
		filters.add(starter);
		filters.add(range);
		page.add(filters, BorderLayout.NORTH);

		configure(list, renderer);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setBackground(Theme.panel());
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) syncButtons();
		});
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				popup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				popup(e);
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && !e.isPopupTrigger() && rowAt(e) >= 0) openSelected();
			}

			private void popup(MouseEvent e) {
				if (!e.isPopupTrigger()) return;
				int row = rowAt(e);
				if (row < 0) return;
				list.setSelectedIndex(row);
				menu(model.getElementAt(row)).show(list, e.getX(), e.getY());
			}
		});
		list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
		list.getActionMap().put("open", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				openSelected();
			}
		});
		scroll.setBorder(brightside.ui.components.Borders.hairlineTop());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		listHolder.setOpaque(false);
		listHolder.add(scroll, BorderLayout.CENTER);
		page.add(listHolder, BorderLayout.CENTER);

		JPanel bar = new JPanel(new MigLayout("insets 8 28 12 28, gap 6, fillx", "[]push[][][][][]", ""));
		bar.setOpaque(false);
		bar.add(count);
		bar.add(openButton);
		bar.add(pauseButton);
		bar.add(resumeButton);
		bar.add(messageButton);
		bar.add(cancelButton);
		page.add(bar, BorderLayout.SOUTH);
		syncButtons();
		return page;
	}

	/**
	 * Sets {@code list} to paint with {@code renderer} at a fixed cell size, so
	 * it asks its model only for the rows on screen. With either size left to
	 * measure, Swing's list renders every row once to find the tallest or
	 * widest. A vertical list draws each row across its whole width whatever
	 * the fixed width says, and tracks its viewport's width.
	 */
	static void configure(JList<JobList.Job> list, JobRenderer renderer) {
		list.setCellRenderer(renderer);
		list.setFixedCellHeight(renderer.getPreferredSize().height);
		list.setFixedCellWidth(1);
	}

	private int rowAt(MouseEvent e) {
		int row = list.locationToIndex(e.getPoint());
		return (row >= 0 && list.getCellBounds(row, row).contains(e.getPoint())) ? row : -1;
	}

	/** The filter the controls describe. */
	JobList.Filter filter() {
		Set<JobList.Group> chosen = EnumSet.noneOf(JobList.Group.class);
		for (Map.Entry<JobList.Group, JToggleButton> e : groups.entrySet()) {
			if (e.getValue().isSelected()) chosen.add(e.getKey());
		}
		Object who = starter.getSelectedItem();
		return new JobList.Filter(chosen, (who instanceof JobList.Starter s) ? s : null,
			(JobList.Range) range.getSelectedItem());
	}

	/** Recomputes the rows off the event thread, keeping the selected job selected. */
	private void refilter() {
		long run = ++generation;
		Index<Blob, ACell> jobs = index;
		String who = owner;
		if (jobs == null || who == null) {
			model.setView(null);
			showEmpty(true);
			return;
		}
		JobList.Filter f = filter();
		String selected = selectedId();
		long now = System.currentTimeMillis();
		long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		new SwingWorker<JobList.View, Void>() {
			@Override
			protected JobList.View doInBackground() {
				return JobList.view(jobs, who, f, now, midnight);
			}

			@Override
			protected void done() {
				if (run != generation) return;
				JobList.View view;
				try {
					view = get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (ExecutionException e) {
					setNote("Couldn't filter your jobs: " + e.getCause().getMessage(), true);
					return;
				}
				renderer.now(System.currentTimeMillis());
				model.setView(view);
				int row = (selected != null) ? view.rowOf(selected) : -1;
				if (row >= 0) list.setSelectedIndex(row);
				else list.clearSelection();
				count.setText(view.size() == view.total() ? Times.plural(view.total(), "job")
					: String.format("%,d of %s", view.size(), Times.plural(view.total(), "job")));
				showEmpty(view.size() == 0);
				syncButtons();
			}
		}.execute();
	}

	private void showEmpty(boolean none) {
		listHolder.removeAll();
		listHolder.add(none ? empty : scroll, BorderLayout.CENTER);
		listHolder.revalidate();
		listHolder.repaint();
	}

	private JobList.Job selected() {
		int row = list.getSelectedIndex();
		return (row >= 0 && row < model.getSize()) ? model.getElementAt(row) : null;
	}

	private String selectedId() {
		JobList.Job job = selected();
		return (job != null) ? job.id() : null;
	}

	private void syncButtons() {
		JobList.Job job = selected();
		openButton.setEnabled(job != null);
		pauseButton.setVisible(job == null || !job.canResume());
		pauseButton.setEnabled(job != null && job.canPause());
		resumeButton.setVisible(job != null && job.canResume());
		messageButton.setEnabled(job != null && job.canMessage());
		cancelButton.setEnabled(job != null && job.canCancel());
	}

	/** A job's right-click menu: everything the bar and the detail do, and the values worth copying. */
	private JPopupMenu menu(JobList.Job job) {
		JPopupMenu m = new JPopupMenu();
		item(m, "Details", "panel-right-open", this::openSelected);
		if (job.session() != null && job.agent() != null) {
			item(m, "Open conversation", "message-square", () -> host.openConversation(job.agent(), job.session()));
		}
		m.addSeparator();
		if (job.canPause()) item(m, "Pause", "circle-pause", () -> control(job, Control.PAUSE));
		if (job.canResume()) item(m, "Resume", "play", () -> control(job, Control.RESUME));
		if (job.canMessage()) item(m, "Send a message…", "message-square-text", () -> control(job, Control.MESSAGE));
		if (job.canCancel()) item(m, "Cancel…", "circle-x", () -> control(job, Control.CANCEL));
		m.addSeparator();
		item(m, "Copy job id", "copy", () -> Clipboard.copy(job.id(), list));
		if (job.error() != null) item(m, "Copy error", null, () -> Clipboard.copy(job.error(), list));
		return m;
	}

	private static JMenuItem item(JPopupMenu m, String text, String icon, Runnable action) {
		JMenuItem i = new JMenuItem(text, icon == null ? null : Lucide.icon(icon, 14, Theme::muted));
		i.addActionListener(e -> action.run());
		m.add(i);
		return i;
	}

	private static JButton action(String text, String icon, Runnable run) {
		JButton b = Buttons.plain(text, Lucide.icon(icon, 14, Theme::muted));
		b.addActionListener(e -> run.run());
		return b;
	}

	private void openSelected() {
		JobList.Job job = selected();
		if (job != null) open(job.id());
	}

	private void controlSelected(Control control) {
		JobList.Job job = selected();
		if (job != null) control(job, control);
	}

	// ------------------------------------------------------------------
	// Acting on a job
	// ------------------------------------------------------------------

	private void control(JobList.Job job, Control control) {
		String message = null;
		switch (control) {
			case CANCEL -> {
				if (!Dialogs.confirmDanger(this, "Cancel job", "Cancel \"" + job.title() + "\"? Whatever it has done so far stays done.")) return;
			}
			case MESSAGE -> {
				message = Dialogs.prompt(this, "Send a message",
					"What to tell \"" + job.title() + "\" — an answer it asked for, or a word for the agent running it:", "");
				if (message == null || message.isBlank()) return;
			}
			default -> {
			}
		}
		setNote(switch (control) {
			case PAUSE -> "Pausing…";
			case RESUME -> "Resuming…";
			case CANCEL -> "Cancelling…";
			case MESSAGE -> "Sending…";
		}, false);
		host.control(job, control, message, this::setNote);
	}

	private void setNote(String text, boolean error) {
		note.setText(text);
		Styles.classes(note, Styles.SMALL, error ? Styles.ERROR : Styles.MUTED);
	}

	// ------------------------------------------------------------------
	// The detail and the breadcrumb
	// ------------------------------------------------------------------

	private void showList() {
		trail.clear();
		detail = null;
		detailHolder.removeAll();
		cards.show(deck, LIST);
		buildCrumbs(null);
		list.requestFocusInWindow();
	}

	private void showDetail() {
		JobList.Job job = jobAt(trail.get(trail.size() - 1));
		detailHolder.removeAll();
		if (job == null) {
			detail = null;
			detailHolder.add(new EmptyState("search-x", Tone.MUTED, "Job not found",
				"It isn't among your jobs, or its record has been removed."), BorderLayout.CENTER);
		} else {
			detail = new JobDetail(job, new JobDetail.Actions() {
				@Override
				public void control(JobList.Job j, Control c) {
					JobsScreen.this.control(j, c);
				}

				@Override
				public void openConversation(JobList.Job j) {
					host.openConversation(j.agent(), j.session());
				}

				@Override
				public void openJob(String id) {
					trail.add(id);
					showDetail();
				}
			});
			detailHolder.add(Scrolls.vertical(detail), BorderLayout.CENTER);
		}
		detailHolder.revalidate();
		detailHolder.repaint();
		cards.show(deck, DETAIL);
		buildCrumbs(job);
	}

	/** Rebuilds an open detail when its job's record has changed. */
	private void refreshDetail() {
		if (detail == null || trail.isEmpty()) return;
		JobList.Job now = jobAt(trail.get(trail.size() - 1));
		if (now == null || !now.record().equals(detail.job().record())) showDetail();
	}

	/** Job {@code id} as the current snapshot has it, or null. */
	private JobList.Job jobAt(String id) {
		return (owner == null) ? null : JobList.find(index, owner, id);
	}

	/** "Jobs › parent › this one": each step back is a link. */
	private void buildCrumbs(JobList.Job current) {
		crumbs.removeAll();
		if (trail.isEmpty()) {
			crumbs.add(Labels.title("Jobs"));
		} else {
			crumbs.add(Buttons.link("Jobs", this::showList));
			for (int i = 0; i < trail.size() - 1; i++) {
				int depth = i + 1;
				JobList.Job step = jobAt(trail.get(i));
				crumbs.add(Labels.muted("›"));
				crumbs.add(Buttons.link(step != null ? step.title() : "Job", () -> {
					while (trail.size() > depth) trail.remove(trail.size() - 1);
					showDetail();
				}));
			}
			crumbs.add(Labels.muted("›"));
			JLabel here = Labels.lead(current != null ? current.title() : "Job");
			crumbs.add(here, "wmin 0");
		}
		crumbs.revalidate();
		crumbs.repaint();
	}
}
