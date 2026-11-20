package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.CardLayout;

import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

/**
 * A page moved out of a card deck, where it was a hidden card, shows in its
 * new place: the sidebar's Jobs screen holds Settings' Jobs page this way.
 * Headless.
 */
class AdoptTest {

	@Test
	void aHiddenCardShowsWhereItMoves() {
		JPanel deck = new JPanel(new CardLayout());
		JPanel first = new JPanel(), page = new JPanel();
		deck.add(first, "first");
		deck.add(page, "page");
		assertFalse(page.isVisible(), "a card not on view is hidden by its deck");

		JPanel holder = new JPanel(new BorderLayout());
		Panels.adopt(holder, page, BorderLayout.CENTER);
		assertSame(holder, page.getParent());
		assertTrue(page.isVisible(), "and shown in its new place");
	}

	@Test
	void theVaultsPagesShowWhenChosen() {
		// The Vault takes Settings' Secrets, Access and Passphrase pages out of its deck, where
		// two of the three were hidden cards; its tabs show whichever is chosen.
		JPanel deck = new JPanel(new CardLayout());
		JPanel secrets = new JPanel(), access = new JPanel(), passphrase = new JPanel();
		deck.add(new JPanel(), "first");
		deck.add(secrets, "secrets");
		deck.add(access, "access");
		deck.add(passphrase, "passphrase");
		brightside.ui.vault.VaultScreen vault = new brightside.ui.vault.VaultScreen();
		vault.adopt(secrets, access, passphrase);
		for (brightside.ui.vault.VaultScreen.Page p : brightside.ui.vault.VaultScreen.Page.values()) {
			vault.select(p);
			JPanel shown = switch (p) {
				case SECRETS -> secrets;
				case ACCESS -> access;
				case PASSPHRASE -> passphrase;
			};
			assertTrue(shown.isVisible(), p + " shows when chosen");
		}
	}

	@Test
	void aPageGoingBackToItsDeckShowsWhenItsCardIs() {
		// The classic layout puts the pages back in Settings: the deck hides and shows them as cards.
		JPanel holder = new JPanel(new BorderLayout());
		JPanel page = new JPanel();
		Panels.adopt(holder, page, BorderLayout.CENTER);
		JPanel deck = new JPanel(new CardLayout());
		deck.add(new JPanel(), "first");
		deck.add(page, "page");
		((CardLayout) deck.getLayout()).show(deck, "page");
		assertTrue(page.isVisible(), "its card on view shows it");
	}
}
