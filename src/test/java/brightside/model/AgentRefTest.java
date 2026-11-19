package brightside.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import brightside.BrightSide;
import convex.core.data.Maps;

class AgentRefTest {

	@Test
	void standardAssistantGoesByTheAppNameUntilNamed() {
		assertEquals(BrightSide.APP_NAME, AgentRef.displayName(null, "Brightside", "Brightside"),
			"an implicit Brightside id is shown, and now introduces itself, as the app");
		assertEquals("Jarvis", AgentRef.displayName(Maps.of("config", Maps.of("name", "Jarvis")),
			"Brightside", "Brightside"));
	}

	@Test
	void otherAgentsGoByTheirId() {
		assertEquals("Helper", AgentRef.displayName(null, "Helper", "Brightside"));
		assertEquals("Helper", AgentRef.displayName(null, "Helper", null));
	}
}
