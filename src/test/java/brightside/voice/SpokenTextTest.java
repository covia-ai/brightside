package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A reply read aloud is its prose: code blocks, tables, images and raw HTML are
 * left out, a link is read by its text, and each heading, paragraph and list
 * item is a sentence; long replies come in parts that split between sentences.
 */
class SpokenTextTest {

	@Test
	void onlyTheProseIsRead() {
		String reply = """
			## Your trip

			The train leaves at **four**; see [the timetable](https://example.com/timetable).
			Run `brightside --unlock` first.

			```java
			System.out.println("not read");
			```

			| Stop | Time |
			|------|------|
			| York | 4pm  |

			- Pack a coat
			- Bring the tickets!

			![a map](map.png)

			<div>not read</div>
			""";
		assertEquals(List.of(
			"Your trip.",
			"The train leaves at four; see the timetable. Run brightside --unlock first.",
			"Pack a coat.",
			"Bring the tickets!"), SpokenText.blocks(reply));
	}

	@Test
	void aReplyOfOnlyCodeHasNothingToRead() {
		assertEquals(List.of(), SpokenText.parts("```\nls -la\n```"));
		assertEquals(List.of(), SpokenText.parts(""));
		assertEquals(List.of(), SpokenText.parts(null));
	}

	@Test
	void aLongReplyComesInPartsSplitBetweenSentences() {
		String sentence = "This sentence is about sixty characters long, more or less. ";
		List<String> parts = SpokenText.parts(sentence.repeat(40));
		assertTrue(parts.size() > 1, "more than one part");
		for (String part : parts) {
			assertTrue(part.length() <= SpokenText.PART, part.length() + " characters");
			assertTrue(part.endsWith("."), "ends on a sentence: " + part);
		}
		assertEquals(sentence.repeat(40).strip(), String.join(" ", parts), "and nothing is lost");

		String unbroken = "word ".repeat(400).strip();
		List<String> words = SpokenText.parts(unbroken);
		for (String part : words) assertTrue(part.length() <= SpokenText.PART);
		assertEquals(unbroken + ".", String.join(" ", words), "a sentence longer than a part splits between words");
	}
}
