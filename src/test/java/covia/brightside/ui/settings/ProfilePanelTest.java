package covia.brightside.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.crypto.IdenticonBuilder;
import convex.core.data.AccountKey;

final class ProfilePanelTest {

	@Test
	void presentsTheUserAndVenueIdentitiesWithoutGridMarketing() throws Exception {
		AtomicReference<ProfilePanel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			ProfilePanel panel = new ProfilePanel(new ProfilePanel.Host() {
				@Override
				public void saveName(String name) {
				}

				@Override
				public String revealPrimarySeed(char[] passphrase) {
					return "00".repeat(32);
				}
			});
			panel.refresh("Alice", "did:key:zAlice:u:alice", "did:key:zAlice", "ab".repeat(32), true);
			result.set(panel);
		});

		List<JLabel> labels = descendants(result.get(), JLabel.class);
		List<String> labelText = labels.stream().map(JLabel::getText).toList();
		assertTrue(labelText.contains("Your name"));
		assertTrue(labelText.contains("Covia user DID"));
		assertTrue(labelText.contains("Home venue DID"));
		assertTrue(labelText.contains("Venue signing key"));
		assertTrue(labelText.contains("Primary seed (Advanced)"));

		List<JTextArea> values = descendants(result.get(), JTextArea.class);
		List<String> valueText = values.stream().map(JTextArea::getText).toList();
		assertTrue(valueText.contains("did:key:zAlice:u:alice"));
		assertTrue(valueText.contains("did:key:zAlice"));
		assertTrue(valueText.contains("ab".repeat(32)));
		assertFalse(String.join(" ", valueText).contains("Covia grid"));
		assertEquals("Identity", SettingsScreen.Tab.PROFILE.toString());

		for (String technical : List.of("did:key:zAlice:u:alice", "did:key:zAlice", "ab".repeat(32))) {
			JTextArea value = values.stream().filter(a -> technical.equals(a.getText())).findFirst().orElseThrow();
			assertEquals(Font.MONOSPACED, value.getFont().getName());
			assertFalse(value.isEditable());
		}

		List<ConvexIdenticon> identicons = descendants(result.get(), ConvexIdenticon.class);
		assertEquals(4, identicons.size());
		AccountKey key = AccountKey.fromHex("ab".repeat(32));
		for (ConvexIdenticon identicon : identicons) {
			assertEquals(key, identicon.publicKey());
			assertEquals(32, identicon.getPreferredSize().width);
			assertEquals(32, identicon.getPreferredSize().height);
			assertNotNull(identicon.pixels());
			assertEquals(IdenticonBuilder.SIZE * IdenticonBuilder.SIZE, identicon.pixels().length);
			assertTrue(java.util.Arrays.equals(IdenticonBuilder.build(key), identicon.pixels()));
		}
	}

	private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
		List<T> found = new ArrayList<>();
		if (type.isInstance(root)) found.add(type.cast(root));
		if (root instanceof Container container) {
			for (Component child : container.getComponents()) {
				found.addAll(descendants(child, type));
			}
		}
		return found;
	}
}
