package brightside;

import java.util.Locale;

/** File sizes in the words a person reads them in: bytes, then KB, MB and GB to one place. */
public final class Sizes {

	private static final String[] UNITS = { "KB", "MB", "GB", "TB" };

	private Sizes() {
	}

	public static String of(long bytes) {
		if (bytes < 1024) return bytes + " B";
		double value = bytes / 1024.0;
		int unit = 0;
		while (value >= 1024 && unit < UNITS.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
	}
}
