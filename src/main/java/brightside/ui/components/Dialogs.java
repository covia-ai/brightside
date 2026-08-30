package brightside.ui.components;

import java.awt.Component;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JOptionPane;

/**
 * The app's modal questions, all through {@link JOptionPane} so they match the
 * platform and the theme: confirm, confirm something destructive, prompt for a
 * line, show a form, inform.
 */
public final class Dialogs {

	private Dialogs() {
	}

	/** An OK/Cancel question; true when confirmed. */
	public static boolean confirm(Component parent, String title, Object message) {
		return JOptionPane.showConfirmDialog(parent, message, title,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/** An OK/Cancel question about something that can't be undone; true when confirmed. */
	public static boolean confirmDanger(Component parent, String title, Object message) {
		return JOptionPane.showConfirmDialog(parent, message, title,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/** A form in a dialog with OK and Cancel; true when accepted. */
	public static boolean form(Component parent, String title, JComponent form) {
		return confirm(parent, title, form);
	}

	/** Asks for a line of text, pre-filled with {@code initial}; null when cancelled. */
	public static String prompt(Component parent, String title, String message, String initial) {
		Object answer = JOptionPane.showInputDialog(parent, message, title,
			JOptionPane.PLAIN_MESSAGE, null, null, initial);
		return (answer == null) ? null : answer.toString();
	}

	public static void info(Component parent, String title, Object message) {
		JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
	}

	/** Information with the app's own icon in place of the platform's. */
	public static void info(Component parent, String title, Object message, Icon icon) {
		JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE, icon);
	}

	public static void warn(Component parent, String title, Object message) {
		JOptionPane.showMessageDialog(parent, message, title, JOptionPane.WARNING_MESSAGE);
	}
}
