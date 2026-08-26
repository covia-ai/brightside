package covia.brightside.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import covia.brightside.model.Providers;

/**
 * <b>Model &amp; API key</b> settings — the same choice as onboarding, to change
 * the provider/model or replace the API key later. The model change applies live
 * (the running agent is re-configured); a new API key is stored encrypted in the
 * vault and takes effect on the next start (the venue provisions secrets at
 * launch). Non-modal.
 */
@SuppressWarnings("serial")
public final class SettingsDialog extends JDialog {

	/** What the dialog does on Save; {@code apiKey} is null when left blank. */
	public interface Handler {
		void applyModel(String providerId, String modelId);

		boolean storeApiKey(String providerId, String apiKey);
	}

	private final Handler handler;
	private final JComboBox<Providers.Provider> provider = new JComboBox<>();
	private final JComboBox<Providers.Model> model = new JComboBox<>();
	private final JPasswordField keyField = new JPasswordField(28);
	private final JLabel note = new JLabel(" ");

	public SettingsDialog(Frame owner, String currentModelOp, Handler handler) {
		super(owner, "Model & API key", false);
		this.handler = handler;
		setContentPane(build());
		preselect(currentModelOp);
		pack();
		setMinimumSize(new Dimension(520, getPreferredSize().height));
		setLocationRelativeTo(owner);
	}

	private JComponent build() {
		JPanel root = new JPanel(new BorderLayout());
		root.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

		JLabel title = new JLabel("Model & API key");
		title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 4f).deriveFont(java.awt.Font.BOLD));

		provider.setModel(new DefaultComboBoxModel<>(Providers.ALL.toArray(new Providers.Provider[0])));
		provider.setRenderer(renderer(o -> ((Providers.Provider) o).label()));
		model.setRenderer(renderer(o -> ((Providers.Model) o).label()));
		provider.addActionListener(e -> onProvider());
		keyField.putClientProperty("JTextField.placeholderText", "Paste a new key to replace it (leave blank to keep)");

		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 0, 6, 8);
		c.anchor = GridBagConstraints.WEST;
		c.gridx = 0;
		c.gridy = 0;
		form.add(muted("Provider"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(provider, c);
		c.gridx = 0;
		c.gridy = 1;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(muted("Model"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(model, c);
		c.gridx = 0;
		c.gridy = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(muted("API key"), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(keyField, c);

		note.putClientProperty("FlatLaf.styleClass", "small");
		note.setForeground(mutedColor());

		JButton cancel = new JButton("Close");
		cancel.addActionListener(e -> dispose());
		JButton save = new JButton("Save");
		save.putClientProperty("JButton.buttonType", "roundRect");
		save.setBackground(LAF.ACCENT);
		save.setForeground(java.awt.Color.WHITE);
		save.addActionListener(e -> onSave());
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.add(note);
		buttons.add(Box.createHorizontalGlue());
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(save);

		JPanel centre = new JPanel();
		centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		centre.add(title);
		centre.add(Box.createVerticalStrut(14));
		centre.add(form);

		root.add(centre, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		return root;
	}

	private void onProvider() {
		Providers.Provider p = (Providers.Provider) provider.getSelectedItem();
		if (p == null) return;
		model.setModel(new DefaultComboBoxModel<>(p.models().toArray(new Providers.Model[0])));
		boolean needsKey = p.secretName() != null;
		keyField.setEnabled(needsKey);
		keyField.putClientProperty("JTextField.placeholderText",
			needsKey ? "Paste a new key to replace it (leave blank to keep)" : "No key needed for this provider");
	}

	private void preselect(String modelOp) {
		String providerId = Providers.providerOf(modelOp);
		Providers.Provider p = (providerId != null) ? Providers.byId(providerId) : Providers.defaultProvider();
		provider.setSelectedItem(p != null ? p : Providers.defaultProvider());
		onProvider();
		// Select the current model if present.
		String modelId = (modelOp != null && providerId != null)
			? modelOp.substring(("v/models/" + providerId + "/").length()) : null;
		for (int i = 0; modelId != null && i < model.getItemCount(); i++) {
			if (model.getItemAt(i).id().equals(modelId)) {
				model.setSelectedIndex(i);
				break;
			}
		}
	}

	private void onSave() {
		Providers.Provider p = (Providers.Provider) provider.getSelectedItem();
		Providers.Model m = (Providers.Model) model.getSelectedItem();
		if (p == null || m == null) return;
		handler.applyModel(p.id(), m.id());
		char[] key = keyField.getPassword();
		if (key.length > 0) {
			boolean ok = handler.storeApiKey(p.id(), new String(key));
			note.setForeground(ok ? mutedColor() : new java.awt.Color(0xE5, 0x53, 0x53));
			note.setText(ok ? "Saved. The new key applies after a restart." : "Couldn't store the key.");
			keyField.setText("");
			return;
		}
		note.setForeground(mutedColor());
		note.setText("Saved.");
	}

	private static JLabel muted(String text) {
		JLabel l = new JLabel(text);
		l.setForeground(mutedColor());
		return l;
	}

	private static java.awt.Color mutedColor() {
		java.awt.Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : java.awt.Color.GRAY;
	}

	private static javax.swing.ListCellRenderer<Object> renderer(java.util.function.Function<Object, String> text) {
		javax.swing.DefaultListCellRenderer base = new javax.swing.DefaultListCellRenderer();
		return (list, value, index, selected, focus) -> {
			Component comp = base.getListCellRendererComponent(list, value, index, selected, focus);
			if (value != null && comp instanceof JLabel jl) jl.setText(text.apply(value));
			return comp;
		};
	}
}
