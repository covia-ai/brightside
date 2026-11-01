package brightside;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.AContent;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.impl.BlobContent;
import covia.venue.RequestContext;

/**
 * {@code brightside:restart} restarts the venue inside this process, and
 * {@code brightside:relaunch} starts the instance that takes over from this
 * one; only the venue — and so Odin, through his bridge — may ask for either.
 * Each venue is the test's own, launched with a stand-in for the app that
 * counts what it is asked to do and does none of it: its restart only counts
 * down a latch, and the successor it names is this very process, alive, so
 * still "starting". How a successor is launched is checked as a command line,
 * never run.
 */
@Execution(ExecutionMode.CONCURRENT)
class RestartTest {

	private static final String OP_RESTART = "v/ops/brightside/restart";
	private static final String OP_RELAUNCH = "v/ops/brightside/relaunch";

	@TempDir
	Path home;

	@Test
	void theVenueRestartsAfterAnswering() throws Exception {
		AtomicInteger checks = new AtomicInteger();
		AtomicBoolean refuse = new AtomicBoolean(true);
		CountDownLatch restarted = new CountDownLatch(1);
		BrightsideAdapter.App app = new BrightsideAdapter.App() {
			@Override
			public void shutdown() {
				throw new AssertionError("a restart never ends the process");
			}

			@Override
			public Runnable prepareRestart() {
				checks.incrementAndGet();
				if (refuse.get()) throw new IllegalStateException("the app refuses");
				return restarted::countDown;
			}
		};
		try (EmbeddedVenue venue = EmbeddedVenue.launch(SharedVenue.config(home), app)) {
			String userDID = Identity.of("not-the-operator").userDID(venue.did());
			assertThrows(Exception.class, () -> run(venue.clientAs(userDID), OP_RESTART), "a user cannot restart Brightside");
			assertEquals(0, checks.get(), "the app was not asked for them");

			ExecutionException refused = assertThrows(ExecutionException.class,
				() -> run(venue.clientAs(venue.did()), OP_RESTART));
			assertTrue(String.valueOf(refused.getCause()).contains("the app refuses"),
				"the app's reason reaches the caller: " + refused.getCause());
			assertEquals(1, restarted.getCount(), "a refused restart restarts nothing");

			refuse.set(false);
			ACell answer = asOdin(venue, OP_RESTART);
			assertEquals(CVMBool.TRUE, RT.getIn(answer, "accepted"), "Odin's restart is accepted: " + answer);
			assertTrue(restarted.await(10, TimeUnit.SECONDS), "and the restart runs once it has answered");
		}
	}

	@Test
	void theVenueRelaunchesOneAtATime() throws Exception {
		AtomicInteger starts = new AtomicInteger();
		AtomicBoolean refuse = new AtomicBoolean(true);
		AtomicReference<Blob> given = new AtomicReference<>();
		CountDownLatch stopped = new CountDownLatch(1);
		BrightsideAdapter.App app = new BrightsideAdapter.App() {
			@Override
			public void shutdown() {
				stopped.countDown();
			}

			@Override
			public ProcessHandle relaunch(AContent jar) throws Exception {
				starts.incrementAndGet();
				if (refuse.get()) throw new IllegalStateException("the app refuses");
				given.set((jar != null) ? jar.getBlob().toFlatBlob() : null);
				return ProcessHandle.current();
			}
		};
		// A build in the code root, as Claude Code leaves Brightside's own.
		byte[] build = { 'P', 'K', 3, 4 };
		Files.write(Files.createDirectories(home.resolve("code/brightside/target")).resolve("brightside.jar"), build);
		try (EmbeddedVenue venue = EmbeddedVenue.launch(SharedVenue.config(home), app)) {
			String userDID = Identity.of("not-the-operator").userDID(venue.did());
			assertThrows(Exception.class, () -> run(venue.clientAs(userDID), OP_RELAUNCH, Maps.empty()),
				"a user cannot relaunch Brightside");
			assertEquals(0, starts.get(), "nothing was started for them");

			Venue operator = venue.clientAs(venue.did());
			ExecutionException refused = assertThrows(ExecutionException.class, () -> run(operator, OP_RELAUNCH, Maps.empty()));
			assertTrue(String.valueOf(refused.getCause()).contains("the app refuses"),
				"the app's reason reaches the caller: " + refused.getCause());
			assertThrows(Exception.class, () -> run(operator, OP_RELAUNCH, Maps.of("jar", "file://code/nothing-here.jar")),
				"a jar that is not there is refused");
			assertEquals(1, starts.get(), "before the app is asked");

			refuse.set(false);
			ACell started = asOdin(venue, OP_RELAUNCH, Maps.of("jar", "file://code/brightside/target/brightside.jar"));
			assertEquals(CVMLong.create(ProcessHandle.current().pid()), RT.getIn(started, "pid"),
				"Odin's relaunch answers with the new instance's process: " + started);
			assertEquals(Blob.wrap(build), given.get(), "the app is handed the jar the reference names");
			assertEquals(2, starts.get());
			assertEquals(1, stopped.getCount(), "this instance runs on until its successor asks it to stop");

			assertThrows(Exception.class, () -> run(operator, OP_RELAUNCH, Maps.empty()),
				"no second relaunch while the successor is starting");
			assertEquals(2, starts.get(), "and no second successor");
		}
	}

