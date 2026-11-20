package brightside.ui;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Tau's own dark theme, the default: the navy field, a sunken sidebar, raised
 * cards, and amber only for what is yours and what is waiting. Its colours are
 * in {@code brightside/ui/TauDarkLaf.properties}, loaded after FlatLaf's dark
 * defaults and after Brightside's shared {@code FlatLaf.properties}, so it
 * wins over both. A dark pass of its own: never an inversion of
 * {@link TauLightLaf}.
 */
@SuppressWarnings("serial")
public class TauDarkLaf extends FlatDarkLaf {

	public static final String NAME = "Tau Dark";

	@Override
	public String getName() {
		return NAME;
	}
}
