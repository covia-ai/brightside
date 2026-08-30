package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.DefaultCaret;

import com.formdev.flatlaf.util.UIScale;

/**
 * A wrapping run of read-only text that can be selected and copied — for
 * anything the reader might want to take away: values, explanations, results,
 * warnings. (A {@code JLabel} cannot be selected.) Transparent and borderless,
 * so it reads as text, not as an input.
 *
 * <p>Fluent modifiers choose the type ({@link #small}, {@link #bold},
 * {@link #italic}, {@link #mono}) and tone ({@link #muted}, {@link #tone}). By
 * default it can take keyboard focus, so native copy works in a dialog; inside
 * the chat, where the composer must keep focus, {@link #unfocusable} hides the
 * caret and drops it from the focus cycle while still painting the selection.
 * {@link #wrapAt} fixes the width for a vertical stack.
 */
@SuppressWarnings("serial")
public class SelectableText extends JTextArea {

	private boolean small;
	private boolean bold;
	private boolean italic;
	private boolean mono;
	private float sizeDelta;
	private int wrapWidth;
	private boolean built;

	public SelectableText(String text) {
		super(text);
		setEditable(false);
		setLineWrap(true);
		setWrapStyleWord(true);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder());
		setAlignmentX(Component.LEFT_ALIGNMENT);
		built = true;
		applyFont();
	}

	/** A muted, wrapping explanation. */
	public static SelectableText description(String text) {
		return new SelectableText(text).muted();
	}

	/** An opaque value — a key, a DID, a token — in the monospaced face. */
	public static SelectableText technical(String text) {
		return new SelectableText(text).mono();
	}

	public SelectableText muted() {
		return tone(Styles.MUTED);
	}

	/** A tone style class: {@link Styles#ERROR}, {@link Styles#WARNING}, … */
	public SelectableText tone(String styleClass) {
		Styles.add(this, styleClass);
		return this;
	}

	/** A colour chosen at runtime (a state that switches); prefer {@link #tone} when it doesn't. */
	public SelectableText colour(Color colour) {
		setForeground(colour);
		return this;
	}

	public SelectableText small() {
		small = true;
		applyFont();
		return this;
	}

	public SelectableText bold() {
		bold = true;
		applyFont();
		return this;
	}

	public SelectableText italic() {
		italic = true;
		applyFont();
		return this;
	}

	public SelectableText mono() {
		mono = true;
		applyFont();
		return this;
	}

	/** A size step off the standard type, in points, like FlatLaf's own {@code +1}. */
	public SelectableText size(float delta) {
		sizeDelta = delta;
		applyFont();
		return this;
	}

	/** Lines are kept whole (for structured text such as JSON); pair with a scrolling pane. */
	public SelectableText noWrap() {
		setLineWrap(false);
		return this;
	}

	/** Never takes keyboard focus and shows no caret, but paints its selection. */
	public SelectableText unfocusable() {
		setFocusable(false);
		DefaultCaret caret = new DefaultCaret() {
			@Override
			public void setVisible(boolean visible) {
				super.setVisible(false);
			}

			@Override
			public void setSelectionVisible(boolean visible) {
				super.setSelectionVisible(true);
			}
		};
		caret.setBlinkRate(0);
		setCaret(caret);
		caret.setSelectionVisible(true);
		return this;
	}

	/** Wraps at a fixed width and reports the resulting height, so a vertical stack gives it exactly the room it needs. */
	public SelectableText wrapAt(int width) {
		wrapWidth = width;
		revalidate();
		return this;
	}

	@Override
	public Dimension getPreferredSize() {
		if (wrapWidth <= 0) return super.getPreferredSize();
		setSize(wrapWidth, Short.MAX_VALUE);
		return new Dimension(wrapWidth, super.getPreferredSize().height);
	}

	@Override
	public Dimension getMaximumSize() {
		return (wrapWidth > 0) ? getPreferredSize() : super.getMaximumSize();
	}

	@Override
	public void updateUI() {
		super.updateUI();
		if (built) applyFont();
	}

	/** The face from the theme's own font keys, so it tracks the default font and its scaling. */
	private void applyFont() {
		Font f = UIManager.getFont(mono ? "monospaced.font" : "Label.font");
		if (f == null) {
			Font base = UIManager.getFont("Label.font");
			float size = (base != null) ? base.getSize2D() : UIScale.scale(13f);
			f = new Font(mono ? Font.MONOSPACED : Font.DIALOG, Font.PLAIN, Math.round(size));
		}
		if (small) {
			Font s = UIManager.getFont("small.font");
			f = f.deriveFont((s != null) ? s.getSize2D() : f.getSize2D() - UIScale.scale(2f));
		}
		int style = (bold ? Font.BOLD : Font.PLAIN) | (italic ? Font.ITALIC : Font.PLAIN);
		if (sizeDelta != 0) f = f.deriveFont(f.getSize2D() + sizeDelta);
		setFont(style == Font.PLAIN ? f : f.deriveFont(style));
	}
}
