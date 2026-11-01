package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * {@code brightside:restart} starts the instance that takes over from this one,
 * as only the venue — and so Odin, through his bridge — may ask, and answers
 * once it has started. The venue is the test's own, launched with a stand-in
 * for the app that counts what it is asked to start and starts nothing: the
 * successor it names is this very process, alive, so still "starting". How the
 * successor is launched is checked as a command line, never run.
 */
@Execution(ExecutionMode.CONCURRENT)
class RestartTest {

	private static final String OP_RESTART = "v/ops/brightside/restart";

	@TempDir
	Path home;

	@Test
	void theVenueRestartsOneAtATime() throws Exception {
		AtomicInteger starts = new AtomicInteger();
		AtomicBoolean refuse = new AtomicBoolean(true);
		CountDownLatch stopped = new CountDownLatch(1);
		try (EmbeddedVenue venue = EmbeddedVenue.launch(SharedVenue.config(home), stopped::countDown, () -> {
			starts.incrementAndGet();
			if (refuse.get()) throw new IllegalStateException("the app refuses");
			return ProcessHandle.current();
		})) {
			String userDID = Identity.of("not-the-operator").userDID(venue.did());
			assertThrows(Exception.class, () -> run(venue.clientAs(userDID)), "a user cannot restart Brightside");
			assertEquals(0, starts.get(), "nothing was started for them");

			Venue operator = venue.clientAs(venue.did());
			ExecutionException refused = assertThrows(ExecutionException.class, () -> run(operator));
			assertTrue(String.valueOf(refused.getCause()).contains("the app refuses"),
				"the app's reason reaches the caller: " + refused.getCause());

			refuse.set(false);
			RequestContext odin = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Odin.AGENT_ID));
			ACell started = venue.engine().jobs().invokeOperation(Odin.OP_RUN, Maps.of("operation", OP_RESTART), odin)
				.future().get(10, TimeUnit.SECONDS);
			assertEquals(CVMLong.create(ProcessHandle.current().pid()), RT.getIn(started, "pid"),
				"Odin's restart answers with the new instance's process: " + started);
			assertEquals(2, starts.get());
			assertEquals(1, stopped.getCount(), "this instance runs on until its successor asks it to stop");

			assertThrows(Exception.class, () -> run(operator), "no second restart while the successor is starting");
			assertEquals(2, starts.get(), "and no second successor");
		}
	}

	@Test
	void theSuccessorComesBackAsThisOneIs() {
		assertEquals(List.of("--takeover"), BrightSide.restartOptions(true, true), "the window comes back, unlocked at once");
		assertEquals(List.of("--unlock", "--takeover"), BrightSide.restartOptions(false, true), "the tray stays the tray");
		assertEquals(List.of("--takeover"), BrightSide.restartOptions(false, false), "with no passphrase remembered, it asks");
		assertEquals(List.of("--takeover"), BrightSide.restartOptions(true, false));
	}

	@Test
	void theSuccessorIsTheSameProgram() throws Exception {
		Path config = home.resolve("config.json");
		Path java = Path.of(System.getProperty("java.home"), "bin");

		Path jar = Files.write(home.resolve("brightside.jar"), new byte[0]);
		List<String> fromJar = Relaunch.command(jar, config, List.of("--takeover"));
		assertEquals(java, Path.of(fromJar.get(0)).getParent(), "the same Java");
		assertEquals(List.of("-jar", jar.toString(), "--takeover", config.toString()), fromJar.subList(1, fromJar.size()));

		// No jar, as from an IDE: the same class path.
		List<String> fromClasses = Relaunch.command(home, config, List.of("--unlock", "--takeover"));
		assertEquals(java, Path.of(fromClasses.get(0)).getParent());
		assertEquals(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName(),
			"--unlock", "--takeover", config.toString()), fromClasses.subList(1, fromClasses.size()));

		// Loaded by another program's class loader, as `mvn exec:java` loads it, this
		// process's class path is that program's: refused rather than started to fail.
		Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		try (URLClassLoader other = new URLClassLoader(new URL[] { classes.toUri().toURL() }, null)) {
			Class<?> elsewhere = Class.forName(Main.class.getName(), false, other);
			assertThrows(IllegalStateException.class, () -> Relaunch.command(elsewhere, config, List.of("--takeover")));
		}
		assertEquals(config.toString(), Relaunch.command(Main.class, config, List.of("--takeover")).getLast(),
			"this process's own program is started again");
	}

	private static ACell run(Venue client) throws Exception {
		Job job = client.invoke(OP_RESTART, Maps.empty()).get(5, TimeUnit.SECONDS);
		return job.future().get(5, TimeUnit.SECONDS);
	}
}
