package covia.brightside.ui.chat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;

import javax.swing.Icon;

/**
 * Tiny painted icons for the chat — a disclosure chevron and a tool
 * success/failure mark. Drawn with vector paths rather than glyphs so they
 * render regardless of the UI font (Lato, for one, has no ▸/▾/✓/✕).
 */
final class ChatIcons {

	private ChatIcons() {
	}

	/** A disclosure chevron: points right when collapsed, down when expanded. */
	static Icon chevron(boolean expanded, Color color) {
		return new ChevronIcon(expanded, color);
	}

	/** A tool outcome mark: a tick when {@code ok}, otherwise a cross. */
	static Icon mark(boolean ok, Color color) {
		return new MarkIcon(ok, color);
	}

	private static Graphics2D prepare(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g2;
	}

	private static final class ChevronIcon implements Icon {
		private static final int SIZE = 9;
		private final boolean expanded;
		private final Color color;

		ChevronIcon(boolean expanded, Color color) {
			this.expanded = expanded;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = prepare(g);
			g2.setColor(color);
			GeneralPath p = new GeneralPath();
			if (expanded) {
				p.moveTo(x + 1.5, y + 2.5);
				p.lineTo(x + SIZE - 1.5, y + 2.5);
				p.lineTo(x + SIZE / 2.0, y + SIZE - 2.0);
			} else {
				p.moveTo(x + 2.5, y + 1.5);
				p.lineTo(x + 2.5, y + SIZE - 1.5);
				p.lineTo(x + SIZE - 2.0, y + SIZE / 2.0);
			}
			p.closePath();
			g2.fill(p);
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}
	}

	private static final class MarkIcon implements Icon {
		private static final int SIZE = 11;
		private final boolean ok;
		private final Color color;

		MarkIcon(boolean ok, Color color) {
			this.ok = ok;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = prepare(g);
			g2.setColor(color);
			g2.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
			if (ok) {
				GeneralPath p = new GeneralPath();
				p.moveTo(x + 2.0, y + SIZE * 0.55);
				p.lineTo(x + SIZE * 0.42, y + SIZE - 2.5);
				p.lineTo(x + SIZE - 1.8, y + 2.2);
				g2.draw(p);
			} else {
				g2.drawLine(x + 2, y + 2, x + SIZE - 2, y + SIZE - 2);
				g2.drawLine(x + SIZE - 2, y + 2, x + 2, y + SIZE - 2);
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}
	}
}
