package brightside.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.Test;

import brightside.ui.LAF;
import brightside.ui.settings.SettingsScreen.Tab;

/**
 * Settings: the sections in their order, search by name or by what is on a
 * page, the plan's free and paid states, and Match the system resolving to a
 * mode. Headless.
 */
class SettingsPhaseTest {

	@Test
	void sectionsComeInTheirOrder() {
		assertEquals(List.of(Tab.PROFILE, Tab.MODEL, Tab.PEERS, Tab.TREASURY, Tab.GENERAL, Tab.THEME,
			Tab.INTEGRATIONS, Tab.SCHEDULE, Tab.JOBS, Tab.SECRETS, Tab.VAULT, Tab.ACCESS), Arrays.asList(Tab.values()));
	}

	@Test
	void searchFindsASectionByWhatIsOnIt() {
		assertTrue(SettingsScreen.matches(Tab.GENERAL, "lock"));
		assertTrue(SettingsScreen.matches(Tab.VAULT, "passphrase"));
		assertTrue(SettingsScreen.matches(Tab.ACCESS, "Token"));
		assertTrue(SettingsScreen.matches(Tab.TREASURY, "plan"));
		assertTrue(SettingsScreen.matches(Tab.MODEL, "Models"));
		assertFalse(SettingsScreen.matches(Tab.THEME, "discord"));
		for (Tab t : Tab.values()) assertTrue(SettingsScreen.matches(t, ""), "an empty search shows " + t);
	}

	@Test
	void thePlanSaysWhatEachGivesWithoutInventingUsage() {
		TreasuryPanel p = new TreasuryPanel();
		p.setPlan(TreasuryPanel.Plan.FREE);
		String free = text(p);
		assertTrue(free.contains("Free plan"));
		assertTrue(free.contains("your own model keys"));
		p.setPlan(TreasuryPanel.Plan.PAID);
		String paid = text(p);
		assertTrue(paid.contains("Paid plan"));
		assertTrue(paid.contains("included through Covia"));
		assertFalse(paid.matches(".*\\d+ (tokens|calls|requests).*"), "no usage figures before the plan service exists");
		assertEquals(TreasuryPanel.Plan.FREE, TreasuryPanel.Plan.stub(), "free unless asked for");
	}

	@Test
	void matchTheSystemResolvesToAMode() {
		String m = LAF.effective(LAF.SYSTEM);
		assertTrue(m.equals(LAF.DARK) || m.equals(LAF.LIGHT));
		assertEquals(LAF.LIGHT, LAF.effective(LAF.LIGHT));
	}

	private static String text(Component c) {
		List<String> out = new ArrayList<>();
		collect(c, out);
		return String.join(" | ", out);
	}

	private static void collect(Component c, List<String> out) {
		if (c instanceof JLabel l && l.getText() != null) out.add(l.getText());
		if (c instanceof JTextComponent t) out.add(t.getText());
		if (c instanceof Container k) for (Component ch : k.getComponents()) collect(ch, out);
	}

	@Test
	void eachPlanHasItsWayOnward() {
		TreasuryPanel t = new TreasuryPanel();
		t.setPlan(TreasuryPanel.Plan.FREE);
		assertEquals("See plans", t.planAction());
		t.setPlan(TreasuryPanel.Plan.PAID);
		assertEquals("Manage plan", t.planAction());
	}
}
