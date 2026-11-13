package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sign-in entry follows the setting: written naming this process's jar
 * with the unattended launch, kept current, removed when not wanted, and
 * another program's entry of the same name never touched. A stand-in for the
 * registry: the real one is never written.
 */
class StartAtSignInTest {

	private final Map<String, String> entries = new HashMap<>();
	private final StartAtSignIn.Registry registry = new StartAtSignIn.Registry() {
		@Override
		public String read(String name) {
			return entries.get(name);
		}

		@Override
		public void write(String name, String command) {
			entries.put(name, command);
		}

		@Override
		public void delete(String name) {
			entries.remove(name);
		}
	};

	private final Path config = AppConfig.HOME.resolve("config.json");
	@TempDir
	Path dir;
	private Path jar;

	/** A jar to start, in a folder whose name has a space, as Program Files does. */
	@BeforeEach
	void jar() throws IOException {
		jar = Files.createFile(Files.createDirectories(dir.resolve("Tau Builds")).resolve("brightside.jar"));
	}

	@Test
	void turningItOnWritesTheUnattendedLaunchOfThisJar() throws IOException {
		StartAtSignIn signIn = new StartAtSignIn(registry, config, jar);
		assertNull(signIn.unavailable());
		assertEquals(BrightSide.APP_NAME, signIn.name(), "the usual data directory goes unnamed");
		signIn.apply(true);
		String command = entries.get(signIn.name());
		assertNotNull(command);
		assertTrue(command.contains("--unlock"), "it unlocks unattended, in the tray");
		assertFalse(command.contains("--takeover"), "and leaves a running one alone");
		assertTrue(command.contains("\"" + jar + "\""), "a path with spaces is quoted");
		assertTrue(command.contains(config.toAbsolutePath().normalize().toString()));
	}

	@Test
	void aStartKeepsTheEntryOnTheJarItRunsFrom() throws IOException {
		new StartAtSignIn(registry, config, Files.createFile(dir.resolve("old.jar"))).apply(true);
		StartAtSignIn signIn = new StartAtSignIn(registry, config, jar);
		signIn.apply(true);
		assertEquals(signIn.command(), entries.get(signIn.name()));
	}

	@Test
	void turningItOffRemovesOnlyItsOwnEntry() throws IOException {
		StartAtSignIn signIn = new StartAtSignIn(registry, config, jar);
		signIn.apply(true);
		signIn.apply(false);
		assertTrue(entries.isEmpty());

		entries.put(signIn.name(), "\"C:/Other/tau.exe\" --background");
		signIn.apply(false);
		assertEquals("\"C:/Other/tau.exe\" --background", entries.get(signIn.name()), "another program's entry stays");
		assertThrows(IOException.class, () -> signIn.apply(true), "and is never overwritten");
		assertEquals("\"C:/Other/tau.exe\" --background", entries.get(signIn.name()));
	}

	@Test
	void withoutAJarNothingIsWrittenButAStaleEntryStillGoes() throws IOException {
		new StartAtSignIn(registry, config, jar).apply(true);
		StartAtSignIn fromClasses = new StartAtSignIn(registry, config, null);
		assertNotNull(fromClasses.unavailable());
		fromClasses.apply(false);
		assertTrue(entries.isEmpty());
		fromClasses.apply(true);
		assertTrue(entries.isEmpty(), "nothing to start, so nothing written");
	}

	@Test
	void anotherDataDirectoryHasAnEntryOfItsOwn() throws IOException {
		StartAtSignIn usual = new StartAtSignIn(registry, config, jar);
		StartAtSignIn other = new StartAtSignIn(registry, Path.of("C:/Agents/second/config.json"), jar);
		assertFalse(usual.name().equals(other.name()));
		usual.apply(true);
		other.apply(true);
		other.apply(false);
		assertEquals(1, entries.size(), "turning one off leaves the other");
	}

	@Test
	void offWindowsItIsNotOffered() throws IOException {
		StartAtSignIn none = new StartAtSignIn(null, config, jar);
		assertFalse(none.offered());
		assertNotNull(none.unavailable());
		none.apply(true); // nothing to write to, and nothing goes wrong
	}
}
