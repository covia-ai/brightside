package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The fold behind a {@link Readout} excerpt: what is shown while folded. */
class ReadoutTest {

	@Test
	void textThatFitsIsNotClamped() {
		assertNull(Readout.clamp("one\ntwo\nthree", 3, 100));
		assertNull(Readout.clamp("", 3, 100));
	}

	@Test
	void clampsToTheLineCount() {
		assertEquals("one\ntwo", Readout.clamp("one\ntwo\nthree\nfour", 2, 100));
	}

	@Test
	void clampsToTheCharacterCountAtASpace() {
		String clamped = Readout.clamp("the quick brown fox jumps over the lazy dog", 5, 20);
		assertEquals("the quick brown fox", clamped);
		assertTrue(clamped.length() <= 20);
	}

	@Test
	void cutsMidWordOnlyWhenNoSpaceFallsInTheSecondHalf() {
		assertEquals("abcdefghij", Readout.clamp("abcdefghijklmnopqrstuvwxyz", 5, 10));
		assertEquals("ab cdefghij", Readout.clamp("ab cdefghijklmnop", 5, 11),
			"a space in the first half is not a good cut");
	}

	@Test
	void aLineClampThatIsStillTooLongIsAlsoCut() {
		String text = "x".repeat(50) + "\n" + "y".repeat(50) + "\nz";
		String clamped = Readout.clamp(text, 2, 60);
		assertEquals(60, clamped.length());
		assertTrue(clamped.startsWith("x".repeat(50) + "\n"));
	}
}
