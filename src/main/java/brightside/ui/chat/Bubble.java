package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.text.JTextComponent;

import brightside.markdown.MarkdownPane;
import brightside.ui.components.Card;
import brightside.ui.components.Links;
import brightside.ui.components.MarkdownStyles;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Theme;

/**
 * A rounded message bubble — a {@link Card} — around a run of text: the
 * user's words {@linkplain #plain as typed}, the assistant's reply
 * {@linkplain #markdown rendered from Markdown}. The text is read-only: the
 * bubble never takes keyboard focus (so the chat input keeps it) and shows no
 * insert caret, but stays mouse-selectable with the selection painted even
 * without focus. Its width reflows to a share of the viewport, and a short
 * message gets a short bubble.
 *
 * <p>The user's side is the accent with white text; the assistant's is the
 * theme's surface in the ordinary text colour — both read from the theme when
 * painted, so a theme change carries through.
 *
 * <p>The bubble is deliberately dumb — selection tracking and the context menu
 * are wired by {@link ChatPanel} onto {@link #textComponent()}, so a bubble can
 * be reused wherever a rounded, selectable run of text is wanted.
 */
@SuppressWarnings("serial")
final class Bubble extends Card {
	private static final int ARC = 20;
	private static final int PAD_H = 14;
	private static final int PAD_V = 10;
	// The text view needs a hair more width than its measure, or it wraps the
	// last word at its own natural width — leaving a too-thin, extra-line bubble.
	private static final int WRAP_SLACK = 6;

	private final JTextComponent text;
	/** The text as a plain area, when it is one; a Markdown pane measures itself. */
	private final SelectableText plain;
	private int maxWidth = 460;

	private Bubble(JTextComponent text, SelectableText plain, boolean user) {
		super(ARC);
		this.text = text;
		this.plain = plain;
		setLayout(new BorderLayout());
		if (user) {
			fill(Theme::accent);
			text.setForeground(Color.WHITE);
		}
		text.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
		add(text, BorderLayout.CENTER);
	}

	/** The text exactly as given: the user's own words. */
	static Bubble plain(String text, boolean user) {
		SelectableText ta = new SelectableText(text).unfocusable().size(1f);
		return new Bubble(ta, ta, user);
	}

	/** Markdown rendered in the theme's type — the assistant's side; links open in the browser. */
	static Bubble markdown(String markdown) {
		MarkdownPane pane = new MarkdownPane(MarkdownStyles::current, markdown)
			.unfocusable()
			.onLink(Links::open);
		return new Bubble(pane, null, false);
	}

	/** The bubble's text component — for wiring selection tracking and a context menu. */
	JTextComponent textComponent() {
		return text;
	}

	void setAvailableWidth(int viewportWidth) {
		int w = (viewportWidth > 0) ? (int) (viewportWidth * 0.78) : 460;
		maxWidth = Math.max(200, Math.min(660, w));
		revalidate();
	}

	@Override
	public Dimension getPreferredSize() {
		int maxTextWidth = Math.max(40, maxWidth - 2 * PAD_H);
		int textWidth = Math.min(naturalTextWidth() + WRAP_SLACK, maxTextWidth);
		// The text component's border already supplies PAD_H/PAD_V. Size it to
		// the complete bubble width and return its complete preferred height;
		// adding the padding again leaves a conspicuous blank band at the bottom.
		int bubbleWidth = textWidth + 2 * PAD_H;
		text.setSize(bubbleWidth, Short.MAX_VALUE);
		return new Dimension(bubbleWidth, text.getPreferredSize().height);
	}

	/** The width the text would take unwrapped, so a short message gets a short bubble. */
	private int naturalTextWidth() {
		if (plain != null) {
			FontMetrics fm = plain.getFontMetrics(plain.getFont());
			int longest = 0;
			for (String line : plain.getText().split("\n", -1)) {
				longest = Math.max(longest, fm.stringWidth(line));
			}
			return longest;
		}
		// A styled view's preferred span is its unwrapped width, whatever size
		// it was last laid out at.
		Insets in = text.getInsets();
		return Math.max(0, text.getPreferredSize().width - in.left - in.right);
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}
}
