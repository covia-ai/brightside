package brightside;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/**
 * Optional plaintext "remember me" storage for the vault passphrase.
 * <b>Opt-in only</b>, with a clear warning in the UI: anyone able to read this
 * file can unlock the vault. This deliberately relies on the trusted computer's
 * OS account and filesystem permissions instead of pretending that locally
 * reproducible key material adds a security boundary.
 */
public final class RememberedPassphrase {

	private static final String FILE = "unlock.passphrase";

	private RememberedPassphrase() {
	}

	public static boolean exists(Path home) {
		return Files.isRegularFile(home.resolve(FILE));
	}

	public static void clear(Path home) {
		try {
			Files.deleteIfExists(home.resolve(FILE));
		} catch (IOException ignored) {
			// best effort
		}
	}

	/** Stores {@code passphrase} as UTF-8 plaintext in the data home. */
	public static void store(Path home, char[] passphrase) throws IOException {
		byte[] plain = toBytes(passphrase);
		try {
			Files.createDirectories(home);
			Path file = home.resolve(FILE);
			Files.write(file, plain);
			try {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Windows/NTFS — the file inherits the user's directory ACL.
			}
		} finally {
			Arrays.fill(plain, (byte) 0);
		}
	}

	/** The remembered passphrase, or null if none, unreadable or not valid UTF-8. */
	public static char[] load(Path home) {
		Path f = home.resolve(FILE);
		if (!Files.isRegularFile(f)) return null;
		try {
			byte[] plain = Files.readAllBytes(f);
			try {
				return toChars(plain);
			} finally {
				Arrays.fill(plain, (byte) 0);
			}
		} catch (IOException e) {
			return null;
		}
	}

	private static byte[] toBytes(char[] chars) {
		ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] b = new byte[bb.remaining()];
		bb.get(b);
		return b;
	}

	private static char[] toChars(byte[] bytes) throws CharacterCodingException {
		CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes));
		char[] c = new char[cb.remaining()];
		cb.get(c);
		return c;
	}
}
