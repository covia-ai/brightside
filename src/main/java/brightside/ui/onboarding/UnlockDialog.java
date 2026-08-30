package brightside.ui.onboarding;

import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;

import javax.swing.JDialog;
import javax.swing.WindowConstants;

import brightside.BrightSide;
import brightside.ui.Icons;

/**
 * The lock screen as its own small window: shown on its own before the main
 * window for a returning owner, and again on <em>Lock</em> with the main
 * window hidden behind it. Hosts the {@link UnlockPanel}; "Forgot
 * passphrase?" opens the {@link RecoveryDialog} over it. It has no owner
 * window, so it stands in the taskbar as the app while nothing else is
 * showing. Closing it is the app's decision ({@code onClose}).
 */
@SuppressWarnings("serial")
public final class UnlockDialog extends JDialog {

	private final UnlockPanel panel;

	public UnlockDialog(UnlockPanel.Listener listener, Runnable onClose) {
		super((Window) null, BrightSide.APP_NAME, ModalityType.MODELESS);
		setIconImages(Icons.appIcons());
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				onClose.run();
			}
		});
		panel = new UnlockPanel(listener);
		setContentPane(panel);
		setResizable(false);
		pack();
		setLocationRelativeTo(null);
	}

	/**
	 * Shows it fresh — the field cleared, or pre-filled with a remembered
	 * passphrase (wiped once copied in) — and focused.
	 */
	public void showUnlock(char[] prefill) {
		panel.reset();
		if (prefill != null) {
			try {
				panel.prefill(prefill);
			} finally {
				Arrays.fill(prefill, '\0');
			}
		}
		showAndFocus();
	}

	/** A wrong passphrase, or a failure worth a line: re-enables the field with the message. */
	public void showError(String message) {
		panel.showError(message);
	}

	/** What the app is doing while the owner waits (taking over a running instance, say). */
	public void showProgress(String message) {
		panel.showProgress(message);
	}

	/** Something the owner should know before unlocking, the field still open. */
	public void showNote(String message) {
		panel.showNote(message);
	}

	/** Bring it back from the tray or from behind other windows. */
	public void showAndFocus() {
		setVisible(true);
		toFront();
		requestFocus();
		EventQueue.invokeLater(panel::focusField);
	}

	/** Open the (modal) recovery dialog over this one. */
	public void openRecovery(RecoveryDialog.Listener listener) {
		new RecoveryDialog(this, listener).setVisible(true);
	}
}
