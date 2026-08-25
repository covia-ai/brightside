package covia.brightside.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.JSON;

/**
 * The encrypted vault: one passphrase unlocks everything Brightside keeps on
 * disk. It hardens the passphrase (Argon2id over a per-vault salt) into two
 * 32-byte keys — a <b>vault key</b> that encrypts the venue store (Etch v3,
 * ChaCha20) and a <b>seed key</b> that encrypts the identity's Ed25519 seed
 * ({@code identity.enc}) — so nothing sensitive is ever written in the clear.
 *
 * <p>The design and threat model are in {@code docs/ONBOARDING.md}. Keys live in
 * memory only; on disk are just {@code vault.salt} (not secret), the encrypted
 * {@code identity.enc}, and the encrypted {@code venue.etch}.
 */
public final class Vault {

	/** File names under the data directory. */
	public static final String SALT_FILE = "vault.salt";
	public static final String IDENTITY_FILE = "identity.enc";
	public static final String KEYS_FILE = "keys.enc";

	private static final int SALT_LEN = 16;
	private static final int KEY_LEN = 32;
	private static final int GCM_NONCE_LEN = 12;
	private static final int GCM_TAG_BITS = 128;

	// Argon2id cost. ~64 MB / 3 passes: strong for an interactive desktop unlock.
	private static final int ARGON_MEMORY_KB = 64 * 1024;
	private static final int ARGON_ITERATIONS = 3;
	private static final int ARGON_PARALLELISM = 1;

	private static final SecureRandom RNG = new SecureRandom();

	private final Path home;
	private final byte[] vaultKey;
	private final byte[] seedKey;

	private Vault(Path home, byte[] vaultKey, byte[] seedKey) {
		this.home = home;
		this.vaultKey = vaultKey;
		this.seedKey = seedKey;
	}

	/** True once a vault has been set up in {@code home} (an encrypted identity exists). */
	public static boolean exists(Path home) {
		return Files.isRegularFile(home.resolve(IDENTITY_FILE));
	}

	/**
	 * Derives the vault from {@code passphrase}, creating {@code vault.salt} if
	 * absent. Deriving succeeds for any passphrase — a wrong one is only detected
	 * when it fails to decrypt {@link #seedHex()} or to open the store.
	 */
	public static Vault open(Path home, char[] passphrase) throws IOException {
		byte[] salt = readOrCreateSalt(home);
		byte[] out = argon2id(passphrase, salt, 2 * KEY_LEN);
		try {
			return new Vault(home, Arrays.copyOfRange(out, 0, KEY_LEN), Arrays.copyOfRange(out, KEY_LEN, 2 * KEY_LEN));
		} finally {
			Arrays.fill(out, (byte) 0);
		}
	}

