package brightside.ui.components;

import java.awt.Cursor;
import java.util.function.Supplier;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * The app's action buttons, by role. Their looks are FlatLaf style classes
 * ({@code Button.primary}, {@code Button.tinted}, {@code Button.secondary},
 * {@code Button.link} in {@code brightside/ui/FlatLaf.properties}) and FlatLaf
 * button types, so the theme paints every state. Navigation and list rows are not buttons in this
 * sense — they are {@link PressButton}s.
 */
public final class Buttons {

	private Buttons() {
	}

	/** The one call to action on a screen — white on the accent's dark shade: Save, Continue, Send. */
	public static JButton primary(String text) {
		return hand(Styles.classes(new JButton(text), Styles.PRIMARY));
	}

	/**
	 * The button that heads a list — the accent on its own faint tint, the
	 * navigation's selected look — with an icon before its text: New
	 * conversation, New agent. Draw the icon in {@link Theme#accentText} so it
	 * matches the text.
	 */
	public static JButton tinted(String text, Icon icon) {
		JButton b = hand(Styles.classes(new JButton(text), Styles.TINTED));
		b.setIcon(icon);
		return b;
	}

	/** A quiet action beside the primary one: Back, Cancel, Copy. */
	public static JButton secondary(String text) {
		return hand(Styles.classes(new JButton(text), Styles.SECONDARY));
	}

	/** An ordinary rounded button for an everyday action. */
	public static JButton plain(String text) {
		JButton b = new JButton(text);
		b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
		return hand(b);
	}

	/** An ordinary rounded button with an icon before its text. */
	public static JButton plain(String text, Icon icon) {
		JButton b = plain(text);
		b.setIcon(icon);
		return b;
	}

	/** A small ordinary button that sits beside a value: Copy, Remove. */
	public static JButton small(String text) {
		return hand(Styles.classes(new JButton(text), Styles.SMALL));
	}

	/** An icon-only button with no chrome until hovered; the tooltip names it. */
	public static JButton icon(Icon icon, String tooltip) {
		JButton b = new JButton(icon);
		b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
		b.setToolTipText(tooltip);
		return hand(b);
	}

	/**
	 * The copy icon beside a value: takes the whole value to the clipboard, or
	 * does nothing while there is none. {@code subject} names the value in the
	 * tooltip — "the Covia DID".
	 */
	public static JButton copy(String subject, Supplier<String> value) {
		JButton b = icon(Lucide.icon("copy", 16, Theme::muted), "Copy " + subject + " to the clipboard");
		b.getAccessibleContext().setAccessibleName("Copy " + subject);
		b.addActionListener(e -> {
			String v = value.get();
			if (v != null && !v.isBlank()) Clipboard.copy(v);
		});
		return b;
	}

	/**
	 * A small link-like control in the accent that acts on the press, like the
	 * app's other navigation.
	 */
	public static PressButton link(String text, Runnable action) {
		PressButton b = new PressButton(text);
		Styles.classes(b, Styles.LINK, Styles.SMALL);
		b.onPress(action);
		return b;
	}

	/** A link that opens {@code url} in the browser. */
	public static PressButton link(String text, String url) {
		return link(text, () -> Links.open(url));
	}

	private static <B extends AbstractButton> B hand(B button) {
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}
}
