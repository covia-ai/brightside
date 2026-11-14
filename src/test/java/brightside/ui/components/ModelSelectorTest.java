package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JComboBox;

import org.junit.jupiter.api.Test;

import brightside.model.Providers;

/** What the model picker offers once the venue's catalogue arrives. Headless: no window is shown. */
class ModelSelectorTest {

	private static List<String> ids(JComboBox<Providers.Model> field) {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < field.getItemCount(); i++) ids.add(field.getItemAt(i).id());
		return ids;
	}

	@Test
	void addsCatalogueModelsAndDropsOnesTheVenueLacks() {
		ModelSelector s = new ModelSelector();
		s.selectModelOp("v/models/openai/gpt-5.4-mini");
		s.offer(Map.of("openai", List.of("gpt-5.4-mini", "gpt-5.6-sol")));
		List<String> ids = ids(s.modelField());
		assertTrue(ids.contains("gpt-5.6-sol"), "a catalogued model is offered");
		assertFalse(ids.contains("gpt-5.6-terra"), "a model the venue does not catalogue is dropped");
		assertEquals("v/models/openai/gpt-5.4-mini", s.selectedModelOp());
	}

	@Test
	void keepsTheModelInUseEvenWhenNotCatalogued() {
		ModelSelector s = new ModelSelector();
		s.selectModelOp("v/models/openai/gpt-5.6-terra");
		s.offer(Map.of("openai", List.of("gpt-5.6-sol")));
		assertTrue(ids(s.modelField()).contains("gpt-5.6-terra"));
		assertEquals("v/models/openai/gpt-5.6-terra", s.selectedModelOp());
	}

	@Test
	void anyModelProviderKeepsItsOwnList() {
		ModelSelector s = new ModelSelector();
		s.selectModelOp("v/models/openrouter/openrouter/auto");
		s.offer(Map.of("openrouter", List.of("google/gemini-x")));
		List<String> ids = ids(s.modelField());
		assertTrue(ids.contains("google/gemini-x"));
		assertTrue(ids.contains("anthropic/claude-sonnet-5"));
	}
}
