package covia.brightside.ui.chat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import covia.brightside.model.AgentTemplate;
import covia.brightside.ui.ModelSelector;
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
	private final JTextArea templateDescription = descriptionArea();
	private final ModelSelector model = new ModelSelector();

	public NewAgentPanel(String currentModelOp) {
		super(new MigLayout("insets 8, fillx, wrap 2", "[]14[grow,fill]", ""));
		setOpaque(false);
		name.putClientProperty("JTextField.placeholderText", "e.g. Research partner");
		name.setToolTipText("The name shown in the agent list");
		template.setToolTipText("A starting role and working style; the agent can grow from here");
		template.addActionListener(e -> updateTemplateDescription());
		model.selectModelOp(currentModelOp);

		add(new JLabel("Name"));
		add(name, "growx");
		add(new JLabel("Starting point"));
		add(template, "growx");
		add(templateDescription, "skip, growx, wmin 0, gapbottom 8");
		add(model, "span 2, growx, wmin 0");
		JTextArea note = descriptionArea();
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
			int choice = JOptionPane.showConfirmDialog(parent, panel, "New agent",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) return null;
			Options options = panel.options();
			if (options != null) return options;
			JOptionPane.showMessageDialog(parent, "Give the agent a name.", "New agent", JOptionPane.WARNING_MESSAGE);
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

	private static JTextArea descriptionArea() {
		JTextArea area = new JTextArea(2, 20);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		Color muted = UIManager.getColor("Label.disabledForeground");
		if (muted != null) area.setForeground(muted);
		area.putClientProperty("FlatLaf.styleClass", "small");
		return area;
	}
}
