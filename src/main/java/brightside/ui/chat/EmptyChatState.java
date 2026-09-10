package brightside.ui.chat;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import brightside.ui.Icons;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Theme;

/**
 * The centred welcome shown before an uncommitted Home conversation has any
 * turns: the mark, and the agent on the line introducing itself by name.
 */
@SuppressWarnings("serial")
final class EmptyChatState extends JPanel {

	/** Until an agent is on the line. */
	static final String DEFAULT_NAME = "Brightside";

	private final JLabel greeting = Labels.title("");

	EmptyChatState() {
		super(new GridBagLayout());
		setName("chat-empty-state");
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		// A quiet elevated surface with a subtle brand-coloured keyline.
		JPanel card = new Card(28).outline(() -> Theme.fade(Theme.accent(), 0.35f));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(BorderFactory.createEmptyBorder(32, 48, 34, 48));

		JLabel mark = Labels.icon(new ImageIcon(Icons.icon(72)));
		mark.setAlignmentX(Component.CENTER_ALIGNMENT);

		greeting.setHorizontalAlignment(SwingConstants.CENTER);
		greeting.setAlignmentX(Component.CENTER_ALIGNMENT);
		setAgentName(null);

		card.add(mark);
		card.add(Box.createVerticalStrut(16));
		card.add(greeting);

		GridBagConstraints centre = new GridBagConstraints();
		centre.gridx = 0;
		centre.gridy = 0;
		centre.weightx = 1;
		centre.weighty = 1;
		centre.anchor = GridBagConstraints.CENTER;
		centre.insets = new Insets(24, 24, 24, 24);
		add(card, centre);
	}

	/** The agent on the line, by the name it goes by (null or blank: {@value #DEFAULT_NAME}). */
	void setAgentName(String name) {
		String who = (name == null || name.isBlank()) ? DEFAULT_NAME : name.trim();
		greeting.setText(who + ". At your service.");
		revalidate();
		repaint();
	}
}
