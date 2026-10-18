package brightside;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import brightside.files.TextPreview;

class TextPreviewTest {

	@Test void previewsAreBoundedEvenWithoutAFileSize() throws Exception {
		int[] read = { 0 };
		InputStream endless = new InputStream() {
			@Override public int read() { read[0]++; return 'a'; }
		};
		var result = TextPreview.read(endless);
		assertTrue(result.truncated());
		assertFalse(result.binary());
		assertEquals(TextPreview.LIMIT, result.text().length());
		assertEquals(TextPreview.LIMIT + 1, read[0]);
	}

	@Test void distinguishesTextBinaryAndIncompleteUtf8AtTheLimit() throws Exception {
		assertFalse(TextPreview.read(new ByteArrayInputStream(new byte[0])).binary());
		assertTrue(TextPreview.read(new ByteArrayInputStream(new byte[] { 0, 1, 2 })).binary());
		assertTrue(TextPreview.read(new ByteArrayInputStream(new byte[] { (byte) 0xff })).binary());
		String text = "a".repeat(TextPreview.LIMIT - 1) + "£more";
		var result = TextPreview.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
		assertFalse(result.binary());
		assertTrue(result.truncated());
		assertEquals("a".repeat(TextPreview.LIMIT - 1), result.text());
		assertEquals("a\n£\t", TextPreview.read(new ByteArrayInputStream("a\n£\t".getBytes(StandardCharsets.UTF_8))).text());
	}
}
