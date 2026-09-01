package brightside.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

/**
 * Look and feel: the themes FlatLaf provides — its own six, the IntelliJ theme
 * pack, and any {@code .theme.json} in the same format the owner drops into
 * the data home's {@code themes} folder — each restyled by Brightside's own UI
 * defaults and set in the bundled Lato at a comfortable size. {@link #init}
 * once before any Swing component exists; {@link #apply} to switch the running
 * app (Settings → Theme).
 *
 * <p>Everything about Brightside's restyling — the rounded geometry, the slim
 * scrollbars, the named style classes the components wear — lives in
 * {@code src/main/resources/brightside/ui/FlatLaf.properties}, which FlatLaf
 * loads after any theme's own defaults. On FlatLaf's core themes the accent is
 * the theme variable {@code @accentColor}, handed over as a global extra
 * default: Brightside's purple, or the colour chosen in Settings. Themes in
 * the IntelliJ format bring their own accent, as in FlatLaf's demo. Colours are
 * read back through {@link brightside.ui.components.Theme}.
 */
public final class LAF {

	private static final Logger log = LoggerFactory.getLogger(LAF.class);

	/**
	 * A theme on offer. {@code core} themes are FlatLaf's own and take an
	 * accent; the rest set their own colours.
	 */
	public record Choice(String id, String name, String group, boolean dark, boolean core, Supplier<LookAndFeel> laf) {
		@Override
		public String toString() {
			return name;
		}
	}

	public static final String GROUP_FLATLAF = "FlatLaf";
	public static final String GROUP_INTELLIJ = "IntelliJ";
	public static final String GROUP_OWN = "Yours";

	/** The two modes: every theme is one or the other, and the owner switches between them. */
	public static final String DARK = "dark";
	public static final String LIGHT = "light";

	/** The default theme of each mode — what {@code config.json}'s {@code "dark"} and {@code "light"} mean. */
	public static final String FLAT_DARK = "flat-dark";
	public static final String FLAT_LIGHT = "flat-light";

	/** Brightside's own accent, a vivid modern purple: the core themes' default until another is chosen. */
	public static final String BRIGHTSIDE_ACCENT = "#7C6CF5";

	/** The package FlatLaf searches for Brightside's {@code *.properties} UI defaults. */
	private static final String DEFAULTS_PACKAGE = "brightside.ui";

	/** Comfortable, slightly-larger base UI size (FlatLaf's own default is ~13). */
	private static final int BASE_FONT_SIZE = 15;

	/** The UI font: Lato, bundled so it's identical on every platform. */
	private static final String FONT_FAMILY = "Lato";
	/**
	 * The bundled faces: Lato for the UI, and Inconsolata for code — named
	 * first in {@code monospaced.font} in the UI defaults, with the platform's
	 * own monospaced faces behind it should the resource ever fail to register.
	 */
	private static final String[] FONT_RESOURCES = {
		"/fonts/lato/Lato-Regular.ttf",
		"/fonts/lato/Lato-Bold.ttf",
		"/fonts/lato/Lato-Light.ttf",
		"/fonts/inconsolata/Inconsolata-Regular.ttf",
		"/fonts/inconsolata/Inconsolata-Bold.ttf",
	};

	private static boolean registered;
	private static List<Choice> catalogue = List.of();
	private static Choice current;
	private static String accent;

	private LAF() {
	}

	/**
	 * Installs the look before any component exists.
	 *
	 * @param themeId   the chosen theme's id, or null
	 * @param mode      {@link #LIGHT} or {@link #DARK}: which default to fall back to when
	 *                  there is no chosen theme or it is no longer available
	 * @param accentHex the chosen accent as {@code #RRGGBB}, or null for the default
	 * @param ownThemes the folder of the owner's own {@code .theme.json} files, or null
	 */
	public static void init(String themeId, String mode, String accentHex, Path ownThemes) {
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

		catalogue = catalogue(ownThemes);
		Choice c = choice(themeId);
		if (c == null) c = choice(defaultTheme(mode));
		setup(c, accentHex);
		installFont();
		FlatLaf.updateUI();
	}

	/**
	 * Switches the running app to another theme or accent, cross-fading every
	 * open window from the old look to the new. Event thread only.
	 */
	public static void apply(String themeId, String accentHex) {
		Choice c = choice(themeId);
		if (c == null) c = (current != null) ? current : choice(FLAT_DARK);
		FlatAnimatedLafChange.showSnapshot();
		setup(c, accentHex);
		installFont();
		FlatLaf.updateUI();
		FlatAnimatedLafChange.hideSnapshotWithAnimation();
	}

	/** Every theme on offer: FlatLaf's, the IntelliJ pack's, then the owner's own. */
	public static List<Choice> themes() {
		return catalogue;
	}

	/** The themes of one mode, in catalogue order. */
	public static List<Choice> themes(String mode) {
		boolean dark = !isLight(mode);
		return catalogue.stream().filter(c -> c.dark() == dark).toList();
	}

