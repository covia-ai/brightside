package brightside.ui.onboarding;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import brightside.ui.LAF;

/**
 * Shared look for the onboarding screens — the app's first impression, so it's
 * worth getting right. Small helpers for the accent primary button, a quiet
 * secondary/link button, muted captions, section titles, a step-dots indicator,
 * a passphrase strength bar and a chip, all in the FlatLaf + Lato + purple-accent
 * language of the rest of the app.
 */
final class OnboardingUI {

	private OnboardingUI() {
	}

	static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	static Color foreground() {
		Color c = UIManager.getColor("Label.foreground");
		return (c != null) ? c : Color.WHITE;
	}

	static Color surface() {
		Color base = UIManager.getColor("Panel.background");
		if (base == null) base = new Color(0x2B, 0x2B, 0x2B);
		boolean dark = (base.getRed() + base.getGreen() + base.getBlue()) / 3 < 128;
		return mix(base, dark ? Color.WHITE : Color.BLACK, dark ? 0.10f : 0.05f);
	}

	static Color mix(Color a, Color b, float t) {
		return new Color(
			Math.round(a.getRed() * (1 - t) + b.getRed() * t),
			Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
			Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
	}

	/** The accent primary action. */
	static JButton primary(String text) {
		JButton b = new JButton(text);
		b.putClientProperty("JButton.buttonType", "roundRect");
		b.setForeground(Color.WHITE);
		b.setBackground(LAF.ACCENT);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() + 1f));
		b.setMargin(new java.awt.Insets(8, 22, 8, 22));
		return b;
	}

	/** A quiet secondary action (Back). */
	static JButton secondary(String text) {
		JButton b = new JButton(text);
		b.putClientProperty("JButton.buttonType", "roundRect");
		b.putClientProperty("FlatLaf.style", "borderColor: @disabledForeground");
		b.setForeground(muted());
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setMargin(new java.awt.Insets(8, 18, 8, 18));
		return b;
	}

	static JLabel title(String text) {
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 9f));
		l.setAlignmentX(Component.CENTER_ALIGNMENT);
		return l;
	}

	static JLabel subtitle(String text) {
		JLabel l = html(text, 420, SwingConstants.CENTER);
		l.setForeground(muted());
		l.setAlignmentX(Component.CENTER_ALIGNMENT);
		return l;
	}

	/** A centred, width-wrapped label (HTML) so long lines read nicely. */
	static JLabel html(String text, int width, int align) {
		String css = "text-align:" + (align == SwingConstants.CENTER ? "center" : "left") + "; width:" + width + "px;";
		JLabel l = new JLabel("<html><div style='" + css + "'>" + text + "</div></html>", align);
		l.setAlignmentX(Component.CENTER_ALIGNMENT);
		return l;
	}

	static JLabel caption(String text) {
		JLabel l = new JLabel(text);
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setForeground(muted());
		l.setAlignmentX(Component.CENTER_ALIGNMENT);
		return l;
	}

	/**
	 * A read-only, transparent, wrapping text block on a standard component, so its
	 * text can be selected and copied (Ctrl/Cmd+C). Use for anything the reader
	 * might want to copy — values, explanations, warnings.
	 */
	static JTextArea selectable(String text) {
		JTextArea a = new JTextArea(text);
		a.setEditable(false);
		a.setOpaque(false);
		a.setBorder(null);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setFont(UIManager.getFont("Label.font"));
		a.setForeground(foreground());
		a.setAlignmentX(Component.LEFT_ALIGNMENT);
		return a;
	}

	/** A clickable link that opens {@code url}. */
	static JLabel link(String text, String url) {
		JLabel l = new JLabel("<html><a href=''>" + text + "</a></html>");
		l.setForeground(LAF.ACCENT);
		l.putClientProperty("FlatLaf.styleClass", "small");
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.setAlignmentX(Component.CENTER_ALIGNMENT);
		// On press, like every other control: a click needs a motionless release.
		l.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (javax.swing.SwingUtilities.isLeftMouseButton(e)) open(url);
			}
		});
		return l;
	}

	static void open(String url) {
		try {
			if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
		} catch (Exception ignored) {
			// best effort
		}
	}

	static Component vspace(int px) {
		return Box.createVerticalStrut(px);
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
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int gap = 14;
			int total = (count - 1) * gap;
			int x = (getWidth() - total) / 2;
			int y = getHeight() / 2;
			for (int i = 0; i < count; i++) {
				boolean on = i == current;
				int r = on ? 5 : 4;
				g2.setColor(on ? LAF.ACCENT : mix(surface(), foreground(), 0.35f));
				g2.fillOval(x + i * gap - r / 2, y - r / 2, r, r);
			}
			g2.dispose();
		}
	}

	/** A rounded word chip: "3  orchard", for the recovery phrase grid. */
	static JComponent wordChip(int index, String word) {
		JPanel p = new JPanel(new java.awt.BorderLayout(8, 0)) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(surface());
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		JLabel num = new JLabel(Integer.toString(index));
		num.setForeground(muted());
		num.putClientProperty("FlatLaf.styleClass", "small");
		JLabel w = new JLabel(word);
		w.setFont(w.getFont().deriveFont(Font.BOLD));
		p.add(num, java.awt.BorderLayout.WEST);
		p.add(w, java.awt.BorderLayout.CENTER);
		return p;
	}

	/** A slim strength bar (0..4) with a colour and label. */
	static final class Strength extends JPanel {
		private static final long serialVersionUID = 1L;
		private int score; // 0..4
		private final JLabel label = new JLabel(" ");

		Strength() {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setAlignmentX(Component.CENTER_ALIGNMENT);
			Bar bar = new Bar();
			bar.setAlignmentX(Component.CENTER_ALIGNMENT);
			label.setAlignmentX(Component.CENTER_ALIGNMENT);
			label.putClientProperty("FlatLaf.styleClass", "small");
			label.setForeground(muted());
			add(bar);
			add(Box.createVerticalStrut(4));
			add(label);
		}

		void set(int score) {
			this.score = Math.max(0, Math.min(4, score));
			label.setText(score <= 1 ? "weak" : score == 2 ? "fair" : score == 3 ? "good" : "strong");
			label.setForeground(colour());
			repaint();
		}

		private Color colour() {
			return switch (score) {
				case 0, 1 -> new Color(0xE5, 0x53, 0x53);
				case 2 -> new Color(0xE0, 0xA0, 0x30);
				case 3 -> new Color(0x8A, 0xB4, 0x3A);
				default -> new Color(0x3F, 0xB9, 0x50);
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
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				g2.setColor(mix(surface(), foreground(), 0.15f));
				g2.fillRoundRect(0, 0, w, h, h, h);
				if (score > 0) {
					int fw = Math.round(w * (score / 4f));
					g2.setColor(colour());
					g2.fillRoundRect(0, 0, fw, h, h, h);
				}
				g2.dispose();
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
