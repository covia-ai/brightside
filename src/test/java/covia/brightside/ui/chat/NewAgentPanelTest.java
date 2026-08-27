package covia.brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import covia.brightside.model.AgentTemplate;
import covia.brightside.ui.ModelSelector;

final class NewAgentPanelTest {

	@Test
	void collectsNameTemplateAndModelThroughTheSharedSelector() throws Exception {
		AtomicReference<NewAgentPanel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			NewAgentPanel panel = new NewAgentPanel("v/models/openai/gpt-5.4-mini");
			panel.nameField().setText("Code Partner");
			panel.templateField().setSelectedItem(AgentTemplate.SOFTWARE);
			result.set(panel);
		});

		NewAgentPanel panel = result.get();
		NewAgentPanel.Options options = panel.options();
		assertNotNull(options);
		assertEquals("Code Partner", options.name());
		assertEquals(AgentTemplate.SOFTWARE, options.template());
		assertEquals("v/models/openai/gpt-5.4-mini", options.modelOp());
		assertTrue(options.systemPrompt().contains("Code Partner"));
		assertEquals(1, descendants(panel, ModelSelector.class).size());
	}

	private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
		List<T> found = new ArrayList<>();
		if (type.isInstance(root)) found.add(type.cast(root));
		if (root instanceof Container container) {
			for (Component child : container.getComponents()) found.addAll(descendants(child, type));
		}
		return found;
	}
}
