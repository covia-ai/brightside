package brightside;

import static brightside.BrightSide.APP_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import covia.grid.AContent;

/**
 * Starts this program again as a separate process: the same Java, and the same
 * jar, or the same class path when there is none (a launch from an IDE), or a
 * jar copied in for the purpose ({@link #stage}). The MCP bridge starts the app
 * this way when it is absent ({@link McpStartup}), and a relaunch starts its
 * successor this way ({@code brightside:relaunch}).
 */
final class Relaunch {

	/** Where a jar to relaunch into is copied, under the data directory. */
	static final String RUN_DIR = "run";
	private static final String PREFIX = "brightside-";
	private static final String SUFFIX = ".jar";

	private Relaunch() {
	}

	/**
	 * Starts it with {@code options} and the configuration file. The new process
	 * outlives this one, so it never inherits a pipe this process owns: its
	 * output is discarded and its input closed. It writes its own log.
	 */
	static Process start(Path config, List<String> options) throws Exception {
		return start(command(config, options));
	}

	/** As {@link #start(Path, List)}, from the jar at {@code jar} rather than this program. */
	static Process start(Path jar, Path config, List<String> options) throws Exception {
		return start(command(jar, config, options));
	}

	private static Process start(List<String> command) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process child = builder.start();
		child.getOutputStream().close();
		return child;
	}

	/**
	 * Copies {@code jar} into the run directory under {@code home} and returns
	 * the copy, which is what runs: a build can then be replaced while its copy
	 * runs, as Windows locks a running jar. Refused unless it is a Brightside
	 * jar. Earlier copies go, except any still running (locked) and the one this
	 * process runs, which a later relaunch of this program needs.
	 */
	static Path stage(AContent jar, Path home) throws IOException {
		Path dir = Files.createDirectories(home.resolve(RUN_DIR));
		Path copy = Files.createTempFile(dir, PREFIX, SUFFIX);
		try (InputStream in = jar.getInputStream()) {
			Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
		}
		String main = mainClass(copy);
		if (!Main.class.getName().equals(main)) {
			Files.deleteIfExists(copy);
			throw new covia.exception.InvalidInputException("That is not a " + APP_NAME + " jar"
				+ ((main != null) ? " (its main class is " + main + ")" : ""));
		}
		Path running = runningJar();
		try (DirectoryStream<Path> earlier = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
			for (Path p : earlier) {
				if (p.equals(copy) || p.toAbsolutePath().normalize().equals(running)) continue;
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// still running, and locked: it goes at a later relaunch
				}
			}
		}
		return copy;
	}

	/** The jar's {@code Main-Class}, or null when it is not a jar or names none. */
	private static String mainClass(Path jar) {
		try (JarFile file = new JarFile(jar.toFile())) {
			Manifest manifest = file.getManifest();
			return (manifest != null) ? manifest.getMainAttributes().getValue("Main-Class") : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** The jar this process runs, or null when it runs from classes. */
	private static Path runningJar() {
		try {
			Path source = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return Files.isRegularFile(source) ? source.toAbsolutePath().normalize() : null;
		} catch (Exception e) {
			return null;
		}
	}

	static List<String> command(Path config, List<String> options) throws Exception {
		return command(Main.class, config, options);
	}

	/**
	 * The command for the program {@code main} belongs to. Refused when another
	 * program's class loader loaded it, as {@code mvn exec:java} does: this
	 * process's class path is then that program's, and would not start it.
	 */
	static List<String> command(Class<?> main, Path config, List<String> options) throws Exception {
		if (main.getClassLoader() != ClassLoader.getSystemClassLoader()) {
			throw new covia.exception.ConflictException(APP_NAME + " is running inside another program (such as mvn exec:java),"
				+ " so it cannot start itself again: start it again the way it was started");
		}
		return command(Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()), config, options);
	}

	/** The command for the program at {@code source}: a jar, or a directory of classes. */
	static List<String> command(Path source, Path config, List<String> options) {
		Path bin = Path.of(System.getProperty("java.home"), "bin");
		// javaw prevents a console window when there is no console to inherit.
		Path java = bin.resolve(Files.isRegularFile(bin.resolve("javaw.exe")) ? "javaw.exe" : "java");
		List<String> command = new ArrayList<>(List.of(java.toString()));
		if (Files.isRegularFile(source)) {
			command.addAll(List.of("-jar", source.toString()));
		} else {
			command.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
		}
		command.addAll(options);
		command.add(config.toAbsolutePath().normalize().toString());
		return command;
	}
}
