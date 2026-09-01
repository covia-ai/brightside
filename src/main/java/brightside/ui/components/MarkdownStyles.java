package brightside.ui.components;

import java.awt.Font;

import javax.swing.UIManager;

import com.formdev.flatlaf.util.UIScale;

import brightside.markdown.MarkdownStyle;

/**
 * Rendered Markdown in the current theme: the theme's own faces and colours,
 * read afresh on every call so a {@code MarkdownPane} given
 * {@code MarkdownStyles::current} follows a theme change.
 */
public final class MarkdownStyles {

	private MarkdownStyles() {
	}

	/** Running text at the chat's size — a step above the UI font, as bubbles are. */
	public static MarkdownStyle current() {
		Font body = UIManager.getFont("Label.font");
		if (body == null) body = new Font(Font.DIALOG, Font.PLAIN, Math.round(UIScale.scale(13f)));
		body = body.deriveFont(Font.PLAIN, body.getSize2D() + 1f);
		Font mono = UIManager.getFont("monospaced.font");
		mono = (mono != null)
			? mono.deriveFont(Font.PLAIN, body.getSize2D() - 1f)
			: new Font(Font.MONOSPACED, Font.PLAIN, Math.round(body.getSize2D() - 1f));
		return new MarkdownStyle(body, mono,
			Theme.foreground(), Theme.muted(), Theme.accent(), Theme.codeBackground(),
			UIScale.scale(8), UIScale.scale(22), UIScale.scale(16),
			new float[] {1.45f, 1.25f, 1.12f, 1.04f, 1f, 1f});
	}
}
