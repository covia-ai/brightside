package brightside.ui.settings;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

import javax.swing.JLabel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.HiDPIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Theme;
import convex.core.crypto.IdenticonBuilder;
import convex.core.data.AccountKey;

/**
 * A crisp rendering of Convex's standard 7x7 identicon for an Ed25519 public
 * key. It is a recognition aid, not an abbreviated or authoritative identity.
 *
 * <p>The grid fills a slightly rounded square edge to edge, with a hairline
 * around it. It is painted in device pixels — the cells are whole pixels at
 * any scale, and the same size as each other — and the rounded corners are
 * cut from the grid itself, so the outline sits on the image rather than
 * beside it.
 */
@SuppressWarnings("serial")
final class ConvexIdenticon extends JLabel {

	static final int GRID_SIZE = IdenticonBuilder.SIZE;
	static final int CELL_SIZE = 3;
	/** The hairline's width: the edge cells reach out under it, so every cell shows the same size. */
	static final int INSET = 1;
	static final int DISPLAY_SIZE = GRID_SIZE * CELL_SIZE + INSET * 2;
	private static final int ARC = 6;

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
			HiDPIUtils.paintAtScale1x(g2, 0, 0, size, size, this::paintIcon);
		} finally {
			g2.dispose();
		}
	}

	/** Paints the icon into a {@code w} by {@code h} box of device pixels, {@code scale} device pixels to the logical one. */
	private void paintIcon(Graphics2D g2, int x, int y, int w, int h, double scale) {
		int line = Math.max(1, (int) Math.round(scale));
		int cell = Math.max(1, (Math.min(w, h) - 2 * line) / GRID_SIZE);
		int offX = (w - cell * GRID_SIZE) / 2;
		int offY = (h - cell * GRID_SIZE) / 2;
		// The grid as an image the size of the box: every cell whole pixels, the
		// edge cells reaching to the box's edge so the hairline covers no more
		// of them than of the others.
		BufferedImage grid = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = grid.createGraphics();
		try {
			for (int gy = 0; gy < GRID_SIZE; gy++) {
				int top = (gy == 0) ? 0 : offY + gy * cell;
				int bottom = (gy == GRID_SIZE - 1) ? h : offY + (gy + 1) * cell;
				for (int gx = 0; gx < GRID_SIZE; gx++) {
					int left = (gx == 0) ? 0 : offX + gx * cell;
					int right = (gx == GRID_SIZE - 1) ? w : offX + (gx + 1) * cell;
					ig.setColor(new Color(pixels[gx + gy * GRID_SIZE], true));
					ig.fillRect(left, top, right - left, bottom - top);
				}
			}
		} finally {
			ig.dispose();
		}
		// The image through the rounded shape, then the hairline on its edge.
		FlatUIUtils.setRenderingHints(g2);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		float arc = (float) (ARC * scale);
		g2.setPaint(new TexturePaint(grid, new Rectangle(x, y, w, h)));
		g2.fill(FlatUIUtils.createComponentRectangle(x, y, w, h, arc));
		g2.setPaint(Theme.line());
		FlatUIUtils.paintOutline(g2, x, y, w, h, line, arc);
	}
}
