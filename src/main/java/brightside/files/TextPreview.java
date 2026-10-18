package brightside.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

/** Bounded, literal UTF-8 previews; never executes or renders file markup. */
public final class TextPreview {

	public static final int LIMIT = 64 * 1024;

	private TextPreview() {}

	public static FileSource.Preview read(InputStream stream) throws IOException {
		byte[] bytes = stream.readNBytes(LIMIT + 1);
		boolean truncated = bytes.length > LIMIT;
		int length = Math.min(bytes.length, LIMIT);
		for (int i = 0; i < length; i++) {
			int b = bytes[i] & 255;
			if (b < 32 && b != 9 && b != 10 && b != 13) return new FileSource.Preview("", truncated, true);
		}
		CharBuffer chars = CharBuffer.allocate(length);
		var result = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length), chars, !truncated);
		if (result.isError()) return new FileSource.Preview("", truncated, true);
		chars.flip();
		return new FileSource.Preview(chars.toString(), truncated, false);
	}
}
