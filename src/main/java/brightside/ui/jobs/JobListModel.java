package brightside.ui.jobs;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.AbstractListModel;

import brightside.JobList;

/**
 * The Jobs list's model over one {@link JobList.View}: a row's {@link JobList.Job}
 * is projected from the lattice only when the list asks for it, which a
 * {@link javax.swing.JList} with a fixed cell height does only for the rows on
 * screen. The few most recently asked for are kept, so scrolling back and the
 * once-a-second repaint of running jobs project nothing new. Event thread only.
 */
@SuppressWarnings("serial")
final class JobListModel extends AbstractListModel<JobList.Job> {

	/** Enough rows for a few screens of scrolling. */
	private static final int KEPT = 256;

	private JobList.View view;
	private final Map<Integer, JobList.Job> kept = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, JobList.Job> eldest) {
			return size() > KEPT;
		}
	};

	JobList.View view() {
		return view;
	}

	/** Shows {@code next}; the list repaints only the rows on screen. */
	void setView(JobList.View next) {
		int before = getSize();
		view = next;
		kept.clear();
		int after = getSize();
		if (after < before) fireIntervalRemoved(this, after, before - 1);
		else if (after > before) fireIntervalAdded(this, before, after - 1);
		if (Math.min(before, after) > 0) fireContentsChanged(this, 0, Math.min(before, after) - 1);
	}

	/** How many rows it holds projected: what the list has asked for lately. */
	int projected() {
		return kept.size();
	}

	@Override
	public int getSize() {
		return (view == null) ? 0 : view.size();
	}

	@Override
	public JobList.Job getElementAt(int row) {
		return kept.computeIfAbsent(row, view::job);
	}
}
