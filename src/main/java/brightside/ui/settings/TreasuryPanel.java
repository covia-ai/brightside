package brightside.ui.settings;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.StatusBadge;
import brightside.ui.components.Styles;
import brightside.ui.components.Tone;
import net.miginfocom.swing.MigLayout;

/**
 * Settings → Treasury: your plan and your payments. The plan is Free (your own
 * model keys) or Paid (frontier models included through Covia); until the
 * plan service exists the state comes from a stub, and the page says what
 * each plan gives without inventing usage figures. Payments are Njord's, the
 * treasurer; spending limits are coming.
 */
@SuppressWarnings("serial")
public final class TreasuryPanel extends JPanel {

	/** The plan in force. */
	public enum Plan {
		FREE, PAID;

		/**
		 * The plan until the plan service exists: Free, or Paid when the app is
		 * started with {@code -Dtau.plan=paid} (for trying the paid screens).
		 */
		public static Plan stub() {
			return "paid".equalsIgnoreCase(System.getProperty("tau.plan")) ? PAID : FREE;
		}
	}

	private final JPanel planBox = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", ""));
	private Plan plan = Plan.FREE;

	public TreasuryPanel() {
		super(new BorderLayout());
		JPanel form = new JPanel(new MigLayout("insets 24 28 20 28, fillx, wrap 1", "[grow,fill]", ""));
		form.setOpaque(false);
		form.add(Styles.classes(Labels.text("Plan"), Styles.SECTION));
		planBox.setOpaque(false);
		form.add(planBox, "growx, wmin 0, gapbottom 16");

		form.add(Styles.classes(Labels.text("Payments"), Styles.SECTION));
		form.add(SelectableText.description("Njord, the treasurer, keeps the payments your agents make, each one "
			+ "recorded. Spending limits per agent are coming.").small(), "wmin 0, w 10::");
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.add(form, BorderLayout.NORTH);
		add(brightside.ui.components.Scrolls.vertical(wrap), BorderLayout.CENTER);
		setPlan(Plan.FREE);
	}

	public void setPlan(Plan plan) {
		this.plan = plan;
		planBox.removeAll();
		if (plan == Plan.PAID) {
			planBox.add(new StatusBadge("Paid plan", "credit-card", Tone.SIGNED), "growx 0");
			planBox.add(SelectableText.description("Frontier models are included through Covia, beside any keys "
				+ "of your own. Usage for this month appears here once the plan service is connected.").small(),
				"wmin 0, w 10::");
		} else {
			planBox.add(new StatusBadge("Free plan", "credit-card", Tone.MUTED), "growx 0");
			planBox.add(SelectableText.description(APP_NAME + " is free with your own model keys, set in Models. "
				+ "A paid plan includes frontier models through Covia.").small(), "wmin 0, w 10::");
		}
		// The way from one plan to the other. Payment is Covia's billing, never a form in the app.
		JButton act = (plan == Plan.PAID) ? Buttons.secondary("Manage plan") : Buttons.primary("See plans");
		act.addActionListener(e -> showPlans());
		planBox.add(act, "growx 0, gaptop 8");
		planBox.add(SelectableText.description("Plans are handled by Covia's billing, never inside " + APP_NAME
			+ ". Choosing and managing a plan here is coming.").small().muted(), "wmin 0, w 10::");
		planBox.revalidate();
		planBox.repaint();
	}

	/** What the plan button opens until the plan service exists: an honest word that it is coming. */
	private void showPlans() {
		String text = (plan == Plan.PAID)
			? "Managing your plan is coming. Once the plan service is connected, this opens Covia's billing, where "
				+ "your plan and payment details are kept."
			: "Plans are coming. Once the plan service is connected, this shows what each plan includes and opens "
				+ "Covia's billing to choose one. " + APP_NAME + " never asks for card details.";
		JOptionPane.showMessageDialog(this, text, plan == Plan.PAID ? "Manage plan" : "Plans",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/** The plan button's words, for tests. */
	String planAction() {
		for (java.awt.Component c : planBox.getComponents()) if (c instanceof JButton b) return b.getText();
		return null;
	}

	public Plan plan() {
		return plan;
	}
}
