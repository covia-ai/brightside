package brightside;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small persisted user preferences (e.g. tray behaviour), stored as a
 * {@code prefs.properties} side-file in the data home. Kept separate from
 * {@code config.json} so that file stays hand-editable (comments intact) — the
 * same reasoning as the {@code model.txt} model override.
 */
public final class Prefs {

	private static final Logger log = LoggerFactory.getLogger(Prefs.class);
	private static final String FILE = "prefs.properties";

	private final Path file;
	private final Properties props = new Properties();

	private Prefs(Path file) {
		this.file = file;
	}

	/** Loads preferences from {@code <home>/prefs.properties} (empty if absent). */
	public static Prefs load(Path home) {
		Prefs p = new Prefs(home.resolve(FILE));
		if (Files.isReadable(p.file)) {
			try (InputStream in = Files.newInputStream(p.file)) {
				p.props.load(in);
			} catch (IOException e) {
				log.warn("Could not read {}: {}", FILE, e.getMessage());
			}
		}
		return p;
	}

	public boolean getBool(String key, boolean fallback) {
		String v = props.getProperty(key);
		return (v == null) ? fallback : Boolean.parseBoolean(v);
	}

	public synchronized void setBool(String key, boolean value) {
		props.setProperty(key, Boolean.toString(value));
		save();
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Brightside preferences");
			}
		} catch (IOException e) {
			log.warn("Could not write {}: {}", FILE, e.getMessage());
		}
	}
}
