package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rule {@link KeyTest} breaks a long answer by (from functional checks
 * F-14 and F-15, where a hint ran past the row's edge): no line wider than the
 * room unless it is one word, no word lost or reordered, and text that fits
 * stays on one line. Measured headless with a real font.
 */
class KeyTestLinesTest {

	private static final FontMetrics FM = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
		.createGraphics().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

	private static final String HINT = "Anthropic keys usually start sk-ant-. This one doesn't, so it may be from "
		+ "another provider (OpenAI and DeepSeek keys start sk-).";

	@Test
	void noLineIsWiderThanTheRoom() {
		for (int room : new int[] { 120, 200, 300, 480, 640 }) {
			for (String line : KeyTest.lines(HINT, FM, room)) {
				assertTrue(FM.stringWidth(line) <= room || !line.contains(" "), room + ": " + line);
			}
		}
	}

	@Test
	void everyWordIsKeptInOrder() {
		for (int room : new int[] { 120, 300, 640 }) {
			assertEquals(HINT, String.join(" ", KeyTest.lines(HINT, FM, room)), "room " + room);
		}
	}

	@Test
	void textThatFitsStaysOnOneLine() {
		assertEquals(List.of("Works: Claude Opus 5.5 replied in 1.4 s."),
			KeyTest.lines("Works: Claude Opus 5.5 replied in 1.4 s.", FM, 2000));
	}

	@Test
	void anOverlongWordStandsAlone() {
		List<String> lines = KeyTest.lines("see console.anthropic.com/settings/keys/very/long/path now", FM, 60);
		assertTrue(lines.contains("console.anthropic.com/settings/keys/very/long/path"), lines.toString());
		assertEquals("see", lines.get(0));
	}

	@Test
	void narrowerRoomMeansMoreLines() {
		assertTrue(KeyTest.lines(HINT, FM, 200).size() > KeyTest.lines(HINT, FM, 480).size());
	}
}
