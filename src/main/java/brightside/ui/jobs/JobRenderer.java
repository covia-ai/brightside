package brightside.ui.jobs;

import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

import brightside.JobList;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;
import net.miginfocom.swing.MigLayout;

/**
 * One row of the Jobs list: the status as an icon, the job's title, a line
 * of what ran, who started it, when and for how long, and the status in words.
 * One panel paints every row, so a long list costs only the rows on screen.
 */
@SuppressWarnings("serial")
final class JobRenderer extends JPanel implements ListCellRenderer<JobList.Job> {

	private final JLabel icon = new JLabel();
	private final JLabel title = Labels.lead(" ");
	private final JLabel meta = Labels.small(" ");
	private final StatusLabel status = new StatusLabel(" ", "history", Tone.MUTED);
	private long now = System.currentTimeMillis();

	JobRenderer() {
		super(new MigLayout("insets 10 14 10 14, fillx, gapx 12, gapy 2", "[]12[grow,fill,100:]12[]", "[][]"));
		add(icon, "spany 2, aligny top, gaptop 2");
		add(title, "wmin 0");
		add(status, "spany 2, aligny center, wrap");
		Styles.classes(status, Styles.SMALL);
		add(meta, "wmin 0");
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.line()), getBorder()));
	}

	/** The time "how long ago" and "for how long" are measured from, set once per paint of the list. */
	void now(long millis) {
		now = millis;
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends JobList.Job> list, JobList.Job job, int index,
			boolean selected, boolean focused) {
		int pad = brightside.ui.components.Density.pad(10);
		((MigLayout) getLayout()).setLayoutConstraints("insets " + pad + " 14 " + pad + " 14, fillx, gapx 12, gapy 2");
		if (job == null) {
			title.setText(" ");
			meta.setText(" ");
			status.setText(" ");
			return this;
		}
		icon.setIcon(Lucide.icon(JobLooks.icon(job), 18, () -> Theme.muted()));
		title.setText(job.title());
		meta.setText(JobLooks.meta(job, now));
		status.setText(JobLooks.status(job));
		status.tone(JobLooks.tone(job));
		status.setIcon(Lucide.icon(JobLooks.icon(job), 14, () -> status.tone().colour()));
		setBackground(selected ? Theme.blend(Theme.panel(), Theme.accent(), 0.14f) : Theme.panel());
		setOpaque(true);
		return this;
	}
}
