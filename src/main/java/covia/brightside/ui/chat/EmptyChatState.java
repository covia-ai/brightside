package covia.brightside.ui.chat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import covia.brightside.ui.Icons;
import covia.brightside.ui.LAF;

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

		JPanel card = new WelcomeCard();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(BorderFactory.createEmptyBorder(28, 38, 30, 38));
		card.setPreferredSize(new Dimension(CARD_WIDTH, 276));

		JLabel mark = new JLabel(new ImageIcon(Icons.icon(72)));
		mark.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel brand = new JLabel("BRIGHTSIDE", SwingConstants.CENTER);
		brand.setForeground(LAF.ACCENT);
		brand.setFont(brand.getFont().deriveFont(Font.BOLD, 12f));
		brand.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel greeting = new JLabel("I’m Brightside. At your service.", SwingConstants.CENTER);
		greeting.setForeground(ChatStyle.foreground());
		greeting.setFont(greeting.getFont().deriveFont(Font.BOLD, 24f));
		greeting.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel invitation = new JLabel(
			"<html><div style='width:390px; text-align:center'>"
			+ "Start with a question, a half-formed idea, or something you want to get done."
			+ "</div></html>", SwingConstants.CENTER);
		invitation.setForeground(ChatStyle.muted());
		invitation.setFont(invitation.getFont().deriveFont(15f));
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
		centre.insets = new java.awt.Insets(24, 24, 24, 24);
		add(card, centre);
	}

	/** A quiet elevated surface with a subtle brand-coloured keyline. */
	private static final class WelcomeCard extends JPanel {
		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(ChatStyle.assistantBg());
				g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 28, 28);
				Color accent = LAF.ACCENT;
				g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
				g2.setStroke(new BasicStroke(1f));
				g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 28, 28);
			} finally {
				g2.dispose();
			}
			super.paintComponent(g);
		}

		WelcomeCard() {
			setOpaque(false);
		}
	}
}