	public static boolean isLight(String mode) {
		return LIGHT.equalsIgnoreCase(mode);
	}

	/** The mode of the theme in use. */
	public static String mode() {
		return (current != null && !current.dark()) ? LIGHT : DARK;
	}

	/** The id of a mode's default theme. */
	public static String defaultTheme(String mode) {
		return isLight(mode) ? FLAT_LIGHT : FLAT_DARK;
	}

	/** The theme with {@code id}, or null. */
	public static Choice choice(String id) {
		if (id == null) return null;
		for (Choice c : catalogue) if (c.id().equals(id)) return c;
		return null;
	}

	/** The theme in use. */
	public static Choice current() {
		return current;
	}

	/** The chosen accent as {@code #RRGGBB}, or null when the default is in use. */
	public static String accent() {
		return accent;
	}

	/** {@code hex} if it is a colour FlatLaf can read, else null. */
	public static String validAccent(String hex) {
		if (hex == null || hex.isBlank()) return null;
		try {
			Color.decode(hex.trim());
			return hex.trim();
		} catch (NumberFormatException e) {
			log.warn("Ignoring unreadable accent colour {}", hex);
			return null;
		}
	}

	private static void setup(Choice c, String accentHex) {
		String chosen = validAccent(accentHex);
		// The theme variable every accent-derived default resolves from —
		// selection, focus, check marks, the default and primary buttons. Only
		// FlatLaf's own themes take one; the others define their colours outright.
		String effective = c.core() ? ((chosen != null) ? chosen : BRIGHTSIDE_ACCENT) : null;
		FlatLaf.setGlobalExtraDefaults((effective != null) ? Map.of("@accentColor", effective) : null);

		LookAndFeel laf;
		try {
			laf = c.laf().get();
		} catch (RuntimeException e) {
			log.warn("Could not load theme {}: {}", c.name(), e.toString());
			c = choice(FLAT_DARK);
			laf = c.laf().get();
		}
		if (!FlatLaf.setup(laf)) log.warn("FlatLaf could not be installed; using the default look and feel");
		current = c;
		accent = chosen;
	}

	/** Bigger, comfortable default size in Lato: FlatLaf derives every other font from it and scales the UI to match. */
	private static void installFont() {
		UIManager.put("defaultFont", new Font(FONT_FAMILY, Font.PLAIN, BASE_FONT_SIZE));
	}

	// ------------------------------------------------------------------
	// The catalogue
	// ------------------------------------------------------------------

	private static List<Choice> catalogue(Path ownThemes) {
		List<Choice> out = new ArrayList<>();
		out.add(core(FLAT_DARK, "Dark", true, FlatDarkLaf::new));
		out.add(core(FLAT_LIGHT, "Light", false, FlatLightLaf::new));
		out.add(core("flat-mac-dark", "macOS Dark", true, FlatMacDarkLaf::new));
		out.add(core("flat-mac-light", "macOS Light", false, FlatMacLightLaf::new));
		out.add(core("flat-darcula", "Darcula", true, FlatDarculaLaf::new));
		out.add(core("flat-intellij", "IntelliJ", false, FlatIntelliJLaf::new));
		for (FlatAllIJThemes.FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
			String className = info.getClassName();
			String id = "ij:" + className.substring(className.lastIndexOf('.') + 1);
			out.add(new Choice(id, info.getName(), GROUP_INTELLIJ, info.isDark(), false, () -> instantiate(className)));
		}
		out.addAll(own(ownThemes));
		return List.copyOf(out);
	}

	private static Choice core(String id, String name, boolean dark, Supplier<LookAndFeel> laf) {
		return new Choice(id, name, GROUP_FLATLAF, dark, true, laf);
	}

	/** The owner's {@code *.theme.json} files, each read once for its name and light/dark. */
	private static List<Choice> own(Path folder) {
		List<Choice> out = new ArrayList<>();
		if (folder == null || !Files.isDirectory(folder)) return out;
		try (Stream<Path> files = Files.list(folder)) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".theme.json")).sorted().toList()) {
				try (InputStream in = Files.newInputStream(f)) {
					IntelliJTheme t = new IntelliJTheme(in);
					String name = (t.name != null && !t.name.isBlank()) ? t.name : f.getFileName().toString();
					out.add(new Choice("file:" + f.getFileName(), name, GROUP_OWN, t.dark, false, () -> load(f)));
				} catch (IOException | RuntimeException e) {
					log.warn("Skipping theme {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			log.warn("Could not list {}: {}", folder, e.toString());
		}
		return out;
	}

	private static LookAndFeel load(Path f) {
		try {
			return IntelliJTheme.createLaf(Files.newInputStream(f));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static LookAndFeel instantiate(String className) {
		try {
			return (LookAndFeel) Class.forName(className).getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Theme " + className + " unavailable", e);
		}
	}

	/** Registers the bundled faces with the AWT graphics environment. */
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
