package brightside;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts this program again as a separate process: the same Java, and the same
 * jar, or the same class path when there is none (a launch from an IDE). The
 * MCP bridge starts the app this way when it is absent ({@link McpStartup}),
 * and a relaunch starts its successor this way ({@code brightside:relaunch}).
 */
final class Relaunch {

	private Relaunch() {
	}

	/**
	 * Starts it with {@code options} and the configuration file. The new process
	 * outlives this one, so it never inherits a pipe this process owns: its
	 * output is discarded and its input closed. It writes its own log.
	 */
	static Process start(Path config, List<String> options) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command(config, options));
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process child = builder.start();
		child.getOutputStream().close();
		return child;
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
			throw new IllegalStateException("Brightside is running inside another program (such as mvn exec:java),"
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
