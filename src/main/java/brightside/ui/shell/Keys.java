package brightside.ui.shell;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.KeyStroke;

/** Shortcut keys: ⌘ on macOS, Ctrl elsewhere, and how a shortcut is written. */
public final class Keys {

	private Keys() {
	}

	public static boolean mac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	/** The menu shortcut modifier; works headless too, where the toolkit cannot be asked. */
	public static int menu() {
		if (!GraphicsEnvironment.isHeadless()) {
			try {
				return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
			} catch (RuntimeException e) {
				// fall through
			}
		}
		return mac() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
	}

	/** A shortcut as the platform writes it: ⌘K on macOS, Ctrl+K elsewhere. */
	public static String text(KeyStroke k) {
		if (k == null) return "";
		String mods = KeyEvent.getModifiersExText(k.getModifiers());
		String key = KeyEvent.getKeyText(k.getKeyCode());
		if (!mac()) return mods + "+" + key;
		String t = mods.replace("+", "") + key;
		// Write the symbols out where the UI font has no glyph for them.
		java.awt.Font font = javax.swing.UIManager.getFont("defaultFont");
		if (font == null) font = javax.swing.UIManager.getFont("Label.font");
		if (font != null && font.canDisplayUpTo(t) >= 0) {
			t = t.replace("⌘", "Cmd+").replace("⌃", "Ctrl+").replace("⌥", "Opt+").replace("⇧", "Shift+");
		}
		return t;
	}
}
