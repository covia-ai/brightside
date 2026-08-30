package brightside.ui.settings;

import java.util.Objects;

import javax.swing.JPasswordField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import brightside.model.Providers;
import brightside.ui.ModelSelector;

/**
 * The <b>Model</b> settings page: pick the provider and model, and replace the API
 * key. Model changes apply live; a new key is stored encrypted and applies at the
 * next start. The <b>Save</b> action stays disabled until something changes.
 */
@SuppressWarnings("serial")
public final class ModelPanel extends SettingsPage {

	/** What Save does; {@code apiKey} is null when left blank. */
	public interface Handler {
		void applyModel(String providerId, String modelId);

		boolean storeApiKey(String providerId, String apiKey);
	}

	private final Handler handler;
	private final ModelSelector modelSelector = new ModelSelector();
	private final JPasswordField keyField = new JPasswordField(24);

	private String baselineProvider;
	private String baselineModel;
	private boolean syncing;

	public ModelPanel(Handler handler, String currentModelOp) {
		super("Save");
		this.handler = handler;
		build();
		preselect(currentModelOp);
	}

	private void build() {
		keyField.setToolTipText("The provider's API key — stored encrypted; a new key applies at the next start");
		keyField.setFont(SettingsUI.technicalFont(keyField.getFont()));
		modelSelector.addSelectionListener(this::onModelSelection);
		keyField.putClientProperty("JTextField.placeholderText", "Paste a new key to replace it (leave blank to keep)");
		keyField.getDocument().addDocumentListener((SimpleDoc) e -> updateDirty());

		primary.setEnabled(false);
		primary.setToolTipText("Apply the selected model and store any new key");
		onPrimary(this::onSave);

		addDescription("Choose the model your assistant thinks with. Model changes apply immediately; a new API key "
			+ "is stored encrypted and applies at the next start.");
		addSpan(modelSelector);
		addField("API key", keyField);
	}

	private void onModelSelection() {
		Providers.Provider p = modelSelector.selectedProvider();
		if (p == null) return;
		boolean needsKey = p.secretName() != null;
		keyField.setEnabled(needsKey);
		keyField.putClientProperty("JTextField.placeholderText",
			needsKey ? "Paste a new key to replace it (leave blank to keep)" : "No key needed for this provider");
		updateDirty();
	}

	/** Preselect the provider/model for {@code modelOp} and re-baseline (Save disabled). */
	public void preselect(String modelOp) {
		syncing = true;
		modelSelector.selectModelOp(modelOp);
		keyField.setText("");
		baselineProvider = currentProviderId();
		baselineModel = currentModelId();
		clearNote();
		syncing = false;
		updateDirty();
	}

	private void updateDirty() {
		if (syncing) return;
		boolean changed = !Objects.equals(currentProviderId(), baselineProvider)
			|| !Objects.equals(currentModelId(), baselineModel)
			|| keyField.getPassword().length > 0;
		primary.setEnabled(changed);
	}

	private String currentProviderId() {
		Providers.Provider p = modelSelector.selectedProvider();
		return (p != null) ? p.id() : null;
	}

	private String currentModelId() {
		Providers.Model m = modelSelector.selectedModel();
		return (m != null) ? m.id() : null;
	}

	private void onSave() {
		Providers.Provider p = modelSelector.selectedProvider();
		Providers.Model m = modelSelector.selectedModel();
		if (p == null || m == null) return;
		handler.applyModel(p.id(), m.id());
		char[] key = keyField.getPassword();
		boolean keyStored = false;
		boolean keyOk = true;
		if (key.length > 0) {
			keyOk = handler.storeApiKey(p.id(), new String(key));
			keyStored = keyOk;
		}
		keyField.setText("");
		baselineProvider = p.id();
		baselineModel = m.id();
		setNote(!keyOk ? "Couldn't store the key."
			: keyStored ? "Saved. The new key applies after a restart." : "Saved.", !keyOk);
		updateDirty();
	}

	/** A DocumentListener whose three methods collapse to one callback. */
	@FunctionalInterface
	private interface SimpleDoc extends DocumentListener {
		void update(DocumentEvent e);

		@Override
		default void insertUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void removeUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void changedUpdate(DocumentEvent e) {
			update(e);
		}
	}
}
