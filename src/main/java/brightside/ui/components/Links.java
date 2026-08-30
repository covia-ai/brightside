package brightside.ui.components;

import java.awt.Desktop;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opening things on the desktop. */
public final class Links {

	private static final Logger log = LoggerFactory.getLogger(Links.class);

	private Links() {
	}

	/** Opens {@code url} in the default browser; best effort, never throws. */
	public static void open(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
			}
		} catch (Exception e) {
			log.warn("Could not open {}: {}", url, e.toString());
		}
	}
}
