package covia.brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import covia.brightside.model.AgentRef;

final class AgentListTest {

	@Test
	void newAgentButtonIsFixedBelowTheScrollableRows() throws Exception {
		AtomicInteger creates = new AtomicInteger();
		AtomicReference<AgentList> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			AgentList list = new AgentList(new AgentList.Listener() {
				@Override public void onSelectAgent(String agentId) { }
				@Override public void onNewAgent() { creates.incrementAndGet(); }
			});
			list.setAgents(List.of(new AgentRef("brightside", "Brightside")), "brightside");
			result.set(list);
		});

		AgentList list = result.get();
		JButton button = descendants(list, JButton.class).stream()
			.filter(b -> "New agent".equals(b.getText())).findFirst().orElseThrow();
		JScrollPane rows = descendants(list, JScrollPane.class).getFirst();
		Component bottom = ((BorderLayout) list.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
		assertTrue(isDescendant(bottom, button));
		assertFalse(isDescendant(rows, button), "button must not be an item in the scrolling list");

		SwingUtilities.invokeAndWait(button::doClick);
		assertEquals(1, creates.get());
	}

	private static boolean isDescendant(Component root, Component sought) {
		if (root == sought) return true;
		if (root instanceof Container container) {
			for (Component child : container.getComponents()) {
				if (isDescendant(child, sought)) return true;
			}
		}
		return false;
	}

	private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
		java.util.ArrayList<T> found = new java.util.ArrayList<>();
		if (type.isInstance(root)) found.add(type.cast(root));
		if (root instanceof Container container) {
			for (Component child : container.getComponents()) found.addAll(descendants(child, type));
		}
		return found;
	}
}
