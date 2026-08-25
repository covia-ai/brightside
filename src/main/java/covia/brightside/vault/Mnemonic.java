package covia.brightside.vault;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.SLIP10;
import convex.core.data.Blob;

/**
 * The identity's BIP39 recovery phrase — the offline backup of the venue's
 * Ed25519 key. A thin, friendly wrapper over Convex's {@link BIP39}/{@link SLIP10}
 * so the onboarding UI can generate, validate and derive without touching the
 * crypto directly. The phrase reconstructs the same 32-byte Ed25519 seed the
 * venue runs under, independent of the vault passphrase.
 */
public final class Mnemonic {

	public static final int DEFAULT_WORDS = 12;

	private Mnemonic() {
	}

	/** A fresh, cryptographically-random phrase ({@code words} must be a multiple of 3). */
	public static String generate(int words) {
		return BIP39.createSecureMnemonic(words);
	}

	/** True if {@code phrase} is a valid BIP39 mnemonic (words + checksum). */
	public static boolean isValid(String phrase) {
		return BIP39.checkMnemonic(normalise(phrase)) == null;
	}

	/** Null if {@code phrase} is valid, otherwise a human-readable reason. */
	public static String checkReason(String phrase) {
		return BIP39.checkMnemonic(normalise(phrase));
	}

	/**
	 * Derives the 32-byte Ed25519 seed (hex) the venue runs under from a phrase —
	 * BIP39 seed (PBKDF2-HMAC-SHA512) then the SLIP-10 ed25519 master. Matches
	 * what the venue writes for its own key.
	 *
	 * @throws IllegalArgumentException if the phrase is not valid
	 */
	public static String toSeedHex(String phrase) {
		String reason = checkReason(phrase);
		if (reason != null) throw new IllegalArgumentException("invalid recovery phrase: " + reason);
		Blob seed64 = BIP39.getSeed(normalise(phrase), "");
		AKeyPair keyPair = SLIP10.deriveKeyPair(seed64);
		return keyPair.getSeed().toHexString();
	}

	/** Lower-cased, single-spaced, trimmed — how BIP39 wants the words. */
	static String normalise(String phrase) {
		return (phrase == null) ? "" : phrase.strip().replaceAll("\\s+", " ").toLowerCase();
	}
}
