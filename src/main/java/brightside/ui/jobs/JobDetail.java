package brightside.ui.jobs;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.JobList;
import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.SelectableText;
import brightside.ui.components.StatusLabel;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Times;
import convex.core.data.ACell;
import convex.core.util.JSON;
import covia.api.Fields;
import net.miginfocom.swing.MigLayout;

/**
 * Everything about one job: its status and the actions it allows, then its
 * times, who started it, what it ran, the agent and conversation, the job it
 * ran inside, its tokens, and its input, output, error and history in full,
 * each copyable. Built for one snapshot of the job; the Jobs screen builds a
 * new one when the record changes.
 */
@SuppressWarnings("serial")
final class JobDetail extends JPanel {

	/** What the detail asks of the Jobs screen. */
	interface Actions {
		void control(JobList.Job job, JobsScreen.Control control);

		void openConversation(JobList.Job job);

		/** Opens job {@code id}, the one this one ran inside. */
		void openJob(String id);
	}

	private final JobList.Job job;
	private final JLabel running = Labels.small(" ");

	JobDetail(JobList.Job job, Actions actions) {
		super(new MigLayout("insets 20 28 24 28, fillx, wrap 2, gapy 8", "[]16[grow,fill]", ""));
		this.job = job;
		setOpaque(false);

		// The title and how it stands, with what can be done about it.
		JLabel title = Labels.title(job.title());
		add(title, "span, wmin 0");
		JPanel standing = new JPanel(new MigLayout("insets 0, gap 10", "", ""));
		standing.setOpaque(false);
		StatusLabel status = new StatusLabel(JobLooks.status(job), JobLooks.icon(job), JobLooks.tone(job));
		standing.add(status);
		standing.add(running);
		if (job.canPause()) standing.add(action("Pause", "circle-pause", () -> actions.control(job, JobsScreen.Control.PAUSE)));
		if (job.canResume()) standing.add(action("Resume", "play", () -> actions.control(job, JobsScreen.Control.RESUME)));
		if (job.canMessage()) standing.add(action("Send a message…", "message-square-text",
			() -> actions.control(job, JobsScreen.Control.MESSAGE)));
		if (job.canCancel()) standing.add(action("Cancel…", "circle-x", () -> actions.control(job, JobsScreen.Control.CANCEL)));
		add(standing, "span, gapbottom 8");

		field("Operation", job.name() + (job.operation() != null && !job.operation().equals(job.name())
			? "  (" + job.operation() + ")" : ""), false);
		if (job.purpose() != null && !job.purpose().equals(job.title())) field("For", job.purpose(), false);
		field("Started", job.created() > 0 ? Times.exact(job.created()) : null, false);
		if (job.finished() && job.updated() > 0) field("Finished", Times.exact(job.updated()), false);
		else if (job.updated() > 0) field("Last update", Times.exact(job.updated()), false);
		field("Started by", JobLooks.startedBy(job) + (job.caller() != null ? "  (" + job.caller() + ")" : ""), false);
		if (job.agent() != null) field("Agent", job.agent(), false);
		if (job.session() != null && job.agent() != null) {
			add(Labels.small("Conversation"), "aligny top");
			JButton open = new JButton("Open " + job.agent() + "'s conversation", Lucide.icon("message-square", 14, Theme::muted));
			open.setToolTipText("What the agent did in it, step by step, as the chat shows it");
			open.addActionListener(e -> actions.openConversation(job));
			add(open, "growx 0");
		}
		if (job.parent() != null) {
			add(Labels.small("Ran inside"), "aligny top");
			add(Buttons.link("Job " + job.parent(), () -> actions.openJob(job.parent())), "growx 0");
		}
		if (job.tokens() > 0) field("Tokens", String.format("%,d", job.tokens()), false);
		field("Job id", job.id(), true);
		json("Input", job.record().get(Fields.INPUT), false);
		json("Output", job.record().get(Fields.OUTPUT), false);
		if (job.error() != null) {
			add(Labels.small("Error"), "aligny top");
			add(new SelectableText(job.error()).tone(Styles.ERROR), "growx, wmin 0");
		}
		json("History", job.record().get(Fields.HISTORY), false);
		tick(System.currentTimeMillis());
	}

	JobList.Job job() {
		return job;
	}

	/** Keeps "running for" current while the job is going. */
	void tick(long now) {
		running.setText(job.finished() || job.created() <= 0 ? (job.created() > 0 ? "Took " + Times.elapsed(job.elapsed(now)) : " ")
			: "For " + Times.elapsed(job.elapsed(now)));
	}

	private static JButton action(String text, String icon, Runnable run) {
		JButton b = Buttons.plain(text, Lucide.icon(icon, 14, Theme::muted));
		b.addActionListener(e -> run.run());
		return b;
	}

	private void field(String label, String value, boolean mono) {
		if (value == null) return;
		add(Labels.small(label), "aligny top");
		JPanel line = new JPanel(new MigLayout("insets 0, fillx, gap 4", "[grow,fill][]", ""));
		line.setOpaque(false);
		SelectableText text = new SelectableText(value);
		if (mono) text.mono();
		line.add(text, "growx, wmin 0");
		line.add(Buttons.copy("the " + label.toLowerCase(), () -> value));
		add(line, "growx, wmin 0");
	}

	private void json(String label, ACell value, boolean always) {
		if (value == null && !always) return;
		String text = (value == null) ? "" : JSON.toStringPretty(value);
		add(Labels.small(label), "aligny top");
		JPanel line = new JPanel(new MigLayout("insets 0, fillx, gap 4", "[grow,fill][]", "[top]"));
		line.setOpaque(false);
		JComponent shown = new SelectableText(text).mono();
		line.add(shown, "growx, wmin 0");
		line.add(Buttons.copy("the " + label.toLowerCase(), () -> text), "aligny top");
		add(line, "growx, wmin 0");
	}
}
