package brightside;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.RememberedPassphrase;

final class RememberedPassphraseTest {

	@TempDir
	Path home;

	@Test
	void storesPlaintextUtf8AndLoadsItExactly() throws Exception {
		char[] passphrase = "correct horse 🐎 battery staple".toCharArray();

		RememberedPassphrase.store(home, passphrase);

		Path file = home.resolve("unlock.passphrase");
		assertTrue(Files.isRegularFile(file));
		assertEquals(new String(passphrase), Files.readString(file, StandardCharsets.UTF_8));
		assertArrayEquals(passphrase, RememberedPassphrase.load(home));
		assertTrue(RememberedPassphrase.exists(home));
	}

	@Test
	void clearRemovesRememberedPassphrase() throws Exception {
		Files.createDirectories(home);
		Files.writeString(home.resolve("unlock.passphrase"), "current");

		RememberedPassphrase.clear(home);

		assertFalse(Files.exists(home.resolve("unlock.passphrase")));
	}
}
