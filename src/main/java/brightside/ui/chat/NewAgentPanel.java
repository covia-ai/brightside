package brightside.ui.chat;

import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.model.AgentTemplate;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.ModelSelector;
import brightside.ui.components.SelectableText;
import net.miginfocom.swing.MigLayout;

/** Name, starting template and model choices for a new agent. */
@SuppressWarnings("serial")
public final class NewAgentPanel extends JPanel {

	/** The choices needed to create and configure an agent. */
	public record Options(String name, AgentTemplate template, String modelOp) {
		public String systemPrompt() {
			return template.systemPrompt(name);
		}
	}

	private final JTextField name = new JTextField(24);
	private final JComboBox<AgentTemplate> template = new JComboBox<>(AgentTemplate.values());
	private final SelectableText templateDescription = descriptionArea();
	private final ModelSelector model = new ModelSelector();

	public NewAgentPanel(String currentModelOp) {
		super(new MigLayout("insets 8, fillx, wrap 2", "[]14[grow,fill]", ""));
		setOpaque(false);
		name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Research partner");
		name.setToolTipText("The name shown in the agent list");
		template.setToolTipText("A starting role and working style; the agent can grow from here");
		template.addActionListener(e -> updateTemplateDescription());
		model.selectModelOp(currentModelOp);

		add(Labels.text("Name"));
		add(name, "growx");
		add(Labels.text("Starting point"));
		add(template, "growx");
		add(templateDescription, "skip, growx, wmin 0, gapbottom 8");
		add(model, "span 2, growx, wmin 0");
		SelectableText note = descriptionArea();
		note.setText("The model uses API credentials already stored in Settings. The template is only a starting point.");
		add(note, "span 2, growx, wmin 0, gaptop 8");
		updateTemplateDescription();
		setPreferredSize(new Dimension(500, getPreferredSize().height));
	}

	/** Shows the shared form in a modal dialog, returning null when cancelled. */
	public static Options showDialog(Component parent, String currentModelOp) {
		NewAgentPanel panel = new NewAgentPanel(currentModelOp);
		while (true) {
			SwingUtilities.invokeLater(panel.name::requestFocusInWindow);
			if (!Dialogs.form(parent, "New agent", panel)) return null;
			Options options = panel.options();
			if (options != null) return options;
			Dialogs.warn(parent, "New agent", "Give the agent a name.");
		}
	}

	Options options() {
		String chosenName = name.getText().trim();
		AgentTemplate chosenTemplate = (AgentTemplate) template.getSelectedItem();
		String modelOp = model.selectedModelOp();
		return (chosenName.isEmpty() || chosenTemplate == null || modelOp == null)
			? null : new Options(chosenName, chosenTemplate, modelOp);
	}

	JTextField nameField() {
		return name;
	}

	JComboBox<AgentTemplate> templateField() {
		return template;
	}

	private void updateTemplateDescription() {
		AgentTemplate selected = (AgentTemplate) template.getSelectedItem();
		templateDescription.setText((selected != null) ? selected.description() : " ");
	}

	/** Two lines of small, muted explanation that never takes focus from the fields. */
	private static SelectableText descriptionArea() {
		SelectableText area = SelectableText.description(" ").small().unfocusable();
		area.setRows(2);
		area.setColumns(20);
		return area;
	}
}
