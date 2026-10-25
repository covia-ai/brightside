package brightside.ui.settings;

import java.util.Objects;
import java.util.function.Supplier;

import javax.swing.JPasswordField;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.KeyCheck;
import brightside.model.Providers;
import brightside.ui.components.Documents;
import brightside.ui.components.Fields;
import brightside.ui.components.KeyTest;
import brightside.ui.components.ModelSelector;
import brightside.ui.components.Styles;

/**
 * The <b>Model</b> settings page: pick the provider and model, and replace the API
 * key. Both apply live: a new key is stored encrypted in the venue's secret
 * stores, which model calls read each time. The <b>Save</b> action stays
 * disabled until something changes. <b>Test</b> checks the selected model with
 * the key typed in the field, or the stored key when the field is blank,
 * without saving anything.
 */
@SuppressWarnings("serial")
public final class ModelPanel extends SettingsPage {

	/** What Save does; {@code apiKey} is null when left blank. */
	public interface Handler {
		void applyModel(String providerId, String modelId);

		boolean storeApiKey(String providerId, String apiKey);

		/** One tiny call to the model, with {@code apiKey} if given, else the stored key. Off the event thread. */
		KeyCheck.Result testModel(String providerId, String modelId, String apiKey);
	}

	private final Handler handler;
	private final ModelSelector modelSelector = new ModelSelector();
	private final JPasswordField keyField = Fields.secret(new JPasswordField(24));
	private final KeyTest keyTest = new KeyTest(this::keyTestCall, 640, false);

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
		keyField.setToolTipText("The provider's API key, stored encrypted on the venue and used from the next message");
		Styles.classes(keyField, Styles.MONOSPACED);
		modelSelector.addSelectionListener(this::onModelSelection);
		keyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Paste a new key to replace it (leave blank to keep)");
		Documents.onChange(keyField, this::updateDirty);
		Documents.onChange(keyField, keyTest::reset);

		primary.setEnabled(false);
		primary.setToolTipText("Apply the selected model and store any new key");
		onPrimary(this::onSave);

		group(null);
		addField("Provider", modelSelector.providerField());
		addField("Model", modelSelector.modelField());
		addField("API key", keyField);
		addField("", keyTest);
	}

	/** Read on the event thread when Test is pressed: the selection and any typed key. */
	private Supplier<KeyCheck.Result> keyTestCall() {
		String providerId = currentProviderId();
		String modelId = currentModelId();
		char[] typed = keyField.getPassword();
		String key = (typed.length > 0) ? new String(typed) : null;
		return () -> handler.testModel(providerId, modelId, key);
	}

	private void onModelSelection() {
		Providers.Provider p = modelSelector.selectedProvider();
		if (p == null) return;
		boolean needsKey = p.secretName() != null;
		keyField.setEnabled(needsKey);
		keyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
			needsKey ? "Paste a new key to replace it (leave blank to keep)" : "No key needed for this provider");
		keyTest.reset();
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
		if (!keyOk) Fields.flag(keyField);
		updateDirty();
	}
}
