package brightside.markdown;

import java.awt.Color;
import java.awt.Font;
import java.util.Objects;

/**
 * How rendered Markdown looks: the two typefaces, the colours and the spacing.
 * Immutable; a host builds one from its own theme and builds another when the
 * theme changes. {@link #defaults()} is a plain, readable starting point.
 *
 * @param font           body type; headings derive from it
 * @param monoFont       inline code, code blocks and tables
 * @param foreground     body text
 * @param muted          block quotes and rules
 * @param link           link text, which is underlined as well
 * @param codeBackground painted behind code, inline and in blocks
 * @param blockGap       vertical space between blocks, in pixels
 * @param listIndent     indent per list level, in pixels
 * @param quoteIndent    indent per block-quote level, in pixels
 * @param headingScale   font-size multipliers for h1 to h6, in that order
 */
public record MarkdownStyle(Font font, Font monoFont, Color foreground, Color muted, Color link,
		Color codeBackground, int blockGap, int listIndent, int quoteIndent, float[] headingScale) {

	public MarkdownStyle {
		Objects.requireNonNull(font, "font");
		Objects.requireNonNull(monoFont, "monoFont");
		Objects.requireNonNull(foreground, "foreground");
		Objects.requireNonNull(muted, "muted");
		Objects.requireNonNull(link, "link");
		Objects.requireNonNull(codeBackground, "codeBackground");
		if (headingScale == null || headingScale.length != 6) {
			throw new IllegalArgumentException("headingScale needs six entries, h1 to h6");
		}
		headingScale = headingScale.clone();
	}

	/** Dark text on a light ground in the JDK's own faces — no theme required. */
	public static MarkdownStyle defaults() {
		return new MarkdownStyle(
			new Font(Font.DIALOG, Font.PLAIN, 14),
			new Font(Font.MONOSPACED, Font.PLAIN, 13),
			new Color(0x1E1E1E), new Color(0x707070), new Color(0x2F6FDE), new Color(0xEDEDED),
			8, 22, 18,
			new float[] {1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f});
	}

	/** The body size scaled for a heading of {@code level} (1 to 6). */
	public float headingSize(int level) {
		int i = Math.min(6, Math.max(1, level)) - 1;
		return font.getSize2D() * headingScale[i];
	}

	@Override
	public float[] headingScale() {
		return headingScale.clone();
	}
}
