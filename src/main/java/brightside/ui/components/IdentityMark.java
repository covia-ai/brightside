package brightside.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.swing.Icon;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

/**
 * An identity's generated mark and short fingerprint, both derived from its
 * identifier (an agent's DID, say), so the same identity always looks the
 * same and two different ones rarely do. The mark is a 3 × 3 grid of
 * tiles in one hue at different strengths, deliberately asymmetric so it
 * never reads as a face. Interim: the
 * final mark design is still to be chosen; only {@link #icon} draws it, so it
 * changes in one place.
 */
public final class IdentityMark {

	private IdentityMark() {
	}

	/** SHA-256 of the identifier. */
	static byte[] digest(String id) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A short fingerprint, the first four and last three hex digits of the digest: "7f3a…c21". */
	public static String fingerprint(String id) {
		byte[] d = digest(id);
		StringBuilder hex = new StringBuilder();
		for (byte b : d) hex.append(String.format("%02x", b));
		return hex.substring(0, 4) + "…" + hex.substring(hex.length() - 3);
	}

	/** The mark at {@code size} pixels square (before scaling). */
	public static Icon icon(String id, int size) {
		byte[] d = digest(id);
		float hue = (d[0] & 0xFF) / 255f;
		// Each tile's strength, from faint to full: no mirror, so no face.
		float[] cells = new float[9];
		for (int i = 0; i < 9; i++) cells[i] = 0.2f + 0.8f * ((d[1 + i] & 0xFF) % 4) / 3f;
		return new Icon() {
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				try {
					FlatUIUtils.setRenderingHints(g2);
					int s = getIconWidth();
					Color ink = Color.getHSBColor(hue, 0.45f, Theme.isDark() ? 0.92f : 0.55f);
					g2.setColor(Theme.fade(ink, 0.18f));
					g2.fillRoundRect(x, y, s, s, s / 3, s / 3);
					float pad = s * 0.14f, gap = s * 0.06f, cell = (s - 2 * pad - 2 * gap) / 3f;
					for (int i = 0; i < 9; i++) {
						int cx = i % 3, cy = i / 3;
						g2.setColor(Theme.fade(ink, cells[i]));
						g2.fill(new java.awt.geom.RoundRectangle2D.Float(x + pad + cx * (cell + gap),
							y + pad + cy * (cell + gap), cell, cell, cell / 3, cell / 3));
					}
				} finally {
					g2.dispose();
				}
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
}
