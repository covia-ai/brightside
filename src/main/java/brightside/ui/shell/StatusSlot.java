package brightside.ui.shell;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The status slot at the foot of the sidebar: what Tau is doing, in plain
 * words with a count where one helps ("Resting", "Waiting on you · 1
 * decision"), beside the τ glyph on a card. It shows the glyph alone whenever
 * a quiet screen is open ({@link #setQuietScreenOpen}): the Record, the
 * Vault, the Constellation, an agent's profile, a refusal, or an error.
 * Collapsed, the label moves to the tooltip and the accessible name.
 */
@SuppressWarnings("serial")
public final class StatusSlot extends JPanel {

	/** What the slot can say. Stopped and failed are status badges, not slot states. */
	public enum State {
		RESTING("Resting"), THINKING("Thinking"), AT_WORK("At work"), WAITING("Waiting on you"), DONE("Done");

		public final String word;

		State(String word) {
			this.word = word;
		}
	}

	private final Glyph glyph = new Glyph();
	private final JLabel label = Labels.small("");
	private State state = State.RESTING;
	private String detail;
	private boolean collapsed;
	private boolean quietScreenOpen;

	public StatusSlot() {
		super(new BorderLayout(UIScale.scale(8), 0));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		Styles.classes(label, Styles.SMALL, Styles.STRONG);
		add(glyph, BorderLayout.WEST);
		add(label, BorderLayout.CENTER);
		update();
	}

	/** {@code detail}: the count, in words ("1 decision"), or null. */
	public void setState(State state, String detail) {
		this.state = state;
		this.detail = detail;
		update();
	}

	public State state() {
		return state;
	}

	/** The words the slot shows, or carries as its tooltip when collapsed. */
	public String text() {
		return (detail != null) ? state.word + " · " + detail : state.word;
	}

	public void setCollapsed(boolean collapsed) {
		this.collapsed = collapsed;
		update();
	}

	/** A quiet screen is open: the slot shows the glyph alone. */
	public void setQuietScreenOpen(boolean open) {
		quietScreenOpen = open;
		repaint();
	}

	public boolean quietScreenOpen() {
		return quietScreenOpen;
	}

	/** Whether the slot shows anything beyond the τ glyph and its words. Always false in this version. */
	public boolean showsMoreThanGlyph() {
		return false;
	}

	private void update() {
		String t = text();
		// The word on the card; the count rides in the tooltip and the accessible name.
		label.setText(collapsed ? null : state.word);
		label.setVisible(!collapsed);
		setToolTipText(APP_NAME + ": " + t);
		getAccessibleContext().setAccessibleName(APP_NAME + " is " + t.toLowerCase());
		revalidate();
		repaint();
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(Theme.card());
			g2.fill(FlatUIUtils.createComponentRectangle(0, 0, getWidth(), getHeight(), UIScale.scale(20f)));
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}

	/**
	 * The τ glyph, 28 px: navy on a light tile in Light, the glyph on its own
	 * in Dark. Plain type, never the logo's gradient. The waiting state adds
	 * the amber dot, which the label always names too.
	 */
	private final class Glyph extends JComponent {
		@Override
		public Dimension getPreferredSize() {
			int s = UIScale.scale(28);
			return new Dimension(s, s);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g2);
				int s = Math.min(getWidth(), getHeight());
				int y0 = (getHeight() - s) / 2;
				Color tile = Theme.isDark() ? Theme.chrome() : Theme.warmSurface();
				g2.setColor(tile);
				g2.fillRoundRect(0, y0, s, s, s / 2, s / 2);
				g2.setColor(Theme.foreground());
				g2.setFont(getFont().deriveFont(Font.BOLD, s * 0.62f));
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString("τ", (s - fm.stringWidth("τ")) / 2f, y0 + (s + fm.getAscent() - fm.getDescent()) / 2f);
				if (state == State.WAITING) {
					int d = UIScale.scale(8);
					g2.setColor(Theme.yours());
					g2.fillOval(s - d, y0, d, d);
				}
			} finally {
				g2.dispose();
			}
		}
	}
}
