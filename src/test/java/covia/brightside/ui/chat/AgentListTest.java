package covia.brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
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
			AgentList list = new AgentList(listener(creates, new ArrayList<>()));
			list.setAgents(List.of(new AgentRef("Brightside", "Brightside")), "Brightside", "Brightside");
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

	@Test
	void rightClickMenuOffersInfoAndNeverDeletesTheStandardAgent() throws Exception {
		List<String> infos = new ArrayList<>();
		AtomicReference<JPopupMenu> standard = new AtomicReference<>();
		AtomicReference<JPopupMenu> other = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			AgentList list = new AgentList(listener(new AtomicInteger(), infos));
			AgentRef brightside = new AgentRef("Brightside", "Brightside");
			AgentRef bob = new AgentRef("Bob", "Bob");
			list.setAgents(List.of(brightside, bob), "Brightside", "Brightside");
			standard.set(list.menuFor(brightside));
			other.set(list.menuFor(bob));
		});

		assertFalse(item(standard.get(), "Open").isEnabled(), "the current agent is already open");
		assertFalse(item(standard.get(), "Delete…").isEnabled(), "the standard agent can't be deleted");
		assertTrue(item(standard.get(), "Agent info…").isEnabled());
		assertTrue(item(other.get(), "Open").isEnabled());
		assertTrue(item(other.get(), "Delete…").isEnabled());

		SwingUtilities.invokeAndWait(() -> item(other.get(), "Agent info…").doClick());
		assertEquals(List.of("Bob"), infos);
	}

	private static AgentList.Listener listener(AtomicInteger creates, List<String> infos) {
		return new AgentList.Listener() {
			@Override public void onSelectAgent(String agentId) { }
			@Override public void onNewAgent() { creates.incrementAndGet(); }
			@Override public void onAgentInfo(String agentId) { infos.add(agentId); }
			@Override public void onDeleteAgent(String agentId) { }
		};
	}

	private static JMenuItem item(JPopupMenu menu, String text) {
		for (Component c : menu.getComponents()) {
			if (c instanceof JMenuItem mi && text.equals(mi.getText())) return mi;
		}
		throw new AssertionError("no menu item " + text);
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
