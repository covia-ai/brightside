package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import brightside.ui.LAF;

/**
 * Tau Dark and Tau Light install their own colour roles over FlatLaf's and
 * Brightside's shared defaults, every text role reads at WCAG AA on every
 * ground it sits on, and the pair rules hold: only navy text on amber, never
 * white on Cerulean, Refused never red. Isolated: it swaps the JVM's look and
 * feel.
 */
@Isolated
class BrandTokensTest {

	private static final Color NAVY = Color.decode("#1E2642");
	private static final Color AMBER = Color.decode("#F2AE30");
	private static final Color CERULEAN = Color.decode("#4A90E2");
	private static final Color PURPLE = Color.decode("#6B46C1");

	@Test
	void tauDarkIsTheDefaultAndHasTheNavyField() {
		assertEquals(LAF.TAU_DARK, LAF.defaultTheme(LAF.DARK));
		assertEquals(LAF.TAU_LIGHT, LAF.defaultTheme(LAF.LIGHT));
		LAF.init(null, LAF.DARK, null, null);
		assertEquals(LAF.TAU_DARK, LAF.current().id());
		assertRgb("#1E2642", Theme.panel());
		assertRgb("#161C33", Theme.sidebar());
		assertRgb("#232B4A", Theme.chrome());
		assertRgb("#273052", Theme.card());
		assertRgb("#F8F9FB", Theme.foreground());
		assertRgb("#B79BF0", Theme.link());
		assertRgb("#B79BF0", Theme.accentText());
		assertRgb("#F2AE30", Theme.localNode());
		assertRgb("#F2AE30", Theme.countPill());
		assertRgb("#6B46C1", Theme.accent());
		assertRgb("#6B46C1", Theme.primaryFill());
	}

	@Test
	void tauLightIsTheGoldGroundSystem() {
		LAF.init(null, LAF.LIGHT, null, null);
		assertEquals(LAF.TAU_LIGHT, LAF.current().id());
		assertRgb("#FFFFFF", Theme.panel());
		assertRgb("#FBEBC9", Theme.sidebar());
		assertRgb("#F8D99A", Theme.chrome());
		assertRgb("#FDF6E7", Theme.warmSurface());
		assertRgb("#FFFFFF", Theme.card());
		assertRgb("#1E2642", Theme.foreground());
		assertRgb("#6B46C1", Theme.link());
		assertRgb("#6B46C1", Theme.localNode());
		assertRgb("#1E2642", Theme.countPill());
		assertRgb("#F2AE30", Theme.countPillText());
		assertRgb("#6B46C1", Theme.primaryFill());
	}

	@Test
	void everyTextRoleReadsOnEveryGroundInBothTauThemes() {
		for (String id : List.of(LAF.TAU_DARK, LAF.TAU_LIGHT)) {
			LAF.init(id, null, null, null);
			Map<String, Color> grounds = new LinkedHashMap<>();
			grounds.put("panel", Theme.panel());
			grounds.put("sidebar", Theme.sidebar());
			grounds.put("chrome", Theme.chrome());
			grounds.put("warm surface", Theme.warmSurface());
			grounds.put("card", Theme.card());
			grounds.put("sidebar active item", Theme.sidebarSelected());
			for (Map.Entry<String, Color> g : grounds.entrySet()) {
				assertReads(id, "text on " + g.getKey(), Theme.foreground(), g.getValue());
				assertReads(id, "muted text on " + g.getKey(), Theme.muted(), g.getValue());
			}
			// Links sit on content, cards and the warm surface; never on amber.
			for (String where : List.of("panel", "card", "warm surface")) {
				assertReads(id, "link on " + where, Theme.link(), grounds.get(where));
			}
			assertReads(id, "white on the primary fill", Color.WHITE, Theme.primaryFill());
			assertReads(id, "accent text on the panel", Theme.accentText(), Theme.panel());
			assertReads(id, "accent text on its tint", Theme.accentText(), Theme.blend(Theme.panel(), Theme.accent(), 0.22f));
			assertReads(id, "count pill", Theme.countPillText(), Theme.countPill());
			for (Tone t : List.of(Tone.RUNNING, Tone.SIGNED, Tone.WAITING, Tone.REFUSED)) {
				assertReads(id, t + " label", t.text(), t.tint());
			}
			// The sidebar's active bar and the focus ring are non-text: 3:1 on their grounds.
			assertTrue(Theme.contrast(Theme.sidebarBar(), Theme.sidebarSelected()) >= 3.0, id + " sidebar bar");
			assertTrue(Theme.contrast(UIManager.getColor("Component.focusColor"), Theme.panel()) >= 3.0, id + " focus");
		}
	}

