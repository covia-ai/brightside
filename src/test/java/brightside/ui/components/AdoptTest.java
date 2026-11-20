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
}
