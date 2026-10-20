package brightside;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Serialises cold MCP connections while the desktop app starts independently. */
final class McpStartup {

	static final String LOCK_FILE = "mcp-startup.lock";
	static final Duration TIMEOUT = Duration.ofSeconds(30);
	static final String SETUP_REQUIRED = "To start Brightside automatically, unlock it once with "
		+ "'Remember me' enabled and enable 'Keep a token for local tools' in Settings > Auth.";
	static final String START_FAILED = "Brightside could not start automatically. "
		+ "Open it to check the saved passphrase and see the logs.";
	static final String TIMED_OUT = "Brightside is still starting. Reconnect the MCP client once it is ready.";

	@FunctionalInterface
	interface Starter {
		Process start() throws Exception;
	}

	private McpStartup() {
	}

	static void ensureRunning(Path config, int port, BooleanSupplier ready) throws Exception {
		Path home = config.toAbsolutePath().getParent();
		ensureRunning(home, () -> Takeover.isRunning(port), ready, () -> {
			if (!Prefs.load(home).getBool("toolToken.keep", false) || !RememberedPassphrase.exists(home)) {
				throw new IOException(SETUP_REQUIRED);
			}
			return start(config);
		}, TIMEOUT);
	}

	/**
	 * Hold a cross-process lock until the port and credential are ready. Never
	 * delete the lock file: replacing its inode could admit two launchers.
	 * The injectable boundary lets tests follow startup without opening a UI.
	 */
	static void ensureRunning(Path home, BooleanSupplier running, BooleanSupplier ready,
			Starter starter, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		try (FileChannel channel = FileChannel.open(home.resolve(LOCK_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock acquired;
			while ((acquired = tryLock(channel)) == null) {
				pause(deadline);
			}
			try (FileLock lock = acquired) {
				ProcessHandle pending = pending(channel);
				// Even an unauthenticated HTTP answer means something owns the port.
				// Leave an existing instance, including a locked one, alone. A
				// recorded startup still needs its credential after the port opens.
				if (running.getAsBoolean() && pending == null) return;
				Process child = null;
				if (pending == null) {
					child = starter.start();
					pending = child.toHandle();
					remember(channel, pending);
				}
				while (!running.getAsBoolean() || !ready.getAsBoolean()) {
					if (child != null ? !child.isAlive() && child.exitValue() != 0 : !pending.isAlive()) {
						channel.truncate(0);
						throw new IOException(START_FAILED);
					}
					// A zero exit may mean an ordinary launch won the race. Wait for
					// that instance's token too; never retry or request takeover.
					pause(deadline);
				}
				channel.truncate(0);
			}
		}
	}

	/** Rejoin a child left starting if its bridge exited or timed out. */
	private static ProcessHandle pending(FileChannel channel) throws IOException {
		if (channel.size() == 0 || channel.size() > 256) return null;
		ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
		channel.position(0);
		while (bytes.hasRemaining() && channel.read(bytes) > 0) {}
		try {
			String[] parts = new String(bytes.array(), StandardCharsets.UTF_8).split(" ", 2);
			long pid = Long.parseLong(parts[0]);
			String started = parts[1];
			return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
				.filter(p -> p.info().startInstant().map(t -> t.toString().equals(started)).orElse(false))
				.orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void remember(FileChannel channel, ProcessHandle child) throws IOException {
		var started = child.info().startInstant();
		if (started.isEmpty()) return;
		ByteBuffer bytes = StandardCharsets.UTF_8.encode(child.pid() + " " + started.get());
		channel.truncate(0).position(0);
		while (bytes.hasRemaining()) channel.write(bytes);
		channel.force(true);
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException e) {
			return null; // Another connection in this JVM holds it.
		}
	}

	private static void pause(long deadline) throws IOException, InterruptedException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) throw new IOException(TIMED_OUT);
		TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
	}

	private static Process start(Path config) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command(config));
		// The desktop outlives the bridge. Never let it inherit protocol stdout
		// or an input/output pipe owned by the MCP client. It has its own log.
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process child = builder.start();
		child.getOutputStream().close();
		return child;
	}

	static List<String> command(Path config) throws Exception {
		Path bin = Path.of(System.getProperty("java.home"), "bin");
		// javaw prevents a console window when a Windows client has no console.
		Path java = bin.resolve(Files.isRegularFile(bin.resolve("javaw.exe")) ? "javaw.exe" : "java");
		List<String> command = new ArrayList<>(List.of(java.toString()));
		Path source = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		if (Files.isRegularFile(source)) {
			command.addAll(List.of("-jar", source.toString()));
		} else {
			command.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
		}
		command.addAll(List.of("--unlock", config.toAbsolutePath().normalize().toString()));
		return command;
	}
}
