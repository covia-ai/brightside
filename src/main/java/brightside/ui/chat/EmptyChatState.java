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
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/** The centred welcome shown before an uncommitted Home conversation has any turns. */
@SuppressWarnings("serial")
final class EmptyChatState extends JPanel {

	private static final int CARD_WIDTH = 520;

	EmptyChatState() {
		super(new GridBagLayout());
		setName("chat-empty-state");
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		// A quiet elevated surface with a subtle brand-coloured keyline.
		JPanel card = new Card(28).outline(() -> Theme.fade(Theme.accent(), 0.35f));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(BorderFactory.createEmptyBorder(28, 38, 30, 38));
		card.setPreferredSize(new Dimension(CARD_WIDTH, 276));

		JLabel mark = Labels.icon(new ImageIcon(Icons.icon(72)));
		mark.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel brand = Styles.classes(Labels.text("BRIGHTSIDE"), Styles.ACCENT);
		Styles.style(brand, "font: bold -3");
		brand.setHorizontalAlignment(SwingConstants.CENTER);
		brand.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel greeting = Labels.title("I’m Brightside. At your service.");
		greeting.setHorizontalAlignment(SwingConstants.CENTER);
		greeting.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Wrapped within the card's inner width: Labels.html scales the width by
		// the UI scale (1.25 at the default font), the card's own size is not.
		JLabel invitation = Styles.classes(Labels.html(
			"Start with a question, a half-formed idea, or something you want to get done.",
			340, SwingConstants.CENTER), Styles.MUTED);
		invitation.setAlignmentX(Component.CENTER_ALIGNMENT);

		card.add(mark);
		card.add(Box.createVerticalStrut(12));
		card.add(brand);
		card.add(Box.createVerticalStrut(8));
		card.add(greeting);
		card.add(Box.createVerticalStrut(12));
		card.add(invitation);

		GridBagConstraints centre = new GridBagConstraints();
		centre.gridx = 0;
		centre.gridy = 0;
		centre.weightx = 1;
		centre.weighty = 1;
		centre.anchor = GridBagConstraints.CENTER;
		centre.insets = new Insets(24, 24, 24, 24);
		add(card, centre);
	}
}
