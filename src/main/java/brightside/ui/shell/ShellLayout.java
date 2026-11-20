package brightside.ui.shell;

/**
 * The shell's layout rules, as arithmetic, so they can be tested at every
 * width without a window: room before panes.
 * <ul>
 * <li>At most three docked panes at once: a list, the main column, and the
 * side panel (the inspector or the Inbox panel). The rail is chrome, not a
 * pane.</li>
 * <li>The main column (the chat, the table, the map, or the page) never goes
 * below {@link #MAIN_FLOOR} while anything is docked beside it: a pane that
 * would take it lower opens as an overlay instead.</li>
 * <li>The rail collapses to icons by itself before any content column is
 * squeezed, and the person can collapse it by choice.</li>
 * <li>The side panel docks only from {@link #DOCK_FROM}; below that it is an
 * overlay.</li>
 * </ul>
 * Nothing is removed: a pane that does not dock is still reachable, as an
 * overlay. Widths are window points, the same units as the window's size.
 */
public final class ShellLayout {

	/** The rail with labels. */
	public static final int RAIL = 184;
	/** The rail as icons only. */
	public static final int RAIL_ICONS = 56;
	/** A list pane beside the main column (the Chats list, the agent list). */
	public static final int LIST = 260;
	/** The side panel: the inspector or the Inbox panel. */
	public static final int SIDE = 320;
	/** The main column's floor while anything is docked beside it. */
	public static final int MAIN_FLOOR = 560;
	/** The side panel docks only from this window width. */
	public static final int DOCK_FROM = 1440;
	/** The most panes docked at once, the main column included. */
	public static final int MAX_DOCKED = 3;

	/** How a pane is shown at a width. */
	public enum Mode {
		/** Beside the main column. */
		DOCKED,
		/** Over the main column, from its toggle; closes with Esc. */
		OVERLAY,
		/** Not asked for on this screen. */
		NONE
	}

	/**
	 * What the shell does at one width.
	 *
	 * @param railWidth the rail's width, {@link #RAIL} or {@link #RAIL_ICONS}
	 * @param list      how the list pane is shown
	 * @param side      how the side panel is shown
	 * @param main      the main column's width
	 */
	public record Plan(int railWidth, Mode list, Mode side, int main, int listWidth) {

		public boolean railCollapsed() {
			return railWidth == RAIL_ICONS;
		}

		/** Docked panes, the main column included. */
		public int docked() {
			return 1 + (list == Mode.DOCKED ? 1 : 0) + (side == Mode.DOCKED ? 1 : 0);
		}
	}

	private ShellLayout() {
	}

	/**
	 * The plan for a window {@code width} wide.
	 *
	 * @param wantsList      the screen has a list pane and the person has it on
	 * @param wantsSide      the screen has a side panel to show (an inspector, or a request waiting)
	 * @param railCollapsed  the person collapsed the rail
	 */
	public static Plan plan(int width, boolean wantsList, boolean wantsSide, boolean railCollapsed) {
		return plan(width, LIST, wantsList, wantsSide, railCollapsed);
	}

	/** As {@link #plan(int, boolean, boolean, boolean)}, for a list pane {@code listWidth} wide. */
	public static Plan plan(int width, int listWidth, boolean wantsList, boolean wantsSide, boolean railCollapsed) {
		// The rail: labels unless the person chose icons, or the content would be squeezed.
		int needed = MAIN_FLOOR + (wantsList ? listWidth : 0);
		int rail = (railCollapsed || width - RAIL < needed) ? RAIL_ICONS : RAIL;
		int room = width - rail;

		Mode list = Mode.NONE;
		if (wantsList) {
			if (room - listWidth >= MAIN_FLOOR) {
				list = Mode.DOCKED;
				room -= listWidth;
			} else {
				list = Mode.OVERLAY;
			}
		}
		Mode side = Mode.NONE;
		if (wantsSide) {
			if (width >= DOCK_FROM && room - SIDE >= MAIN_FLOOR) {
				side = Mode.DOCKED;
				room -= SIDE;
			} else {
				side = Mode.OVERLAY;
			}
		}
		return new Plan(rail, list, side, room, listWidth);
	}
}
