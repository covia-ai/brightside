package brightside.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
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
import convex.etch.EtchStore;
import covia.venue.Config;

/**
 * The encrypted vault. Two keys protect what Brightside keeps on disk, and they
 * come from <em>different</em> roots on purpose:
 *
 * <ul>
 * <li>The <b>store key</b> (the Etch v3 / ChaCha20 key for {@code venue.etch}) is
 * derived from the <b>identity seed</b> — so it is reproducible from the BIP39
 * recovery phrase. Recovery therefore restores full access to the existing store,
 * not just the identity.</li>
 * <li>The <b>passphrase key</b> (Argon2id over a per-vault salt) protects only the
 * seed itself ({@code identity.enc}) and the provider API keys ({@code keys.enc}).</li>
 * </ul>
 *
 * <p>Unlocking is <em>passphrase → seed → store key → open store</em>; recovery is
 * <em>recovery phrase → seed → store key → open the same store</em>, then the seed
 * is re-encrypted under a new passphrase. The passphrase can be changed (or
 * recovered) without re-encrypting the store, because the store key never depended
 * on it. Design and threat model: {@code docs/ONBOARDING.md}.
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

	// Domain-separation label so the store key is distinct from the raw seed.
	private static final byte[] ETCH_LABEL = "brightside-etch-v1".getBytes(StandardCharsets.UTF_8);

	// Argon2id cost. ~64 MB / 3 passes: strong for an interactive desktop unlock.
	private static final int ARGON_MEMORY_KB = 64 * 1024;
	private static final int ARGON_ITERATIONS = 3;
	private static final int ARGON_PARALLELISM = 1;

	private static final SecureRandom RNG = new SecureRandom();

	private final Path home;
	private final byte[] passKey; // Argon2id(passphrase, salt): protects identity.enc and keys.enc

	private Vault(Path home, byte[] passKey) {
		this.home = home;
		this.passKey = passKey;
	}

	/** True once a vault has been set up in {@code home} (an encrypted identity exists). */
	public static boolean exists(Path home) {
		return Files.isRegularFile(home.resolve(IDENTITY_FILE));
	}

	/**
	 * Derives the passphrase key, creating {@code vault.salt} if absent. Deriving
	 * succeeds for any passphrase — a wrong one is only detected when it fails to
	 * decrypt {@link #seedHex()}.
	 */
	public static Vault open(Path home, char[] passphrase) throws IOException {
		byte[] salt = readOrCreateSalt(home);
		return new Vault(home, argon2id(passphrase, salt, KEY_LEN));
	}

	/** Encrypts and stores the 32-byte Ed25519 {@code seedHex} as {@code identity.enc}. */
	public void storeSeed(String seedHex) throws IOException {
		byte[] seed = unhex(seedHex);
		try {
			writeOwnerOnly(home.resolve(IDENTITY_FILE), aesGcmEncrypt(passKey, seed));
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
	}

	/** The identity's 32-byte Ed25519 seed (hex), decrypting {@code identity.enc}. */
	public String seedHex() throws IOException {
		byte[] enc = Files.readAllBytes(home.resolve(IDENTITY_FILE));
		byte[] seed = aesGcmDecrypt(passKey, enc); // throws on a wrong passphrase (GCM tag)
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
		byte[] json = aesGcmDecrypt(passKey, Files.readAllBytes(file));
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
		writeKeys(keys);
	}

	/** Forgets a stored key; nothing happens if there is none of that name. */
	public void removeApiKey(String name) throws IOException {
		Map<String, String> keys = apiKeys();
		if (keys.remove(name) == null) return;
		writeKeys(keys);
	}

	private void writeKeys(Map<String, String> keys) throws IOException {
		AMap<AString, ACell> map = Maps.empty();
		for (Map.Entry<String, String> e : keys.entrySet()) {
			map = map.assoc(Strings.create(e.getKey()), Strings.create(e.getValue()));
		}
		byte[] json = JSON.toStringPretty(map).getBytes(StandardCharsets.UTF_8);
		writeOwnerOnly(home.resolve(KEYS_FILE), aesGcmEncrypt(passKey, json));
	}

	/**
	 * Returns {@code venueConfig} with the identity {@code seed} and Etch v3
	 * encryption injected. The Etch key is derived from the <b>seed</b> (not the
	 * passphrase), so the same seed always opens the same store — that is what lets
	 * recovery reopen it. Neither value is ever persisted; they live only in the
	 * in-memory config handed to the venue.
	 */
	public AMap<AString, ACell> secure(AMap<AString, ACell> venueConfig, String seedHex) {
		byte[] seed = unhex(seedHex);
		String etchKeyHex;
		try {
			etchKeyHex = hex(deriveKey(seed, ETCH_LABEL));
		} finally {
			Arrays.fill(seed, (byte) 0);
		}
		AMap<AString, ACell> etch = Maps.of(
			"version", 3L,
			"cipher", "chacha20",
			"key", etchKeyHex);
		return venueConfig
			.assoc(Strings.create("seed"), Strings.create(seedHex))
			.assoc(Strings.create("etch"), etch);
	}

	/**
	 * Verifies that {@code seedHex} can open an existing persistent store without
	 * changing any vault credential files. Recovery uses this before replacing
	 * {@code identity.enc}; a mistyped but valid recovery phrase must not destroy
	 * the last usable encrypted identity.
	 */
	public void verifyStoreAccess(AMap<AString, ACell> venueConfig, String seedHex) throws IOException {
		Config secured = new Config(secure(venueConfig, seedHex));
		String store = secured.getStore();
		if ("temp".equals(store) || "memory".equals(store)) return;
		Path storeFile = Path.of(store);
		if (!Files.isRegularFile(storeFile)) return;
		try (EtchStore ignored = EtchStore.create(storeFile.toFile(), secured.getEtchConfig())) {
			// Opening the authenticated Etch v3 header is the verification.
		} catch (IOException | RuntimeException e) {
			throw new IOException("The recovery phrase does not unlock the encrypted store", e);
		}
	}

	// ------------------------------------------------------------------
	// Key derivation
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

	/** A 32-byte key derived from the high-entropy seed with domain separation. */
	private static byte[] deriveKey(byte[] seed, byte[] label) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(label);
			md.update(seed);
			return md.digest();
		} catch (Exception e) {
			throw new IllegalStateException("key derivation failed", e);
		}
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
		if (envelope.length <= GCM_NONCE_LEN) throw new IOException("encrypted file is truncated");
		try {
			byte[] nonce = Arrays.copyOfRange(envelope, 0, GCM_NONCE_LEN);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return cipher.doFinal(envelope, GCM_NONCE_LEN, envelope.length - GCM_NONCE_LEN);
		} catch (Exception e) {
			// A wrong passphrase surfaces here as a GCM tag failure.
			throw new IOException("could not decrypt (wrong passphrase?)", e);
		}
	}

	// ------------------------------------------------------------------
	// Files & hex
	// ------------------------------------------------------------------

	/**
	 * Reads the vault salt, creating it only when there is none. An existing
	 * salt file is never overwritten: the vault key and seed key are derived
	 * from it, so replacing it would silently make {@code identity.enc} and
	 * {@code venue.etch} undecryptable even with the right passphrase. A
	 * malformed salt is therefore an error, not a reason to start afresh.
	 */
	private static byte[] readOrCreateSalt(Path home) throws IOException {
		Path saltFile = home.resolve(SALT_FILE);
		if (Files.exists(saltFile)) {
			byte[] salt = Files.readAllBytes(saltFile);
			if (salt.length != SALT_LEN) {
				throw new IOException(SALT_FILE + " is malformed (" + salt.length + " bytes, expected "
					+ SALT_LEN + ") — not overwriting it; restore it from a backup");
			}
			return salt;
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
