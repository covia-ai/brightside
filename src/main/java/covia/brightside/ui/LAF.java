package covia.brightside.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
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

	private LAF() {
	}

	/** Installs the theme ({@code "dark"} unless {@code "light"}). */
	public static void init(String theme) {
		// Bundled Inter — set as the preferred family before the theme installs so
		// every style (regular, light, semibold) maps to it.
		FlatInterFont.install();
		FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
		FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
		FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);

		boolean light = "light".equalsIgnoreCase(theme);
		boolean ok = light ? FlatMacLightLaf.setup() : FlatMacDarkLaf.setup();
		if (!ok) log.warn("FlatLaf could not be installed; using the default look and feel");

		// Bigger, comfortable default size in Inter (components derive from this).
		UIManager.put("defaultFont", new Font(FlatInterFont.FAMILY, Font.PLAIN, BASE_FONT_SIZE));

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

		// Roomier menus and controls.
		UIManager.put("MenuItem.selectionArc", 8);
		UIManager.put("Menu.selectionArc", 8);
		UIManager.put("TitlePane.unifiedBackground", true);
		UIManager.put("Button.default.boldText", true);

		// Repaint if anything is already showing (harmless before UI exists).
		FlatLaf.updateUI();
	}
}