	/** Encrypts and stores the 32-byte Ed25519 {@code seedHex} as {@code identity.enc}. */
	public void storeSeed(String seedHex) throws IOException {
		byte[] seed = unhex(seedHex);
		try {
			writeOwnerOnly(home.resolve(IDENTITY_FILE), aesGcmEncrypt(seedKey, seed));
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	/** The identity's 32-byte Ed25519 seed (hex), decrypting {@code identity.enc}. */
	public String seedHex() throws IOException {
		byte[] enc = Files.readAllBytes(home.resolve(IDENTITY_FILE));
		byte[] seed = aesGcmDecrypt(seedKey, enc); // throws on a wrong key (GCM tag)
		try {
			return hex(seed);
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	/** The stored provider API keys (name → value), decrypting {@code keys.enc}; empty if none. */
	public Map<String, String> apiKeys() throws IOException {
		Path file = home.resolve(KEYS_FILE);
		if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
		byte[] json = aesGcmDecrypt(seedKey, Files.readAllBytes(file));
		Map<String, String> out = new LinkedHashMap<>();
		ACell parsed = JSON.parseJSON5(new String(json, StandardCharsets.UTF_8));
		if (parsed instanceof AMap<?, ?> map) {
			for (long i = 0; i < map.count(); i++) {
				var entry = map.entryAt(i);
				out.put(entry.getKey().toString(), entry.getValue().toString());
			}
		}
		return out;
	}

	/** Encrypts and stores a provider API key (merged with any existing ones). */
	public void storeApiKey(String name, String value) throws IOException {
		Map<String, String> keys = apiKeys();
		keys.put(name, value);
		AMap<AString, ACell> map = Maps.empty();
		for (Map.Entry<String, String> e : keys.entrySet()) {
			map = map.assoc(Strings.create(e.getKey()), Strings.create(e.getValue()));
		}
		byte[] json = JSON.toStringPretty(map).getBytes(StandardCharsets.UTF_8);
		writeOwnerOnly(home.resolve(KEYS_FILE), aesGcmEncrypt(seedKey, json));
	}

	/**
	 * Returns {@code venueConfig} with the identity {@code seed} and Etch v3
	 * encryption injected — the venue then runs under this exact key and stores
	 * everything encrypted with the vault key. Neither value is ever persisted;
	 * they live only in the in-memory config handed to the venue.
	 */
	public AMap<AString, ACell> secure(AMap<AString, ACell> venueConfig, String seedHex) {
		AMap<AString, ACell> etch = Maps.of(
			"version", 3L,
			"cipher", "chacha20",
			"key", hex(vaultKey));
		return venueConfig
			.assoc(Strings.create("seed"), Strings.create(seedHex))
			.assoc(Strings.create("etch"), etch);
	}

	// ------------------------------------------------------------------
	// Argon2id
	// ------------------------------------------------------------------

	private static byte[] argon2id(char[] passphrase, byte[] salt, int length) {
		Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
			.withVersion(Argon2Parameters.ARGON2_VERSION_13)
			.withSalt(salt)
			.withMemoryAsKB(ARGON_MEMORY_KB)
			.withIterations(ARGON_ITERATIONS)
			.withParallelism(ARGON_PARALLELISM)
			.build();
		Argon2BytesGenerator generator = new Argon2BytesGenerator();
		generator.init(params);
		byte[] out = new byte[length];
		generator.generateBytes(passphrase, out);
		return out;
	}

	// ------------------------------------------------------------------
	// AES-GCM (nonce ‖ ciphertext+tag)
	// ------------------------------------------------------------------

	private static byte[] aesGcmEncrypt(byte[] key, byte[] plaintext) {
		try {
			byte[] nonce = new byte[GCM_NONCE_LEN];
			RNG.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
			byte[] ct = cipher.doFinal(plaintext);
			byte[] out = new byte[nonce.length + ct.length];
			System.arraycopy(nonce, 0, out, 0, nonce.length);
			System.arraycopy(ct, 0, out, nonce.length, ct.length);
			return out;
		} catch (Exception e) {
			throw new IllegalStateException("vault encryption failed", e);
		}
	}

	private static byte[] aesGcmDecrypt(byte[] key, byte[] envelope) throws IOException {
		if (envelope.length <= GCM_NONCE_LEN) throw new IOException("identity.enc is truncated");
		try {
			byte[] nonce = Arrays.copyOfRange(envelope, 0, GCM_NONCE_LEN);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return cipher.doFinal(envelope, GCM_NONCE_LEN, envelope.length - GCM_NONCE_LEN);
		} catch (Exception e) {
			// A wrong passphrase surfaces here as a GCM tag failure.
			throw new IOException("could not decrypt the identity (wrong passphrase?)", e);
		}
	}

	// ------------------------------------------------------------------
	// Files & hex
	// ------------------------------------------------------------------

	private static byte[] readOrCreateSalt(Path home) throws IOException {
		Path saltFile = home.resolve(SALT_FILE);
		if (Files.isRegularFile(saltFile)) {
			byte[] salt = Files.readAllBytes(saltFile);
			if (salt.length == SALT_LEN) return salt;
		}
		byte[] salt = new byte[SALT_LEN];
		RNG.nextBytes(salt);
		Files.createDirectories(home);
		Files.write(saltFile, salt);
		return salt;
	}

	/** Writes owner-only where the filesystem supports POSIX perms; best-effort elsewhere. */
	private static void writeOwnerOnly(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException ignored) {
			// Windows/NTFS — no POSIX perms; the file inherits the user's ACL.
		}
	}

	static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return sb.toString();
	}

	static byte[] unhex(String hex) {
		int n = hex.length() / 2;
		byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
		}
		return out;
	}
}
