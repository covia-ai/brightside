package brightside.ui;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * Tau's Light theme: the gold-ground system, with white content and cards, a
 * gold sidebar and chrome, navy text, purple actions and Cerulean outlines and
 * focus. Its colours are in {@code brightside/ui/TauLightLaf.properties},
 * loaded after FlatLaf's light defaults and after Brightside's shared
 * {@code FlatLaf.properties}.
 */
@SuppressWarnings("serial")
public class TauLightLaf extends FlatLightLaf {

	public static final String NAME = "Tau Light";

	@Override
	public String getName() {
		return NAME;
	}
}
