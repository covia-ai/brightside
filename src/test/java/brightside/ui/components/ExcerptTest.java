package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The clamp behind {@link Excerpt}: what is shown while folded. */
class ExcerptTest {

	@Test
	void textThatFitsIsNotClamped() {
		assertNull(Excerpt.clamp("one\ntwo\nthree", 3, 100));
		assertNull(Excerpt.clamp("", 3, 100));
	}

	@Test
	void clampsToTheLineCount() {
		assertEquals("one\ntwo", Excerpt.clamp("one\ntwo\nthree\nfour", 2, 100));
	}

	@Test
	void clampsToTheCharacterCountAtASpace() {
		String clamped = Excerpt.clamp("the quick brown fox jumps over the lazy dog", 5, 20);
		assertEquals("the quick brown fox", clamped);
		assertTrue(clamped.length() <= 20);
	}

	@Test
	void cutsMidWordOnlyWhenNoSpaceFallsInTheSecondHalf() {
		assertEquals("abcdefghij", Excerpt.clamp("abcdefghijklmnopqrstuvwxyz", 5, 10));
		assertEquals("ab cdefghij", Excerpt.clamp("ab cdefghijklmnop", 5, 11),
			"a space in the first half is not a good cut");
	}

	@Test
	void aLineClampThatIsStillTooLongIsAlsoCut() {
		String text = "x".repeat(50) + "\n" + "y".repeat(50) + "\nz";
		String clamped = Excerpt.clamp(text, 2, 60);
		assertEquals(60, clamped.length());
		assertTrue(clamped.startsWith("x".repeat(50) + "\n"));
	}
}
