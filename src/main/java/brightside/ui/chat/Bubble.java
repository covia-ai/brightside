package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.text.JTextComponent;

import covia.gui.components.TextComponents;
import covia.gui.markdown.MarkdownPane;
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
 * <p>The user's side is the accent {@linkplain Theme#accentDark darkened until
 * white text reads on it}; the assistant's is the theme's surface in the
 * ordinary text colour — both read from the theme when painted, so a theme
 * change carries through.
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
	private final JScrollPane markdownScroll;
	private int maxWidth = 460;

	private Bubble(JTextComponent text, boolean user) {
		super(ARC);
		this.text = text;
		setLayout(new BorderLayout());
		if (user) {
			fill(Theme::accentDark);
			text.setForeground(Color.WHITE);
		}
		text.setBorder(BorderFactory.createEmptyBorder(PAD_V, PAD_H, PAD_V, PAD_H));
		if (text instanceof MarkdownPane) {
			markdownScroll = new JScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			markdownScroll.setBorder(BorderFactory.createEmptyBorder());
			markdownScroll.setOpaque(false);
			markdownScroll.getViewport().setOpaque(false);
			markdownScroll.getHorizontalScrollBar().setUnitIncrement(16);
			add(markdownScroll, BorderLayout.CENTER);
		} else {
			markdownScroll = null;
			add(text, BorderLayout.CENTER);
		}
	}

	/** The text exactly as given: the user's own words. */
	static Bubble plain(String text, boolean user) {
		SelectableText ta = new SelectableText(text).unfocusable().size(1f);
		return new Bubble(ta, user);
	}

	/**
	 * Markdown rendered in the theme's type — the assistant's side; links open
	 * in the browser. A transcript holds many, mostly out of view, so each
	 * renders again (for a theme change or new text) only while it is showing.
	 */
	static Bubble markdown(String markdown) {
		MarkdownPane pane = new MarkdownPane(MarkdownStyles::current, markdown)
			.renderWhenShowing()
			.unfocusable()
			.onLink(Links::open);
		pane.setOpaque(false);
		return new Bubble(pane, false);
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
		var insets = text.getInsets();
		int naturalWidth = TextComponents.naturalWidth(text) - insets.left - insets.right;
		int textWidth = Math.min(naturalWidth + WRAP_SLACK, maxTextWidth);
		// The text component's border already supplies PAD_H/PAD_V. Size it to
		// the complete bubble width and return its complete preferred height;
		// adding the padding again leaves a conspicuous blank band at the bottom.
		int bubbleWidth = textWidth + 2 * PAD_H;
		int viewWidth = markdownScroll == null ? bubbleWidth : Math.max(bubbleWidth, text.getMinimumSize().width);
		int height = TextComponents.sizeForWidth(text, viewWidth).height;
		if (markdownScroll != null && viewWidth > bubbleWidth) height += markdownScroll.getHorizontalScrollBar().getPreferredSize().height;
		return new Dimension(bubbleWidth, height);
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}
}
