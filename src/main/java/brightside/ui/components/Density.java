package brightside.ui.components;

/**
 * Density for tables and lists: Comfortable (the default) or Compact, chosen
 * in View → Density and Settings → Theme and remembered per person. Compact
 * tightens row height and padding only; type stays the same size. Renderers
 * read it when they lay a row out.
 */
public final class Density {

	private static volatile boolean compact;

	private Density() {
	}

	public static void set(boolean compact) {
		Density.compact = compact;
	}

	public static boolean compact() {
		return compact;
	}

	/** {@code comfortable} vertical padding in unscaled pixels, halved (at least 2) when compact. */
	public static int pad(int comfortable) {
		return compact ? Math.max(2, comfortable / 2) : comfortable;
	}
}
