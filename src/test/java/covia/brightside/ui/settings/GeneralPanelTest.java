package covia.brightside.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

final class GeneralPanelTest {

	@Test
	void containsTheFormerMenuActionsAndTrayPreferences() throws Exception {
		AtomicInteger newChats = new AtomicInteger();
		AtomicInteger refreshes = new AtomicInteger();
		AtomicReference<GeneralPanel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			GeneralPanel panel = new GeneralPanel(new GeneralPanel.Host() {
				@Override public void newChat() { newChats.incrementAndGet(); }
				@Override public void refreshConversations() { refreshes.incrementAndGet(); }
				@Override public void changeName() { }
				@Override public void logout() { }
				@Override public void setKeepInTray(boolean value) { }
				@Override public void setMinimiseToTray(boolean value) { }
				@Override public void hideToTray() { }
				@Override public void openDashboard() { }
				@Override public void openConfigFile() { }
				@Override public void openLogsFolder() { }
				@Override public void showAbout() { }
				@Override public void quit() { }
			});
			panel.refresh(false, false, true, true, true);
			result.set(panel);
		});

		List<JButton> buttons = descendants(result.get(), JButton.class);
		List<String> labels = buttons.stream().map(JButton::getText).toList();
		assertTrue(labels.containsAll(List.of("New chat", "Refresh", "Change name…", "Log out",
			"Hide to tray", "Open dashboard", "Open settings file", "Open logs folder", "About", "Quit")));
		assertEquals(SettingsScreen.Tab.GENERAL, SettingsScreen.Tab.values()[0]);
		assertFalse(button(buttons, "Hide to tray").isEnabled());
		assertTrue(button(buttons, "Open dashboard").isEnabled());

		List<String> checks = descendants(result.get(), JCheckBox.class).stream().map(JCheckBox::getText).toList();
		assertTrue(checks.contains("Keep running in the tray when I close the window"));
		assertTrue(checks.contains("Send Brightside to the tray when minimised"));

		SwingUtilities.invokeAndWait(() -> {
			button(buttons, "New chat").doClick();
			button(buttons, "Refresh").doClick();
		});
		assertEquals(1, newChats.get());
		assertEquals(1, refreshes.get());
	}

	private static JButton button(List<JButton> buttons, String text) {
		return buttons.stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
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
