package brightside.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import brightside.ui.shell.ShellLayout.Mode;
import brightside.ui.shell.ShellLayout.Plan;

/**
 * The layout rules hold at every width from the minimum window to a wide
 * screen, for every combination of panes: never more than three docked, the
 * main column never below the floor while anything is docked, the rail
 * collapses before a column is squeezed, the side panel docks only from
 * 1440, and every pane asked for is shown, docked or as an overlay.
 */
class ShellLayoutTest {

	@Test
	void rulesHoldAtEveryWidth() {
		for (int w = 720; w <= 2560; w += 8) {
			for (boolean list : new boolean[] { false, true }) {
				for (boolean side : new boolean[] { false, true }) {
					for (boolean collapsed : new boolean[] { false, true }) {
						Plan p = ShellLayout.plan(w, list, side, collapsed);
						String at = w + " list=" + list + " side=" + side + " collapsed=" + collapsed + ": " + p;
						assertTrue(p.docked() <= ShellLayout.MAX_DOCKED, at);
						if (p.docked() > 1) assertTrue(p.main() >= ShellLayout.MAIN_FLOOR, at);
						assertEquals(w - p.railWidth() - (p.list() == Mode.DOCKED ? p.listWidth() : 0)
							- (p.side() == Mode.DOCKED ? ShellLayout.SIDE : 0), p.main(), at);
						// Nothing asked for goes missing.
						assertEquals(list, p.list() != Mode.NONE, at);
						assertEquals(side, p.side() != Mode.NONE, at);
						if (p.side() == Mode.DOCKED) assertTrue(w >= ShellLayout.DOCK_FROM, at);
						if (collapsed) assertTrue(p.railCollapsed(), at);
					}
				}
			}
		}
	}

	@Test
	void rulesHoldForAWiderList() {
		for (int w = 720; w <= 2560; w += 8) {
			Plan p = ShellLayout.plan(w, 400, true, false, false);
			if (p.docked() > 1) assertTrue(p.main() >= ShellLayout.MAIN_FLOOR, w + ": " + p);
		}
		// Today's 400 px agents and Chats list at today's 1040 window: the rail gives way, the list docks.
		Plan p = ShellLayout.plan(1040, 400, true, false, false);
		assertTrue(p.railCollapsed());
		assertEquals(Mode.DOCKED, p.list());
		assertEquals(584, p.main());
	}

	@Test
	void theRailCollapsesBeforeAColumnIsSqueezed() {
		// With a list: labels need 184 + 260 + 560 = 1004.
		assertTrue(ShellLayout.plan(1003, true, false, false).railCollapsed());
		assertEquals(ShellLayout.RAIL, ShellLayout.plan(1004, true, false, false).railWidth());
		// Without one, the rail keeps its labels down to 744.
		assertEquals(ShellLayout.RAIL, ShellLayout.plan(744, false, false, false).railWidth());
		assertTrue(ShellLayout.plan(743, false, false, false).railCollapsed());
	}

	@Test
	void theNewDefaultWindowLandsInTheMediumLayoutWithRoom() {
		Plan p = ShellLayout.plan(1200, true, true, false);
		assertEquals(ShellLayout.RAIL, p.railWidth());
		assertEquals(Mode.DOCKED, p.list());
		assertEquals(Mode.OVERLAY, p.side());
		assertEquals(756, p.main());
	}

	@Test
	void theSidePanelDocksOnlyFrom1440() {
		assertEquals(Mode.OVERLAY, ShellLayout.plan(1439, false, true, false).side());
		assertEquals(Mode.DOCKED, ShellLayout.plan(1440, false, true, false).side());
		// At 1440 with a list too: 184 + 260 + 320 = 764 leaves 676 for the main column.
		Plan p = ShellLayout.plan(1440, true, true, false);
		assertEquals(3, p.docked());
		assertEquals(676, p.main());
	}

	@Test
	void atTheMinimumWindowEverythingIsStillReachable() {
		Plan p = ShellLayout.plan(720, true, true, false);
		assertTrue(p.railCollapsed());
		assertNotEquals(Mode.NONE, p.list());
		assertNotEquals(Mode.NONE, p.side());
		assertEquals(Mode.OVERLAY, p.list()); // 720 - 56 - 260 = 404, below the floor
	}
}
