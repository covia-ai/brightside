package covia.brightside.ui;

import java.awt.Insets;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

/** FlatLaf look and feel setup. Call once, before any Swing component exists. */
public final class LAF {

	private static final Logger log = LoggerFactory.getLogger(LAF.class);

	private LAF() {
	}

	/** Installs FlatLaf in the given theme ({@code "dark"} unless {@code "light"}). */
	public static void init(String theme) {
		boolean ok = "light".equalsIgnoreCase(theme) ? FlatLightLaf.setup() : FlatDarkLaf.setup();
		if (!ok) log.warn("FlatLaf could not be installed; using the default look and feel");
		UIManager.put("Component.arc", 8);
		UIManager.put("Button.arc", 8);
		UIManager.put("TextComponent.arc", 8);
		UIManager.put("ScrollBar.thumbArc", 999);
		UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
	}
}
