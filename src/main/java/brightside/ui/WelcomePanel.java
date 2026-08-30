package brightside.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.Identity;
import brightside.ui.components.Buttons;
import brightside.ui.components.Documents;
import brightside.ui.components.Labels;
import brightside.ui.components.Panels;
import brightside.ui.components.Styles;

/**
 * The friendly first screen: "What should I call you?". A full-window panel
 * (not a dialog) with a text box and a Continue button. Reused, with a Cancel
 * option, when someone changes their name later. No jargon — the person just
 * enters a name.
 */
@SuppressWarnings("serial")
public final class WelcomePanel extends JPanel {

	/** What the panel does with the entered name. */
	public interface Listener {
		void onNameEntered(String name);

		void onCancel();
	}

	private final Listener listener;
	private final JLabel title = Labels.title("What should I call you?");
	private final JLabel subtitle = Labels.muted("");
	private final JTextField field = new JTextField(16);
	private final JButton cont = Buttons.primary("Continue");
	private final JButton cancel = Buttons.secondary("Cancel");
	private final JLabel statusLine = Labels.muted(" ");

	public WelcomePanel(Listener listener) {
		super(new java.awt.GridBagLayout()); // centre the column in the window
		this.listener = listener;

		JPanel column = Panels.column();
		column.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

		JLabel mark = Labels.icon(new ImageIcon(Icons.icon(72)));
		mark.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel hello = Labels.muted("Welcome to " + brightside.BrightSide.APP_NAME);
		hello.setAlignmentX(Component.CENTER_ALIGNMENT);

		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		setSubtitle("I'm your personal assistant. Let's get to know each other.");

		field.setHorizontalAlignment(JTextField.CENTER);
		Styles.style(field, "font: +6");
		field.setMaximumSize(new Dimension(320, field.getPreferredSize().height + 10));
		field.setAlignmentX(Component.CENTER_ALIGNMENT);
		field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Your name");

		cont.setAlignmentX(Component.CENTER_ALIGNMENT);
		cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
		cancel.setVisible(false);
		cancel.addActionListener(e -> listener.onCancel());

		statusLine.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel buttons = Panels.row();
		buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(cont);

		column.add(mark);
		column.add(Box.createVerticalStrut(16));
		column.add(hello);
		column.add(Box.createVerticalStrut(4));
		column.add(title);
		column.add(Box.createVerticalStrut(10));
		column.add(subtitle);
		column.add(Box.createVerticalStrut(22));
		column.add(field);
		column.add(Box.createVerticalStrut(16));
		column.add(buttons);
		column.add(Box.createVerticalStrut(12));
		column.add(statusLine);

		add(column);

		// Enter submits.
		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");
		field.getActionMap().put("submit", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				submit();
			}
		});
		cont.addActionListener(e -> submit());
		Documents.onChange(field, this::refresh);
		field.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE && cancel.isVisible()) listener.onCancel();
			}
		});
		refresh();
	}

	public void setSubtitle(String text) {
		subtitle.setText(text);
	}

	/**
	 * Prepares the screen for showing.
	 *
	 * @param initial      name to pre-fill
	 * @param showCancel   whether a Cancel option is offered (changing name, not first run)
	 */
	public void prepare(String initial, boolean showCancel) {
		field.setText(initial == null ? "" : initial);
		field.selectAll();
		cancel.setVisible(showCancel);
		setBusy(null);
		refresh();
	}

	public void focusField() {
		field.requestFocusInWindow();
		field.selectAll();
	}

	/** Shows a working message and locks input, or clears it when {@code message} is null. */
	public void setBusy(String message) {
		boolean busy = message != null;
		field.setEnabled(!busy);
		cont.setEnabled(!busy && nameValid());
		cancel.setEnabled(!busy);
		Styles.classes(statusLine, Styles.MUTED);
		statusLine.setText(busy ? message : " ");
	}

	public void setError(String message) {
		field.setEnabled(true);
		cancel.setEnabled(true);
		Styles.classes(statusLine, Styles.ERROR);
		statusLine.setText(message);
		refresh();
		focusField();
	}

	private boolean nameValid() {
		return !Identity.sanitise(field.getText()).isEmpty();
	}

	private void refresh() {
		cont.setEnabled(nameValid());
	}

	private void submit() {
		if (!nameValid()) {
			setError("Please enter a name.");
			return;
		}
		listener.onNameEntered(field.getText());
	}
}
