package covia.brightside.ui.chat;

import java.awt.Color;

import javax.swing.UIManager;

/**
 * Shared colours and a small text helper for the chat components. Kept in one
 * place so the bubble, the activity chip and the panel all derive their
 * surfaces and muted text from the same theme-driven values.
 */
final class ChatStyle {

	/** A failed tool/step. */
	static final Color ERROR = new Color(0xE5, 0x53, 0x53);
	/** A successful tool/step. */
	static final Color OK = new Color(0x3F, 0xB9, 0x50);

	private ChatStyle() {
	}

	static Color foreground() {
		Color c = UIManager.getColor("Label.foreground");
		return (c != null) ? c : Color.WHITE;
	}

	static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	/** Elevated surface for the assistant's bubbles, derived from the theme. */
	static Color assistantBg() {
		Color base = UIManager.getColor("Panel.background");
		if (base == null) base = new Color(0x2B, 0x2B, 0x2B);
		boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 128;
		return dark ? mix(base, Color.WHITE, 0.12f) : mix(base, Color.BLACK, 0.06f);
	}

	static Color mix(Color a, Color b, float t) {
		return new Color(
			Math.round(a.getRed() * (1 - t) + b.getRed() * t),
			Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
			Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
	}

	static String truncate(String s, int max) {
		return (s.length() <= max) ? s : s.substring(0, max) + "…";
	}
}
