package brightside.ui.components;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;

import javax.swing.ButtonModel;
import javax.swing.DefaultButtonModel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The app's one button-like control for navigation and item lists: the bottom
 * tabs, the Settings sections, the agent and conversation rows. FlatLaf's
 * tool-bar style supplies the hover and pressed backgrounds and the selected
 * tint; the control adds what those surfaces need and plain buttons lack.
 *
 * <ul>
 *   <li><b>It acts on the press.</b> Swing reports a click only when press and
 *       release land on the same pixel, so a tap that drifts is lost; here the
 *       {@link #onPress} action runs the moment the button model becomes armed
 *       and pressed — once per press, by mouse or programmatically.</li>
 *   <li><b>Its selected state belongs to its host.</b> A click never toggles it:
 *       the host marks the current tab or row with {@link #setSelected}, and the
 *       theme paints it. (A plain {@link DefaultButtonModel} instead of the
 *       toggle model, which would flip on every release.)</li>
 *   <li><b>It never takes keyboard focus</b>, so the chat composer keeps it, and
 *       shows a hand cursor.</li>
 *   <li><b>A right-click can open a menu</b> ({@link #onPopup}), on whichever of
 *       press and release the platform treats as the popup trigger.</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class PressButton extends JToggleButton {

	private Runnable onPress;
	private Supplier<JPopupMenu> onPopup;
	private boolean down;

	public PressButton(String text) {
		super(text);
		setModel(new DefaultButtonModel());
		putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
		setFocusable(false);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		getModel().addChangeListener(e -> {
			ButtonModel m = getModel();
			boolean now = m.isArmed() && m.isPressed();
			if (now && !down && onPress != null) onPress.run();
			down = now;
		});
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				popup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				popup(e);
			}
		});
	}

	/** What a press does. */
	/** Wears the list-row selection: the warm surface with a 3 px bar on the left, as the sidebar marks its item. */
	private boolean listRow;

	/**
	 * The selected look of a row in a list beside the content (the Chats
	 * column): the warm surface, since the column's ground is the content's
	 * own and a white selection would not show on it, plus the sidebar's 3 px
	 * bar, so selection reads the same everywhere.
	 */
	public PressButton listRow() {
		listRow = true;
		// Added to any style already set, so a font or margin set earlier is kept.
		Object was = getClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE);
		String row = "toolbar.selectedBackground: $Brightside.warmSurface";
		Styles.style(this, (was instanceof String w && !w.isBlank()) ? w + "; " + row : row);
		return this;
	}

	@Override
	protected void paintComponent(java.awt.Graphics g) {
		super.paintComponent(g);
		if (!listRow || !isSelected()) return;
		java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
		try {
			com.formdev.flatlaf.ui.FlatUIUtils.setRenderingHints(g2);
			int w = com.formdev.flatlaf.util.UIScale.scale(3), inset = com.formdev.flatlaf.util.UIScale.scale(6);
			g2.setColor(Theme.sidebarBar());
			g2.fillRoundRect(0, inset, w, getHeight() - 2 * inset, w, w);
		} finally {
			g2.dispose();
		}
	}

	public PressButton onPress(Runnable action) {
		this.onPress = action;
		return this;
	}

	/** The menu a right-click opens, built on demand; null for none. */
	public PressButton onPopup(Supplier<JPopupMenu> menu) {
		this.onPopup = menu;
		return this;
	}

	private void popup(MouseEvent e) {
		if (onPopup == null || !e.isPopupTrigger()) return;
		JPopupMenu menu = onPopup.get();
		if (menu != null) menu.show(this, e.getX(), e.getY());
	}
}
