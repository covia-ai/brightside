package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JLabel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * One concept, one Lucide icon, in a rounded tile tinted from its colour
 * family: the house pattern for a screen, a settings page, or a kind of
 * thing. 32 px in lists, 40 px in headers; the icon takes 18 px of a 32 px
 * tile. Colours are read when painting, so the tile follows a theme change.
 */
@SuppressWarnings("serial")
public final class IconTile extends JLabel {

	/** The colour families a tile is tinted from, and the opacity of each one's ground. */
	public enum Family {
		PRIMARY(Theme::accentText, 0.15f),
		CERULEAN(Theme::blue, 0.15f),
		VIOLET(() -> FlatUIUtils.getUIColor("Brightside.icon.violet", 0x8B5CF6), 0.15f),
		INDIGO(() -> FlatUIUtils.getUIColor("Brightside.icon.indigo", 0x4F5BC4), 0.15f),
		NAVY(() -> FlatUIUtils.getUIColor("Brightside.icon.navy", 0x2E3A66), 0.20f),
		AMBER(() -> Theme.isDark() ? Theme.yours() : FlatUIUtils.getUIColor("Brightside.warning", 0xA9561B), 0.25f),
		NEUTRAL(Theme::muted, 0.15f);

		final Supplier<Color> colour;
		final float ground;

		Family(Supplier<Color> colour, float ground) {
			this.colour = colour;
			this.ground = ground;
		}

		public Color colour() {
			return colour.get();
		}
	}

	/** The concepts with a fixed icon and family, the same everywhere in the app. */
	public enum Concept {
		HOME("house", Family.PRIMARY),
		AGENT("user-round", Family.PRIMARY),
		AGENTS("users-round", Family.PRIMARY),
		FILES("folder-open", Family.PRIMARY),
		CHATS("message-square-text", Family.CERULEAN),
		INBOX("inbox", Family.CERULEAN),
		CONSTELLATION("orbit", Family.CERULEAN),
		INTEGRATIONS("plug", Family.CERULEAN),
		VENUE("building-2", Family.CERULEAN),
		FEED("rss", Family.CERULEAN),
		SKILLS("book-open-check", Family.VIOLET),
		MODEL("cpu", Family.VIOLET),
		JOBS("list-checks", Family.NAVY),
		RECORD("scroll-text", Family.NAVY),
		SCHEDULE("calendar-clock", Family.NAVY),
		SECRETS("key-round", Family.AMBER),
		VAULT("lock", Family.AMBER),
		ACCESS("ticket", Family.AMBER),
		TREASURY("credit-card", Family.AMBER),
		IDENTITY("fingerprint", Family.NEUTRAL),
		SETTINGS("settings", Family.NEUTRAL);

		public final String icon;
		public final Family family;

		Concept(String icon, Family family) {
			this.icon = icon;
			this.family = family;
		}
	}

	private final Family family;
	private final int size;

	public IconTile(Concept concept, int size) {
		this(concept.icon, concept.family, size);
	}

	/** {@code icon}: a {@link Lucide} name. */
	public IconTile(String icon, Family family, int size) {
		super(Lucide.icon(icon, Math.round(size * 18 / 32f), family::colour), CENTER);
		this.family = family;
		this.size = size;
		setOpaque(false);
	}

	/** The tile as an {@link Icon}, for a button or a list row that takes one beside its text. */
	public Icon asIcon() {
		Icon glyph = getIcon();
		return new Icon() {
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				try {
					FlatUIUtils.setRenderingHints(g2);
					g2.setColor(Theme.fade(family.colour(), family.ground));
					g2.fill(FlatUIUtils.createComponentRectangle(x, y, getIconWidth(), getIconHeight(), UIScale.scale(16f)));
				} finally {
					g2.dispose();
				}
				glyph.paintIcon(c, g, x + (getIconWidth() - glyph.getIconWidth()) / 2,
					y + (getIconHeight() - glyph.getIconHeight()) / 2);
			}

			@Override
			public int getIconWidth() {
				return UIScale.scale(size);
			}

			@Override
			public int getIconHeight() {
				return getIconWidth();
			}
		};
	}

	@Override
	public Dimension getPreferredSize() {
		int s = UIScale.scale(size);
		return new Dimension(s, s);
	}

	@Override
	public Dimension getMinimumSize() {
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(Theme.fade(family.colour(), family.ground));
			g2.fill(FlatUIUtils.createComponentRectangle(0, 0, getWidth(), getHeight(), UIScale.scale(20f)));
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
