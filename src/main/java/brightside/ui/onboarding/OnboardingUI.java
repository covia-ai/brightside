package brightside.ui.onboarding;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The onboarding screens' own pieces — a step-dots indicator, a passphrase
 * strength bar and a recovery-word chip — on top of the app's shared
 * {@code brightside.ui.components}, which supply everything else they show.
 */
final class OnboardingUI {

	private OnboardingUI() {
	}

	/** A row of step dots; the current one is accent and larger. */
	static final class Dots extends JPanel {
		private static final long serialVersionUID = 1L;
		private int count;
		private int current;

		Dots() {
			setOpaque(false);
			setPreferredSize(new Dimension(200, 16));
		}

		void set(int count, int current) {
			this.count = count;
			this.current = current;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			if (count <= 0) return;
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g2);
				int gap = UIScale.scale(14);
				int total = (count - 1) * gap;
				int x = (getWidth() - total) / 2;
				int y = getHeight() / 2;
				for (int i = 0; i < count; i++) {
					boolean on = i == current;
					int r = UIScale.scale(on ? 5 : 4);
					g2.setColor(on ? Theme.accent() : Theme.blend(Theme.surface(), Theme.foreground(), 0.35f));
					g2.fillOval(x + i * gap - r / 2, y - r / 2, r, r);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** A rounded word chip: "3  orchard", for the recovery phrase grid. */
	static JComponent wordChip(int index, String word) {
		JPanel p = new Card(12);
		p.setLayout(new BorderLayout(8, 0));
		p.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		p.add(Labels.small(Integer.toString(index)), BorderLayout.WEST);
		p.add(Labels.heading(word), BorderLayout.CENTER);
		return p;
	}

	/** A slim strength bar (0..4) with a colour and label. */
	static final class Strength extends JPanel {
		private static final long serialVersionUID = 1L;
		private int score; // 0..4
		private final JLabel label = Labels.small(" ");

		Strength() {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setAlignmentX(Component.CENTER_ALIGNMENT);
			Bar bar = new Bar();
			bar.setAlignmentX(Component.CENTER_ALIGNMENT);
			label.setAlignmentX(Component.CENTER_ALIGNMENT);
			add(bar);
			add(Box.createVerticalStrut(4));
			add(label);
		}

		void set(int score) {
			this.score = Math.max(0, Math.min(4, score));
			label.setText(score <= 1 ? "weak" : score == 2 ? "fair" : score == 3 ? "good" : "strong");
			Styles.classes(label, Styles.SMALL, tone());
			repaint();
		}

		private String tone() {
			return switch (score) {
				case 0, 1 -> Styles.ERROR;
				case 2 -> Styles.WARNING;
				default -> Styles.SUCCESS;
			};
		}

		private Color colour() {
			return switch (score) {
				case 0, 1 -> Theme.error();
				case 2 -> Theme.warning();
				case 3 -> Theme.blend(Theme.success(), Theme.warning(), 0.4f);
				default -> Theme.success();
			};
		}

		private final class Bar extends JComponent {
			private static final long serialVersionUID = 1L;

			Bar() {
				setPreferredSize(new Dimension(300, 8));
				setMaximumSize(new Dimension(300, 8));
			}

			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				try {
					FlatUIUtils.setRenderingHints(g2);
					int w = getWidth();
					int h = getHeight();
					g2.setColor(Theme.blend(Theme.surface(), Theme.foreground(), 0.15f));
					g2.fill(FlatUIUtils.createComponentRectangle(0, 0, w, h, h));
					if (score > 0) {
						int fw = Math.round(w * (score / 4f));
						g2.setColor(colour());
						g2.fill(FlatUIUtils.createComponentRectangle(0, 0, fw, h, h));
					}
				} finally {
					g2.dispose();
				}
			}
		}
	}

	/** A rough passphrase strength score 0..4 (length + character variety). */
	static int scorePassphrase(char[] pw) {
		if (pw == null || pw.length == 0) return 0;
		int len = pw.length;
		boolean lower = false, upper = false, digit = false, other = false;
		for (char c : pw) {
			if (Character.isLowerCase(c)) lower = true;
			else if (Character.isUpperCase(c)) upper = true;
			else if (Character.isDigit(c)) digit = true;
			else other = true;
		}
		int variety = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
		int score = 0;
		if (len >= 8) score++;
		if (len >= 12) score++;
		if (len >= 16) score++;
		if (variety >= 3) score++;
		if (len >= 20 && variety >= 2) score++;
		return Math.min(4, score);
	}
}
