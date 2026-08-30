package brightside.ui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import brightside.model.Providers;
import net.miginfocom.swing.MigLayout;

/** Shared provider/model picker used by onboarding, Settings and agent creation. */
@SuppressWarnings("serial")
public final class ModelSelector extends JPanel {

	private final JComboBox<Providers.Provider> provider = new JComboBox<>();
	private final JComboBox<Providers.Model> model = new JComboBox<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private boolean syncing;

	public ModelSelector() {
		super(new MigLayout("insets 0, fillx, wrap 2", "[]12[grow,fill]", ""));
		setOpaque(false);
		provider.setModel(new DefaultComboBoxModel<>(Providers.ALL.toArray(new Providers.Provider[0])));
		provider.setRenderer(renderer(true));
		model.setRenderer(renderer(false));
		provider.setToolTipText("The model provider");
		model.setToolTipText("Which model to use");
		provider.addActionListener(e -> providerChanged());
		model.addActionListener(e -> fireSelectionChanged());

		add(new JLabel("Provider"));
		add(provider, "growx, wmin 180");
		add(new JLabel("Model"));
		add(model, "growx, wmin 180");
		providerChanged();
	}

	/** Select an operation path such as {@code v/models/anthropic/claude-sonnet-5}. */
	public void selectModelOp(String modelOp) {
		syncing = true;
		String providerId = Providers.providerOf(modelOp);
		Providers.Provider selected = (providerId != null) ? Providers.byId(providerId) : null;
		provider.setSelectedItem((selected != null) ? selected : Providers.defaultProvider());
		reloadModels();
		String prefix = (providerId != null) ? "v/models/" + providerId + "/" : null;
		String modelId = (prefix != null && modelOp != null && modelOp.startsWith(prefix))
			? modelOp.substring(prefix.length()) : null;
		for (int i = 0; modelId != null && i < model.getItemCount(); i++) {
			if (model.getItemAt(i).id().equals(modelId)) {
				model.setSelectedIndex(i);
				break;
			}
		}
		syncing = false;
		fireSelectionChanged();
	}

	public Providers.Provider selectedProvider() {
		return (Providers.Provider) provider.getSelectedItem();
	}

	public Providers.Model selectedModel() {
		return (Providers.Model) model.getSelectedItem();
	}

	public String selectedModelOp() {
		Providers.Provider p = selectedProvider();
		Providers.Model m = selectedModel();
		return (p != null && m != null) ? Providers.modelOp(p.id(), m.id()) : null;
	}

	public void addSelectionListener(Runnable listener) {
		if (listener != null) listeners.add(listener);
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		if (provider != null) provider.setEnabled(enabled);
		if (model != null) model.setEnabled(enabled);
	}

	private void providerChanged() {
		reloadModels();
		fireSelectionChanged();
	}

	private void reloadModels() {
		Providers.Provider p = selectedProvider();
		if (p == null) return;
		model.setModel(new DefaultComboBoxModel<>(p.models().toArray(new Providers.Model[0])));
	}

	private void fireSelectionChanged() {
		if (syncing) return;
		for (Runnable listener : List.copyOf(listeners)) listener.run();
	}

	private static DefaultListCellRenderer renderer(boolean providerChoice) {
		return new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean selected, boolean focus) {
				java.awt.Component c = super.getListCellRendererComponent(list, value, index, selected, focus);
				if (c instanceof JLabel label && value != null) {
					label.setText(providerChoice
						? ((Providers.Provider) value).label() : ((Providers.Model) value).label());
				}
				return c;
			}
		};
	}
}
