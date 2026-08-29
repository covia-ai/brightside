package covia.brightside.ui.chat;

import java.awt.Color;

import javax.swing.Icon;

import covia.brightside.ui.Lucide;

/**
 * The chat's small icons — a disclosure chevron and a tool success/failure
 * mark — from the app's {@link Lucide} set, so they match the navigation and
 * render regardless of the UI font (Lato has no ▸/▾/✓/✕).
 */
final class ChatIcons {

	private static final int CHEVRON = 12;
	private static final int MARK = 12;

	private ChatIcons() {
	}

	/** A disclosure chevron: points right when collapsed, down when expanded. */
	static Icon chevron(boolean expanded, Color color) {
		return Lucide.icon(expanded ? "chevron-down" : "chevron-right", CHEVRON, color);
	}

	/** A tool outcome mark: a tick when {@code ok}, otherwise a cross. */
	static Icon mark(boolean ok, Color color) {
		return Lucide.icon(ok ? "check" : "x", MARK, color);
	}
}
