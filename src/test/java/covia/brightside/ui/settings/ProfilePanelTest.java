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

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import convex.core.crypto.IdenticonBuilder;
import convex.core.data.AccountKey;

final class ProfilePanelTest {

	/** The identities handed to the page are the ones it shows, as read-only technical values with identicons from the key. */
	@Test
	void presentsTheUserAndVenueIdentities() throws Exception {
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

				@Override
				public void actAs(boolean operator) {
				}
			});
			panel.refresh("Alice", "did:key:zAlice:u:alice", "did:key:zAlice", "ab".repeat(32), true, false);
			result.set(panel);
		});

		List<JTextArea> values = descendants(result.get(), JTextArea.class);
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

	/** The Act-as switch: a click reaches the host; a refresh only reflects state and never calls back. */
	@Test
	void actAsSwitchCallsTheHostOnlyWhenClicked() throws Exception {
		List<Boolean> asked = new ArrayList<>();
		AtomicReference<ProfilePanel> ref = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> ref.set(new ProfilePanel(new ProfilePanel.Host() {
			@Override
			public void saveName(String name) {
			}

			@Override
			public String revealPrimarySeed(char[] passphrase) {
				return null;
			}

			@Override
			public void actAs(boolean operator) {
				asked.add(operator);
			}
		})));
		ProfilePanel panel = ref.get();

		SwingUtilities.invokeAndWait(() -> {
			assertFalse(panel.asOperator.isEnabled(), "no venue yet: nothing to act as");
			panel.refresh("Mike", "did:key:z6Mk:u:mike", "did:key:z6Mk", null, false, false);
		});
		assertTrue(panel.asUser.isSelected());
		assertTrue(panel.asOperator.isEnabled());
		assertTrue(asked.isEmpty(), "a refresh never calls the host");

		SwingUtilities.invokeAndWait(panel.asOperator::doClick);
		assertEquals(List.of(true), asked);

		// The app rebinds and refreshes: the page shows the operator selected, still without a call.
		SwingUtilities.invokeAndWait(() -> panel.refresh("Mike", "did:key:z6Mk:u:mike", "did:key:z6Mk", null, false, true));
		assertTrue(panel.asOperator.isSelected());
		assertEquals(List.of(true), asked);

		SwingUtilities.invokeAndWait(panel.asUser::doClick);
		assertEquals(List.of(true, false), asked);

		SwingUtilities.invokeAndWait(panel::clearSensitive);
		assertTrue(panel.asUser.isSelected(), "logging out drops back to the user");
		assertFalse(panel.asOperator.isEnabled());
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
