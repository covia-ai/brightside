package brightside.ui.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import brightside.ui.LAF;
import brightside.ui.components.Badge;
import brightside.ui.components.EmptyState;
import brightside.ui.components.TauMark;
import brightside.ui.components.Tone;

/**
 * Onboarding and the empty states: the τ greets on Welcome only (and as
 * Finish sets things up), at the size the brand sets; the name card shows the
 * identity just made; empty states can carry the τ in place of their icon.
 * Headless.
 */
class OnboardingPhaseTest {

	@Test
	void theMarkGreetsOnWelcomeOnly() throws Exception {
		LAF.init(LAF.TAU_DARK, null, null, null);
		OnboardingWizard[] w = new OnboardingWizard[1];
		SwingUtilities.invokeAndWait(() -> w[0] = new OnboardingWizard(setup -> {
		}));
		assertTrue(w[0].greeting(), "Welcome greets");
		assertTrue(OnboardingWizard.GREETING >= 160 && OnboardingWizard.GREETING <= 240);
		for (String step : new String[] { "PASSPHRASE", "IDENTITY", "RECOVERY", "CONFIRM", "NAME", "PROVIDER" }) {
			show(w[0], step);
			assertFalse(w[0].greeting(), step + " shows the small mark");
		}
		show(w[0], "WELCOME");
		assertTrue(w[0].greeting());
	}

	@Test
	void enterPressesThePrimaryButton() throws Exception {
		LAF.init(LAF.TAU_DARK, null, null, null);
		OnboardingWizard[] w = new OnboardingWizard[1];
		SwingUtilities.invokeAndWait(() -> w[0] = new OnboardingWizard(setup -> {
		}));
		Object key = w[0].getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.get(javax.swing.KeyStroke.getKeyStroke("ENTER"));
		assertNotNull(key);
		assertNotNull(w[0].getActionMap().get(key));
	}

	@Test
	void theNameCardShowsTheIdentityJustMade() throws Exception {
		LAF.init(LAF.TAU_LIGHT, null, null, null);
		OnboardingWizard[] w = new OnboardingWizard[1];
		SwingUtilities.invokeAndWait(() -> w[0] = new OnboardingWizard(setup -> {
		}));
		show(w[0], "RECOVERY"); // makes the phrase
		show(w[0], "NAME");
		String shown = w[0].identityShown();
		assertNotNull(shown);
		assertTrue(shown.matches("[0-9a-f]{4}…[0-9a-f]{3}"), shown);
		LAF.init(LAF.TAU_DARK, null, null, null);
	}

	@Test
	void anEmptyStateCanCarryTheMark() {
		LAF.init(LAF.TAU_DARK, null, null, null);
		EmptyState plain = new EmptyState("inbox", Tone.MUTED, "Nothing", null);
		assertNotNull(find(plain, Badge.class));
		EmptyState greeted = new EmptyState("inbox", Tone.MUTED, "Nothing", null).withMark(104);
		assertNull(find(greeted, Badge.class), "the τ replaces the icon");
		JLabel art = (JLabel) greeted.getComponent(0);
		assertTrue(art.getIcon() instanceof TauMark t && !t.isBrand(), "the everyday glyph, never the brand gradient");
	}

	@Test
	void theBrandGradientIsKeptForBrandMoments() {
		assertTrue(TauMark.brand(128).isBrand());
		assertFalse(TauMark.plain(104).isBrand());
		assertEquals(TauMark.plain(48).getIconWidth(), TauMark.plain(48).getIconHeight());
	}

	private static void show(OnboardingWizard w, String step) throws Exception {
		Class<?> type = Class.forName("brightside.ui.onboarding.OnboardingWizard$Step");
		@SuppressWarnings({ "unchecked", "rawtypes" })
		Object s = Enum.valueOf((Class) type, step);
		Method m = OnboardingWizard.class.getDeclaredMethod("show", type);
		m.setAccessible(true);
		SwingUtilities.invokeAndWait(() -> {
			try {
				m.invoke(w, s);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private static Component find(Container c, Class<?> type) {
		for (Component k : c.getComponents()) {
			if (type.isInstance(k)) return k;
			if (k instanceof Container kc) {
				Component f = find(kc, type);
				if (f != null) return f;
			}
		}
		return null;
	}
}
