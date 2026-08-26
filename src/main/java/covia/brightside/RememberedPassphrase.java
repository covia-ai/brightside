package covia.brightside;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional "remember me" storage for the vault passphrase. <b>Opt-in only</b>,
 * with a clear warning in the UI: it stores the passphrase in the data home so
 * the vault can be unlocked without typing it on this computer.
 *
 * <p>The file is encrypted (AES-GCM) under a key derived from stable local
 * identifiers (this OS user + home directory), so it is useless if copied to
 * another machine or account. It is <em>not</em> protection against software
 * running as this user here — enabling it trades the vault's at-rest protection
 * for convenience. That is the user's explicit, warned choice, so it is off by
 * default and cleared the moment they untick it or a recovery resets the vault.
 */
public final class RememberedPassphrase {

	private static final String FILE = "unlock.enc";
	private static final int NONCE_LEN = 12;
	private static final int TAG_BITS = 128;

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

	/** Encrypts and stores {@code passphrase} in the data home. */
	public static void store(Path home, char[] passphrase) throws IOException {
		byte[] plain = toBytes(passphrase);
		try {
			byte[] nonce = new byte[NONCE_LEN];
			new SecureRandom().nextBytes(nonce);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			byte[] ct = c.doFinal(plain);
			byte[] out = new byte[nonce.length + ct.length];
			System.arraycopy(nonce, 0, out, 0, nonce.length);
			System.arraycopy(ct, 0, out, nonce.length, ct.length);
			Files.createDirectories(home);
			Files.write(home.resolve(FILE), out);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not store the remembered passphrase", e);
		} finally {
			Arrays.fill(plain, (byte) 0);
		}
	}

	/** The remembered passphrase, or null if none / unreadable (e.g. moved machine). */
	public static char[] load(Path home) {
		Path f = home.resolve(FILE);
		if (!Files.isRegularFile(f)) return null;
		try {
			byte[] in = Files.readAllBytes(f);
			if (in.length <= NONCE_LEN) return null;
			byte[] nonce = Arrays.copyOfRange(in, 0, NONCE_LEN);
			byte[] ct = Arrays.copyOfRange(in, NONCE_LEN, in.length);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			byte[] plain = c.doFinal(ct);
			char[] out = toChars(plain);
			Arrays.fill(plain, (byte) 0);
			return out;
		} catch (Exception e) {
			return null; // corrupt, tampered, or a different machine/user
		}
	}

	private static SecretKeySpec key() throws Exception {
		String material = System.getProperty("user.name", "") + '|'
			+ System.getProperty("user.home", "") + "|brightside-remember-v1";
		byte[] k = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(k, "AES");
	}

	private static byte[] toBytes(char[] chars) {
		ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] b = new byte[bb.remaining()];
		bb.get(b);
		return b;
	}

	private static char[] toChars(byte[] bytes) {
		CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
		char[] c = new char[cb.remaining()];
		cb.get(c);
		return c;
	}
}
