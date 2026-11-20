package brightside.ui;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Whether the operating system is set to a dark appearance, for "Match the
 * system": macOS's AppleInterfaceStyle, Windows's AppsUseLightTheme, and
 * GNOME's color-scheme. When it can't tell, dark, Tau's own default.
 */
public final class SystemAppearance {

	private SystemAppearance() {
	}

	public static boolean dark() {
		String os = System.getProperty("os.name", "").toLowerCase();
		try {
			if (os.contains("mac")) {
				// Prints "Dark" in dark mode; fails (no such key) in light mode.
				String out = run("defaults", "read", "-g", "AppleInterfaceStyle");
				return out != null && out.trim().equalsIgnoreCase("dark");
			}
			if (os.contains("win")) {
				String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
					"/v", "AppsUseLightTheme");
				if (out == null) return true;
				return !out.contains("0x1");
			}
			String out = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
			// 'prefer-dark' is dark; 'default' and 'prefer-light' are light.
			if (out != null) return out.contains("dark");
		} catch (RuntimeException e) {
			// fall through to the default
		}
		return true;
	}

	/** Runs a short command and returns what it printed, or null if it failed or took too long. */
	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			if (!p.waitFor(2, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			String out = new String(p.getInputStream().readAllBytes());
			return (p.exitValue() == 0) ? out : null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
