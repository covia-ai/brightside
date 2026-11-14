package brightside.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.text.JTextComponent;

import brightside.model.Providers;
import net.miginfocom.swing.MigLayout;

/**
 * Shared provider/model picker used by onboarding, Settings and agent creation.
 * For a provider that takes any model, the model field is editable and holds
 * the model's id: pick a listed one, or type any id the provider serves.
 */
@SuppressWarnings("serial")
public final class ModelSelector extends JPanel {

	private final JComboBox<Providers.Provider> provider = new JComboBox<>();
	private final JComboBox<Providers.Model> model = new JComboBox<>();
	private final IdEditor idEditor = new IdEditor();
	private final List<Runnable> listeners = new ArrayList<>();
	private Map<String, List<String>> offered = Map.of();
	private boolean syncing;

	public ModelSelector() {
		super(new MigLayout("insets 0, fillx, wrap 2", "[]12[grow,fill]", ""));
		setOpaque(false);
		provider.setModel(new DefaultComboBoxModel<>(Providers.ALL.toArray(new Providers.Provider[0])));
		provider.setRenderer(renderer());
		model.setRenderer(renderer());
		model.setEditor(idEditor);
		provider.setToolTipText("The model provider");
		provider.addActionListener(e -> providerChanged());
		model.addActionListener(e -> fireSelectionChanged());
		// A typed id is the choice as it stands, before Enter or a change of focus.
		Documents.onChange((JTextComponent) idEditor.getEditorComponent(), this::fireSelectionChanged);

		add(new JLabel("Provider"));
		add(provider, "growx, wmin 180");
		add(new JLabel("Model"));
		add(model, "growx, wmin 180");
		providerChanged();
	}

	/**
	 * The provider picker, for a form that labels its own rows (Settings →
	 * Model): adding it there takes it out of this panel, which is then left
	 * unshown while it goes on choosing.
	 */
	public JComboBox<Providers.Provider> providerField() {
		return provider;
	}

	/** The model picker; as {@link #providerField}. */
	public JComboBox<Providers.Model> modelField() {
		return model;
	}

	/**
	 * Select an operation path such as {@code v/models/anthropic/claude-sonnet-5-5}.
	 * A model the list does not offer is added to it, so the field shows the
	 * choice in force rather than another one.
	 */
	public void selectModelOp(String modelOp) {
		syncing = true;
		String providerId = Providers.providerOf(modelOp);
		Providers.Provider selected = (providerId != null) ? Providers.byId(providerId) : null;
		provider.setSelectedItem((selected != null) ? selected : Providers.defaultProvider());
		reloadModels();
		String modelId = (selected != null) ? Providers.modelOf(modelOp) : null;
		if (modelId != null && !modelId.isEmpty()) {
			Providers.Model current = listed(modelId);
			if (current == null) {
				current = new Providers.Model(modelId, modelId);
				((DefaultComboBoxModel<Providers.Model>) model.getModel()).addElement(current);
			}
			model.setSelectedItem(current);
		}
		syncing = false;
		fireSelectionChanged();
	}

	/**
	 * Offers the models the venue catalogues and serves for each provider
	 * ({@link Providers#offered}), keeping the choice as it stands. Ids the
	 * list lacks are added after the provider's own. For a provider that runs
	 * only what the venue catalogues, a listed model the venue does not offer
	 * is dropped, unless it is the choice in force.
	 */
	public void offer(Map<String, List<String>> offeredModels) {
		offered = Map.copyOf(offeredModels);
		applyOffered(true);
	}

	public Providers.Provider selectedProvider() {
		return (Providers.Provider) provider.getSelectedItem();
	}

	/** The chosen model; for an editable field, the id typed so far. Null when there is none. */
	public Providers.Model selectedModel() {
		if (!model.isEditable()) return (Providers.Model) model.getSelectedItem();
		String id = idEditor.text();
		if (id.isEmpty()) return null;
		Providers.Model current = listed(id);
		return (current != null) ? current : new Providers.Model(id, id);
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
		model.setEditable(p.anyModel());
		model.setToolTipText(p.anyModel()
			? "Which model to use: pick one, or type the id of any model " + p.label() + " serves"
			: "Which model to use");
		applyOffered(false);
	}

	/**
	 * Appends the selected provider's offered models that the list lacks,
	 * labelled by id, and drops the ones it cannot run (see {@link #offer}).
	 */
	private void applyOffered(boolean keepChoice) {
		Providers.Provider p = selectedProvider();
		if (p == null) return;
		DefaultComboBoxModel<Providers.Model> list = (DefaultComboBoxModel<Providers.Model>) model.getModel();
		List<String> ids = offered.getOrDefault(p.id(), List.of());
		if (!ids.isEmpty() && !p.anyModel()) {
			Object chosen = keepChoice ? list.getSelectedItem() : null;
			for (int i = list.getSize() - 1; i >= 0; i--) {
				Providers.Model m = list.getElementAt(i);
				if (m != chosen && !ids.contains(m.id())) list.removeElementAt(i);
			}
		}
		for (String id : ids) {
			if (listed(id) == null) list.addElement(new Providers.Model(id, id));
		}
	}

	/** The model in the list with this id, or null. */
	private Providers.Model listed(String modelId) {
		for (int i = 0; i < model.getItemCount(); i++) {
			if (model.getItemAt(i).id().equals(modelId)) return model.getItemAt(i);
		}
		return null;
	}

	private void fireSelectionChanged() {
		if (syncing) return;
		for (Runnable listener : List.copyOf(listeners)) listener.run();
	}

	/** The editable model field shows and takes a model's id, not its label. */
	private static final class IdEditor extends BasicComboBoxEditor {
		@Override
		public void setItem(Object item) {
			super.setItem((item instanceof Providers.Model m) ? m.id() : item);
		}

		String text() {
			return editor.getText().trim();
		}
	}

	/** Labels for providers and models; a typed id shows as itself. */
	private static DefaultListCellRenderer renderer() {
		return new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean selected, boolean focus) {
				java.awt.Component c = super.getListCellRendererComponent(list, value, index, selected, focus);
				if (c instanceof JLabel label && value != null) {
					label.setText((value instanceof Providers.Provider p) ? p.label()
						: (value instanceof Providers.Model m) ? m.label() : value.toString());
				}
				return c;
			}
		};
	}
}
