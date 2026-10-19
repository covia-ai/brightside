package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

import brightside.ui.LAF;

/**
 * The tones' grounds come from Brightside's UI defaults through a real theme
 * install, a step stronger on a dark theme than a light one — not from the
 * fallback {@link Tone#tint} works out when a key is missing.
 */
class ToneTest {

	@Test
	void tintsAreTheThemesAndFollowItsMode() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, null, null);
		assertTints(0.18f);
		LAF.init(LAF.FLAT_LIGHT, LAF.LIGHT, null, null);
		assertTints(0.14f);
	}

	@Test
	void anAccentTintIsTheAccentItself() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, "#3B82F6", null);
		Color tint = Tone.ACCENT.tint();
		assertEquals(Color.decode("#3B82F6").getRGB() & 0xFFFFFF, tint.getRGB() & 0xFFFFFF);
	}

	private static void assertTints(float alpha) {
		for (Tone tone : Tone.values()) {
			String key = "Brightside." + tone.name().toLowerCase() + ".tint";
			assertNotNull(UIManager.getColor(key), key);
			assertEquals(Math.round(alpha * 255), tone.tint().getAlpha(), 1, tone.name());
		}
	}
}
