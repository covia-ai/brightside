package brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import brightside.model.AgentTemplate;

/** The instructions follow the name and template until the owner edits them, and are what the agent is given. */
class NewAgentPanelTest {

	@Test
	void instructionsFollowNameAndTemplateUntilEdited() {
		NewAgentPanel panel = new NewAgentPanel(null, Map.of());
		AgentTemplate first = AgentTemplate.values()[0];
		AgentTemplate last = AgentTemplate.values()[AgentTemplate.values().length - 1];
		panel.nameField().setText("Scout");
		panel.templateField().setSelectedItem(last);
		assertEquals(last.systemPrompt("Scout"), panel.instructionsField().getText());

		panel.instructionsField().setText("You scout the shared task list.");
		panel.templateField().setSelectedItem(first);
		panel.nameField().setText("Scout two");
		assertEquals("You scout the shared task list.", panel.instructionsField().getText());
	}

	@Test
	void blankInstructionsFallBackToTheTemplate() {
		AgentTemplate t = AgentTemplate.values()[0];
		assertEquals(t.systemPrompt("Editor"), new NewAgentPanel.Options("Editor", t, "op", " ").systemPrompt());
		assertEquals("Edit.", new NewAgentPanel.Options("Editor", t, "op", "Edit.").systemPrompt());
	}
}
