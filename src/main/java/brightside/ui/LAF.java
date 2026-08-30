package brightside.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Look and feel: FlatLaf's light or dark theme, restyled by Brightside's own
 * UI defaults and set in the bundled Lato at a comfortable size. Call once,
 * before any Swing component exists.
 *
 * <p>Everything about the look — the purple accent, the rounded geometry, the
 * slim scrollbars, the named style classes the components wear — lives in
 * {@code src/main/resources/brightside/ui/FlatLaf.properties} (with the light
 * and dark variants beside it), which FlatLaf loads after the theme's own
 * defaults. Colours are read back through
 * {@link brightside.ui.components.Theme}.
 */
public final class LAF {

	private static final Logger log = LoggerFactory.getLogger(LAF.class);

	/** The package FlatLaf searches for Brightside's {@code *.properties} UI defaults. */
	private static final String DEFAULTS_PACKAGE = "brightside.ui";

	/** Comfortable, slightly-larger base UI size (FlatLaf's own default is ~13). */
	private static final int BASE_FONT_SIZE = 15;

	/** The UI font: Lato, bundled so it's identical on every platform. */
	private static final String FONT_FAMILY = "Lato";
	private static final String[] FONT_RESOURCES = {
		"/fonts/lato/Lato-Regular.ttf",
		"/fonts/lato/Lato-Bold.ttf",
		"/fonts/lato/Lato-Light.ttf",
	};

	private static boolean registered;

	private LAF() {
	}

	/** Installs the theme ({@code "dark"} unless {@code "light"}). */
	public static void init(String theme) {
		// Bundled Lato — register the faces, then make it the preferred family
		// before the theme installs so components pick it up.
		registerFonts();
		FlatLaf.setPreferredFontFamily(FONT_FAMILY);

		// Brightside's defaults load after the theme's, so they win. Registered
		// once: the source list is additive.
		if (!registered) {
			FlatLaf.registerCustomDefaultsSource(DEFAULTS_PACKAGE);
			registered = true;
		}

		boolean light = "light".equalsIgnoreCase(theme);
		boolean ok = light ? FlatLightLaf.setup() : FlatDarkLaf.setup();
		if (!ok) log.warn("FlatLaf could not be installed; using the default look and feel");

		// Bigger, comfortable default size in Lato: FlatLaf derives every other
		// font from it and scales the UI to match.
		UIManager.put("defaultFont", new Font(FONT_FAMILY, Font.PLAIN, BASE_FONT_SIZE));

		// Apply the font (and repaint anything already showing).
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
