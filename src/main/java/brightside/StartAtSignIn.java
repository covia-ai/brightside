package brightside;

import static brightside.BrightSide.APP_NAME;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Starting Tau when the owner signs in to Windows, which they opt into in
 * Settings → General → Startup and only with a passphrase remembered.
 *
 * <p>It is one value under the account's own {@code Run} key, which Windows
 * starts at sign-in and Task Manager lists under Startup apps, so the owner can
 * always see and disable it without Tau. The command is the {@code --unlock}
 * launch: up in the tray, unlocked with the remembered passphrase, leaving a
 * running Tau alone. Windows starts it, so it belongs to no other program's
 * job. While wanted, the entry is kept naming the Java and jar this process
 * runs from, refreshed once Tau is up; an entry of the same name that names
 * another configuration is another program's and is never changed.
 */
final class StartAtSignIn {

	/** Where Windows keeps the account's programs to start at sign-in. */
	static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

	/** The account's startup entries, by name. */
	interface Registry {
		/** The entry's command, or null when there is none. */
		String read(String name) throws IOException;

		void write(String name, String command) throws IOException;

		void delete(String name) throws IOException;
	}

	private final Registry registry; // null where there is none, off Windows
	private final Path config;
	private final Path jar; // null when running from classes
	private final String name;

	/**
	 * @param registry the account's startup entries, or null where there are none
	 * @param config   the configuration file this process runs with
	 * @param jar      the jar this process runs from, or null for classes
	 */
	StartAtSignIn(Registry registry, Path config, Path jar) {
		this.registry = registry;
		this.config = config.toAbsolutePath().normalize();
		this.jar = jar;
		Path home = this.config.getParent();
		this.name = AppConfig.HOME.toAbsolutePath().normalize().equals(home) ? APP_NAME : APP_NAME + " (" + home + ")";
	}

	/** For this process: Windows' registry, and the jar it runs from. */
	static StartAtSignIn forThisProcess(Path config) {
		return new StartAtSignIn(WindowsProcess.isWindows() ? new WindowsRegistry() : null, config, Relaunch.runningJar());
	}

	/** Whether the setting means anything here: on Windows only. */
	boolean offered() {
		return registry != null;
	}

	/** Why it cannot be turned on here, or null when it can. */
	String unavailable() {
		if (registry == null) return "Only on Windows.";
		if (jar == null) return APP_NAME + " is running from its classes, not a jar: start it from its jar to use this.";
		return null;
	}

	/** The entry's name: the app's, with the data directory when it is not the usual one. */
	String name() {
		return name;
	}

	/** What Windows runs at sign-in. */
	String command() {
		return WindowsProcess.commandLine(Relaunch.command(jar, config, List.of("--unlock")));
	}

	/**
	 * Makes the entry match {@code wanted}: present and naming this process's
	 * Java and jar, or gone. A launch from classes has no jar to name, so it
	 * leaves a wanted entry as it is. Refused when another program's entry has
	 * the name.
	 */
	void apply(boolean wanted) throws IOException {
		if (registry == null) return;
		String current = registry.read(name);
		boolean ours = current != null && current.contains(config.toString());
		if (wanted) {
			if (current != null && !ours) {
				throw new IOException("Another program already starts at sign-in as \"" + name + "\"");
			}
			if (jar == null) return; // nothing to name: keep what an earlier start wrote
			String command = command();
			if (!command.equals(current)) registry.write(name, command);
		} else if (ours) {
			registry.delete(name);
		}
	}

	/**
	 * As {@link #apply}, but never throws, so a registry that fails cannot stop
	 * the launch or unlock that asked: null when the entry matches, else why not.
	 */
	String tryApply(boolean wanted) {
		try {
			apply(wanted);
			return null;
		} catch (IOException | RuntimeException | LinkageError e) {
			return (e.getMessage() != null) ? e.getMessage() : e.toString();
		}
	}

	/** The current account's {@code Run} key, through the Windows registry API. */
	static final class WindowsRegistry implements Registry {
		private final String key;

		WindowsRegistry() {
			this(RUN_KEY);
		}

		/** Another key of the current account's, such as a scratch key to check the calls against. */
		WindowsRegistry(String key) {
			this.key = key;
		}

		private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress((long) (int) 0x80000001);
		private static final int REG_SZ = 1;
		private static final int RRF_RT_REG_SZ = 0x2;
		private static final int ERROR_SUCCESS = 0, ERROR_FILE_NOT_FOUND = 2;

		private static final class Advapi32 {
			static final Linker LINKER = Linker.nativeLinker();
			static final SymbolLookup LIB = SymbolLookup.libraryLookup("advapi32", Arena.global());
			static final MethodHandle GET = LINKER.downcallHandle(LIB.findOrThrow("RegGetValueW"),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
			static final MethodHandle SET = LINKER.downcallHandle(LIB.findOrThrow("RegSetKeyValueW"),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
			static final MethodHandle DELETE = LINKER.downcallHandle(LIB.findOrThrow("RegDeleteKeyValueW"),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		}

		@Override
		public String read(String name) throws IOException {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment key = wide(arena, this.key), value = wide(arena, name);
				MemorySegment size = arena.allocate(JAVA_INT);
				int status = (int) Advapi32.GET.invokeExact(HKEY_CURRENT_USER, key, value, RRF_RT_REG_SZ,
					MemorySegment.NULL, MemorySegment.NULL, size);
				if (status == ERROR_FILE_NOT_FOUND) return null;
				check(status, "read");
				MemorySegment data = arena.allocate(size.get(JAVA_INT, 0) + 2L, 2);
				size.set(JAVA_INT, 0, (int) data.byteSize());
				status = (int) Advapi32.GET.invokeExact(HKEY_CURRENT_USER, key, value, RRF_RT_REG_SZ,
					MemorySegment.NULL, data, size);
				if (status == ERROR_FILE_NOT_FOUND) return null;
				check(status, "read");
				return data.getString(0, StandardCharsets.UTF_16LE);
			} catch (IOException | RuntimeException | Error e) {
				throw e;
			} catch (Throwable t) {
				throw new IOException("Could not call the Windows registry", t);
			}
		}

		@Override
		public void write(String name, String command) throws IOException {
			Objects.requireNonNull(command);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment data = wide(arena, command);
				int status = (int) Advapi32.SET.invokeExact(HKEY_CURRENT_USER, wide(arena, key), wide(arena, name),
					REG_SZ, data, (int) data.byteSize());
				check(status, "write");
			} catch (IOException | RuntimeException | Error e) {
				throw e;
			} catch (Throwable t) {
				throw new IOException("Could not call the Windows registry", t);
			}
		}

		@Override
		public void delete(String name) throws IOException {
			try (Arena arena = Arena.ofConfined()) {
				int status = (int) Advapi32.DELETE.invokeExact(HKEY_CURRENT_USER, wide(arena, key), wide(arena, name));
				if (status != ERROR_FILE_NOT_FOUND) check(status, "remove");
			} catch (IOException | RuntimeException | Error e) {
				throw e;
			} catch (Throwable t) {
				throw new IOException("Could not call the Windows registry", t);
			}
		}

		/** A null-terminated UTF-16 string, as Windows' wide-character calls take. */
		private static MemorySegment wide(Arena arena, String s) {
			return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
		}

		private static void check(int status, String what) throws IOException {
			if (status != ERROR_SUCCESS) {
				throw new IOException("Could not " + what + " the startup entry (Windows error " + status + ")");
			}
		}
	}
}
