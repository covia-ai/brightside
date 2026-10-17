package brightside;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Optional "keep a token for local tools": while Brightside runs, a short-lived
 * user access token kept fresh in a file, so a tool on this computer such as
 * Claude Code reads it whenever it connects and nobody has to paste a new one.
 * <b>Opt-in only</b>: anyone able to read the file can act as the user until
 * the token expires, so it carries the same trust as the remembered
 * passphrase, and relies on the OS account and filesystem permissions alike.
 * <p>
 * The file is what Claude Code's {@code headersHelper} must print, a JSON
 * object of headers: {@code {"Authorization": "Bearer <jwt>"}}.
 */
public final class ToolToken {

	static final String FILE = "tool-token.json";

	/** How long each token is valid: a stale file is soon useless. */
	public static final long LIFETIME_SECONDS = 24 * 3600;

	/** How often a running Brightside replaces it, well inside {@link #LIFETIME_SECONDS}. */
	public static final long REFRESH_SECONDS = 3600;

	private ToolToken() {
	}

	public static Path file(Path home) {
		return home.resolve(FILE);
	}

	/** The file's content for {@code jwt}: the headers a tool sends. */
	static String headers(String jwt) {
		return "{\"Authorization\": \"Bearer " + jwt + "\"}\n";
	}

	/** Replaces the file with {@code jwt}'s headers, whole: a tool reading it never sees half a token. */
	public static void write(Path home, String jwt) throws IOException {
		Files.createDirectories(home);
		Path tmp = Files.createTempFile(home, "tool-token", ".tmp");
		try {
			try {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Windows/NTFS — the file inherits the user's directory ACL.
			}
			Files.writeString(tmp, headers(jwt), StandardCharsets.UTF_8);
			Files.move(tmp, file(home), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public static void clear(Path home) {
		try {
			Files.deleteIfExists(file(home));
		} catch (IOException ignored) {
			// best effort
		}
	}
}
