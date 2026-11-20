package brightside.ui.peers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import brightside.peers.ExamplesSource;
import brightside.peers.MapSettingsStore;
import brightside.peers.PeerGraph;
import brightside.peers.SamplePeerSource;
import brightside.ui.LAF;

/**
 * The Peers map: the examples can be hidden, the gods are named, the header's
 * buttons carry words on a wide window, and the map's ink follows the theme.
 * Headless.
 */
class PeersPhaseTest {

	@Test
	void hiddenExamplesReportNothingAndTellFollowers() {
		ExamplesSource ex = new ExamplesSource(new SamplePeerSource(), true);
		assertTrue(ex.sample());
		assertFalse(ex.graph().nodes().isEmpty());
		AtomicInteger told = new AtomicInteger();
		ex.onChange(told::incrementAndGet);
		ex.setShown(false);
		assertEquals(PeerGraph.EMPTY, ex.graph());
		assertFalse(ex.sample());
		assertEquals(1, told.get());
		ex.setShown(false);
		assertEquals(1, told.get(), "no change, no word");
	}

	@Test
	void theCombinedSourceKeepsHoldOfTheExamples() {
		ExamplesSource ex = new ExamplesSource(new SamplePeerSource(), false);
		var joined = ExamplesSource.with(ex);
		assertEquals(ex, ExamplesSource.find(joined));
		assertNull(ExamplesSource.find(new SamplePeerSource()));
	}

	@Test
	void eachGodHasItsRole() {
		assertEquals("the administrator", PeersScreen.godRole("Odin"));
		assertEquals("the guardian", PeersScreen.godRole("Thor"));
		assertEquals("the treasurer", PeersScreen.godRole("Njord"));
		assertEquals("the healer", PeersScreen.godRole("Eir"));
		assertNull(PeersScreen.godRole("Robin"));
	}

	@Test
	void theMapsInkFollowsTheTheme() {
		LAF.init(LAF.TAU_DARK, null, null, null);
		Color dark = SolarMap.ink();
		LAF.init(LAF.TAU_LIGHT, null, null, null);
		Color light = SolarMap.ink();
		assertEquals(Color.WHITE, dark);
		assertNotEquals(dark, light);
		LAF.init(LAF.TAU_DARK, null, null, null);
	}

	@Test
	void theHeaderCarriesWordsOnAWideWindowOnly() throws Exception {
		LAF.init(LAF.TAU_DARK, null, null, null);
		PeersScreen[] s = new PeersScreen[1];
		SwingUtilities.invokeAndWait(() -> {
			s[0] = new PeersScreen(ExamplesSource.with(new ExamplesSource(new SamplePeerSource(), true)),
				MapSettingsStore::memory);
			s[0].setSize(1440, 900);
			s[0].doLayout();
			for (var l : s[0].getComponentListeners()) l.componentResized(null);
		});
		assertTrue(s[0].labelsShown());
		assertTrue(texts(s[0]).containsAll(List.of("Zoom out", "Zoom in", "Your system", "Whole map", "Reset")));
		assertTrue(checkbox(s[0], "Show examples"));
		SwingUtilities.invokeAndWait(() -> {
			s[0].setSize(900, 700);
			for (var l : s[0].getComponentListeners()) l.componentResized(null);
		});
		assertFalse(s[0].labelsShown());
		assertFalse(texts(s[0]).contains("Zoom out"));
	}

	private static List<String> texts(Container c) {
		List<String> out = new ArrayList<>();
		for (Component k : c.getComponents()) {
			if (k instanceof JButton b && b.getText() != null) out.add(b.getText());
			if (k instanceof Container kc) out.addAll(texts(kc));
		}
		return out;
	}

	private static boolean checkbox(Container c, String text) {
		for (Component k : c.getComponents()) {
			if (k instanceof AbstractButton b && !(k instanceof JButton) && text.equals(b.getText())) return true;
			if (k instanceof Container kc && checkbox(kc, text)) return true;
		}
		return false;
	}
}
