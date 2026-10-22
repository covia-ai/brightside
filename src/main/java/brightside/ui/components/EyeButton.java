package brightside.ui.components;

import java.awt.Cursor;

import javax.swing.Icon;
import javax.swing.JButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The eye beside something that can be hidden — a secret, the primary seed, a
 * preview pane. Everywhere in the app the eye shows the current state: open
 * while the thing shows, struck through while it is hidden. The tooltip says
 * what a press will do. The owner decides what a press does; this button only
 * shows {@link #setShown}.
 */
@SuppressWarnings("serial")
public final class EyeButton extends JButton {

	private final String show;
	private final String hide;
	private final Icon open;
	private final Icon shut;
	private boolean shown;

	/** {@code show} and {@code hide}: the tooltips naming what a press does, while hidden and while shown. */
	public EyeButton(String show, String hide, int size) {
		this.show = show;
		this.hide = hide;
		open = Lucide.icon("eye", size, Theme::muted);
		shut = Lucide.icon("eye-off", size, Theme::muted);
		putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setShown(false);
	}

	public boolean isShown() {
		return shown;
	}

	public void setShown(boolean shown) {
		this.shown = shown;
		setIcon(shown ? open : shut);
		String tip = shown ? hide : show;
		setToolTipText(tip);
		getAccessibleContext().setAccessibleName(tip);
	}
}
