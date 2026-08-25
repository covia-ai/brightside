package covia.brightside.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MnemonicTest {

	// The classic all-zero-entropy BIP39 vector.
	private static final String VECTOR =
		"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

	@Test
	void generatesValidTwelveWordPhrases() {
		String phrase = Mnemonic.generate(12);
		assertEquals(12, phrase.split(" ").length);
		assertTrue(Mnemonic.isValid(phrase));
	}

	@Test
	void derivesAStable32ByteSeed() {
		assertTrue(Mnemonic.isValid(VECTOR));
		String seed = Mnemonic.toSeedHex(VECTOR);
		assertEquals(64, seed.length(), "32-byte Ed25519 seed as hex");
		assertEquals(seed, Mnemonic.toSeedHex(VECTOR), "deterministic");
		// Case/spacing normalisation gives the same seed.
		assertEquals(seed, Mnemonic.toSeedHex("  ABANDON   abandon abandon abandon abandon abandon "
			+ "abandon abandon abandon abandon abandon ABOUT "));
	}

	@Test
	void rejectsInvalidPhrases() {
		assertFalse(Mnemonic.isValid("not a real recovery phrase at all"));
		assertNotNull(Mnemonic.checkReason("not a real recovery phrase at all"));
		assertThrows(IllegalArgumentException.class, () -> Mnemonic.toSeedHex("nonsense words here please"));
	}
}
