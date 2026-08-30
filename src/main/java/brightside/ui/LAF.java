package brightside.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.io.InputStream;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

/**
 * Look and feel: FlatLaf's modern macOS-style themes with a Brightside-purple
 * accent, generous corner rounding and comfortable spacing. Call once, before
 * any Swing component exists.
 */
public final class LAF {

	private static final Logger log = LoggerFactory.getLogger(LAF.class);

	/** Brightside accent — a vivid modern purple used for the send button, focus and selection. */
	public static final Color ACCENT = new Color(0x7C, 0x6C, 0xF5);

	/** Comfortable, slightly-larger base UI size (FlatLaf's own default is ~13). */
	private static final int BASE_FONT_SIZE = 15;

	/** The UI font: Lato, bundled so it's identical on every platform. */
	private static final String FONT_FAMILY = "Lato";
	private static final String[] FONT_RESOURCES = {
		"/fonts/lato/Lato-Regular.ttf",
		"/fonts/lato/Lato-Bold.ttf",
		"/fonts/lato/Lato-Light.ttf",
	};

	private LAF() {
	}

	/**
	 * Returns a logical monospaced font at the same size and style as a UI font.
	 * The JVM maps the logical family on every supported desktop, so opaque values
	 * such as keys, DIDs and tokens remain legible without another bundled font.
	 */
	public static Font monospaced(Font base) {
		if (base == null) return new Font(Font.MONOSPACED, Font.PLAIN, BASE_FONT_SIZE);
		return new Font(Font.MONOSPACED, base.getStyle(), Math.round(base.getSize2D()))
			.deriveFont(base.getSize2D());
	}

	/** Installs the theme ({@code "dark"} unless {@code "light"}). */
	public static void init(String theme) {
		// Bundled Lato — register the faces, then make it the preferred family
		// before the theme installs so components pick it up.
		registerFonts();
		FlatLaf.setPreferredFontFamily(FONT_FAMILY);

		boolean light = "light".equalsIgnoreCase(theme);
		boolean ok = light ? FlatMacLightLaf.setup() : FlatMacDarkLaf.setup();
		if (!ok) log.warn("FlatLaf could not be installed; using the default look and feel");

		// Bigger, comfortable default size in Lato (components derive from this).
		UIManager.put("defaultFont", new Font(FONT_FAMILY, Font.PLAIN, BASE_FONT_SIZE));

		// Modern accent everywhere accent colours are read (buttons, focus, selection).
		UIManager.put("Component.accentColor", ACCENT);
		UIManager.put("Component.focusColor", ACCENT);
		UIManager.put("Component.focusWidth", 1);
		UIManager.put("Component.innerFocusWidth", 1);

		// Rounded, 2020s geometry.
		UIManager.put("Component.arc", 14);
		UIManager.put("Button.arc", 18);
		UIManager.put("TextComponent.arc", 14);
		UIManager.put("CheckBox.arc", 8);

		// Slim, rounded, unobtrusive scrollbars.
		UIManager.put("ScrollBar.width", 12);
		UIManager.put("ScrollBar.thumbArc", 999);
		UIManager.put("ScrollBar.trackArc", 999);
		UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
		UIManager.put("ScrollBar.track", new Color(0, 0, 0, 0));
		UIManager.put("ScrollPane.arc", 12);

		// Navigation and list rows are tool-bar-style buttons (PressButton): the
		// theme paints their hover and pressed looks; the selected one — the
		// active tab, the open agent or conversation — carries an accent tint.
		Color selectedTint = new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 56);
		UIManager.put("Button.toolbar.selectedBackground", selectedTint);
		UIManager.put("ToggleButton.toolbar.selectedBackground", selectedTint);

		// Roomier menus and controls.
		UIManager.put("MenuItem.selectionArc", 8);
		UIManager.put("Menu.selectionArc", 8);
		UIManager.put("TitlePane.unifiedBackground", true);
		UIManager.put("Button.default.boldText", true);

		// Repaint if anything is already showing (harmless before UI exists).
		FlatLaf.updateUI();
	}

	/** Registers the bundled Lato faces with the AWT graphics environment. */
	private static void registerFonts() {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		for (String resource : FONT_RESOURCES) {
			try (InputStream in = LAF.class.getResourceAsStream(resource)) {
				if (in == null) {
					log.warn("Bundled font not found on the classpath: {}", resource);
					continue;
				}
				ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
			} catch (Exception e) {
				log.warn("Could not register bundled font {}: {}", resource, e.toString());
			}
		}
	}
}
