package brightside.ui.settings;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JLabel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Theme;
import convex.core.crypto.IdenticonBuilder;
import convex.core.data.AccountKey;

/**
 * A crisp rendering of Convex's standard 7x7 identicon for an Ed25519 public
 * key. It is a recognition aid, not an abbreviated or authoritative identity.
 */
@SuppressWarnings("serial")
final class ConvexIdenticon extends JLabel {

	static final int GRID_SIZE = IdenticonBuilder.SIZE;
	static final int CELL_SIZE = 3;
	static final int INSET = 2;
	static final int DISPLAY_SIZE = GRID_SIZE * CELL_SIZE + INSET * 2;

	private AccountKey publicKey;
	private int[] pixels;

	ConvexIdenticon() {
		Dimension size = new Dimension(UIScale.scale(DISPLAY_SIZE), UIScale.scale(DISPLAY_SIZE));
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
		setOpaque(false);
		setName("Convex identity identicon");
		setToolTipText("No Ed25519 public key available");
		getAccessibleContext().setAccessibleName("Convex identity identicon");
	}

	/** Updates the icon from a 32-byte Ed25519 public key encoded as hex. */
	void setPublicKeyHex(String hex) {
		publicKey = AccountKey.parse(hex);
		pixels = (publicKey != null) ? IdenticonBuilder.build(publicKey) : null;
		setToolTipText(publicKey != null
			? "Convex 7x7 identicon for this Ed25519 public key (visual check only)"
			: "No Ed25519 public key available");
		repaint();
	}

	AccountKey publicKey() {
		return publicKey;
	}

	int[] pixels() {
		return (pixels != null) ? pixels.clone() : null;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (pixels == null) return;
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			int size = UIScale.scale(DISPLAY_SIZE);
			int cell = UIScale.scale(CELL_SIZE);
			int inset = UIScale.scale(INSET);
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(Theme.line());
			g2.drawRoundRect(0, 0, size - 1, size - 1, UIScale.scale(6), UIScale.scale(6));
			// The cells stay crisp: no antialiasing between them.
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			for (int y = 0; y < GRID_SIZE; y++) {
				for (int x = 0; x < GRID_SIZE; x++) {
					g2.setColor(new Color(pixels[x + y * GRID_SIZE], true));
					g2.fillRect(inset + x * cell, inset + y * cell, cell, cell);
				}
			}
		} finally {
			g2.dispose();
		}
	}
}