	@Test
	void pairRulesHold() {
		for (String id : List.of(LAF.TAU_DARK, LAF.TAU_LIGHT)) {
			LAF.init(id, null, null, null);
			// Only navy text on amber.
			assertRgb("#F2AE30", Tone.WAITING.tint());
			assertRgb("#1E2642", Tone.WAITING.text());
			if (Theme.countPill().getRGB() == AMBER.getRGB()) assertRgb("#1E2642", Theme.countPillText());
			// Never white on Cerulean: the primary fill is purple, never the blue.
			assertNotEquals(CERULEAN.getRGB(), Theme.primaryFill().getRGB(), id);
			// Royal Purple is never text on navy.
			if (Theme.panel().getRGB() == NAVY.getRGB()) assertNotEquals(PURPLE.getRGB(), Theme.link().getRGB(), id);
			// Refused is calm: outlined, never in the error colour.
			assertNotNull(Tone.REFUSED.border(), id + " refused outline");
			assertNotEquals(Theme.error().getRGB(), Tone.REFUSED.text().getRGB(), id);
			assertNotEquals(Theme.error().getRGB(), Tone.REFUSED.tint().getRGB(), id);
			assertNull(Tone.WAITING.border(), id);
		}
	}

	@Test
	void everyAccentOnOfferStillGivesAReadablePrimary() {
		for (String hex : List.of("#6B46C1", "#3B82F6", "#14B8A6", "#3FB950", "#F59E0B", "#EC4899", "#FFFF00")) {
			for (String id : List.of(LAF.TAU_DARK, LAF.TAU_LIGHT)) {
				LAF.init(id, null, hex, null);
				assertReads(id + " " + hex, "white on the primary fill", Color.WHITE, Theme.primaryFill());
			}
		}
	}

	@Test
	void otherThemesKeepTheirOwnGroundsButGetEveryRole() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, null, null);
		assertNotEquals(NAVY.getRGB(), Theme.panel().getRGB(), "FlatLaf Dark keeps its own field");
		List<Supplier<Color>> roles = List.of(Theme::sidebar, Theme::chrome, Theme::card, Theme::warmSurface,
			Theme::link, Theme::linkUnderline, Theme::countPill, Theme::countPillText, Theme::localNode, Theme::yours);
		for (Supplier<Color> r : roles) assertNotNull(r.get());
		for (Tone t : Tone.values()) {
			assertNotNull(t.tint(), t.name());
			assertNotNull(t.text(), t.name());
		}
	}

	@Test
	void aRefusalMustNameItsCapability() {
		LAF.init(LAF.TAU_DARK, null, null, null);
		assertEquals("Refused: billing.write", StatusBadge.refused("billing.write").getText());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> StatusBadge.refused(" "));
		assertEquals("Waiting on you", StatusBadge.waiting().getText());
	}

	private static void assertReads(String theme, String what, Color text, Color ground) {
		double c = Theme.contrast(text, ground);
		assertTrue(c >= Theme.AA_CONTRAST, () -> theme + ": " + what + " reads at only " + String.format("%.2f", c) + ":1");
	}

	private static void assertRgb(String hex, Color c) {
		assertEquals(Color.decode(hex).getRGB() & 0xFFFFFF, c.getRGB() & 0xFFFFFF,
			() -> "expected " + hex + " but was " + String.format("#%06X", c.getRGB() & 0xFFFFFF));
	}
}
