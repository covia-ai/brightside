package brightside;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Exercises startup ownership and failure paths without launching a desktop. */
@Execution(ExecutionMode.CONCURRENT)
class McpStartupTest {

	@TempDir
	Path home;

	@Test
	void aRunningButLockedAppIsNeverRestarted() throws Exception {
		McpStartup.ensureRunning(home, () -> true, () -> false,
			() -> { throw new AssertionError("Existing app must not be launched again"); }, Duration.ZERO);
	}

	@Test
	void simultaneousConnectionsWaitForOneLaunchAndItsCredential() throws Exception {
		var running = new AtomicBoolean();
		var readyEntered = new CountDownLatch(1);
		var releaseReady = new CompletableFuture<Boolean>();
		var secondEntered = new CountDownLatch(1);
		var starts = new AtomicInteger();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = executor.submit(() -> {
				McpStartup.ensureRunning(home, running::get, () -> {
					readyEntered.countDown();
					return releaseReady.join();
				}, () -> {
					starts.incrementAndGet();
					running.set(true);
					return new Child(true);
				}, Duration.ofSeconds(5));
				return null;
			});
			try {
				assertTrue(readyEntered.await(5, TimeUnit.SECONDS));
				var second = executor.submit(() -> {
					secondEntered.countDown();
					McpStartup.ensureRunning(home, running::get, () -> true, () -> {
						starts.incrementAndGet();
						return new Child(true);
					}, Duration.ofSeconds(5));
					return null;
				});
				assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
				releaseReady.complete(true);
				first.get(5, TimeUnit.SECONDS);
				second.get(5, TimeUnit.SECONDS);
				assertEquals(1, starts.get());
				assertEquals(0, Files.size(home.resolve(McpStartup.LOCK_FILE)));
			} finally {
				releaseReady.complete(true);
			}
		}
	}

	@Test
	void aFailedChildIsReportedAndTheLaunchLeaseIsReleased() throws Exception {
		IOException failure = assertThrows(IOException.class, () ->
			McpStartup.ensureRunning(home, () -> false, () -> false,
				() -> new Child(false), Duration.ofSeconds(5)));
		assertEquals(McpStartup.START_FAILED, failure.getMessage());
		// A later connection can acquire the same lock after failure.
		McpStartup.ensureRunning(home, () -> true, () -> true,
			() -> { throw new AssertionError(); }, Duration.ZERO);
		assertEquals(0, Files.size(home.resolve(McpStartup.LOCK_FILE)));
	}

	@Test
	void aTimedOutConnectionLeavesTheAppAliveAndTheNextConnectionRejoinsIt() throws Exception {
		Child child = new Child(true);
		IOException timeout = assertThrows(IOException.class, () ->
			McpStartup.ensureRunning(home, () -> false, () -> false, () -> child, Duration.ZERO));
		assertEquals(McpStartup.TIMED_OUT, timeout.getMessage());
		assertTrue(child.isAlive());
		assertTrue(Files.size(home.resolve(McpStartup.LOCK_FILE)) > 0);
		var readinessChecks = new AtomicInteger();
		McpStartup.ensureRunning(home, () -> true, () -> {
			readinessChecks.incrementAndGet();
			return true;
		},
			() -> { throw new AssertionError("Must rejoin the recorded child"); }, Duration.ZERO);
		assertEquals(1, readinessChecks.get(), "An open port alone is not a completed startup");
		assertEquals(0, Files.size(home.resolve(McpStartup.LOCK_FILE)));
	}

	@Test
	void unattendedCommandUsesTheSameInstallationAndNeverRequestsTakeover() throws Exception {
		Path config = home.resolve("my config.json");
		var command = McpStartup.command(config);
		assertTrue(command.contains("--unlock"));
		assertEquals(config.toAbsolutePath().toString(), command.getLast());
		assertFalse(command.contains("--takeover"));
		assertFalse(command.contains("--mcp-stdio"));
		assertTrue(Path.of(command.getFirst()).isAbsolute());
	}

	/** Controlled child lifetime; the live test JVM supplies a real process identity. */
	private static final class Child extends Process {
		private final boolean alive;

		Child(boolean alive) { this.alive = alive; }
		@Override public boolean isAlive() { return alive; }
		@Override public int exitValue() {
			if (alive) throw new IllegalThreadStateException();
			return 1;
		}
		@Override public ProcessHandle toHandle() { return ProcessHandle.current(); }
		@Override public void destroy() { throw new AssertionError("The app must outlive its bridge"); }
		@Override public int waitFor() { throw new UnsupportedOperationException(); }
		@Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
		@Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
		@Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
	}
}