	@Test
	void aJarIsCopiedInBeforeItRuns() throws Exception {
		Path run = Files.createDirectories(home.resolve(Relaunch.RUN_DIR));
		Path earlier = Files.write(run.resolve("brightside-earlier.jar"), new byte[] { 1 });
		Path built = jar(home.resolve("built.jar"), Main.class.getName());

		Path staged = Relaunch.stage(content(built), home);
		assertEquals(run, staged.getParent(), "the copy is what runs, so the build can be replaced");
		assertArrayEquals(Files.readAllBytes(built), Files.readAllBytes(staged));
		assertFalse(Files.exists(earlier), "an earlier copy goes");

		assertThrows(IllegalArgumentException.class,
			() -> Relaunch.stage(content(jar(home.resolve("other.jar"), "some.other.Main")), home), "not Brightside");
		assertThrows(IllegalArgumentException.class,
			() -> Relaunch.stage(BlobContent.of(Blob.wrap(new byte[] { 1, 2, 3 })), home), "not a jar at all");
		try (var left = Files.list(run)) {
			assertEquals(List.of(staged), left.toList(), "a refused jar leaves no copy behind");
		}

		List<String> command = Relaunch.command(staged, home.resolve("config.json"), List.of("--takeover"));
		assertEquals(List.of("-jar", staged.toString()), command.subList(1, 3), "and the copy is started");
	}

	@Test
	void theSuccessorComesBackAsThisOneIs() {
		assertEquals(List.of("--takeover"), BrightSide.relaunchOptions(true, true), "the window comes back, unlocked at once");
		assertEquals(List.of("--unlock", "--takeover"), BrightSide.relaunchOptions(false, true), "the tray stays the tray");
		assertEquals(List.of("--takeover"), BrightSide.relaunchOptions(false, false), "with no passphrase remembered, it asks");
		assertEquals(List.of("--takeover"), BrightSide.relaunchOptions(true, false));
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

	private static ACell run(Venue client, String op) throws Exception {
		return run(client, op, Maps.empty());
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(5, TimeUnit.SECONDS);
		return job.future().get(5, TimeUnit.SECONDS);
	}

	private static ACell asOdin(EmbeddedVenue venue, String op) throws Exception {
		return asOdin(venue, op, Maps.empty());
	}

	/** Through Odin's bridge, as his own sub-principal — the context his run loop gives his tool calls. */
	private static ACell asOdin(EmbeddedVenue venue, String op, AMap<AString, ACell> input) throws Exception {
		RequestContext odin = RequestContext.ofAgent(Strings.create(venue.did()), Strings.create(Odin.AGENT_ID));
		return venue.engine().jobs().invokeOperation(Odin.OP_RUN, Maps.of("operation", op, "input", input), odin)
			.future().get(10, TimeUnit.SECONDS);
	}

	/** An empty jar whose manifest names {@code mainClass}. */
	private static Path jar(Path file, String mainClass) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
			// the manifest is the jar
		}
		return file;
	}

	private static AContent content(Path file) throws IOException {
		return BlobContent.of(Blob.wrap(Files.readAllBytes(file)));
	}
}
