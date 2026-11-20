package brightside.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.UIManager;

import brightside.ui.LAF;
import brightside.ui.components.Theme;

/**
 * The 12 and 13 px legibility sheet: real Tau strings (Inbox meta, Record
 * rows, badge labels, the status strip, fingerprints beside text, and the
 * confusable pairs) in the UI face at 12 and 13 px, regular and bold, on each
 * ground of Tau Dark and Tau Light, at 1x and 2x. One PNG per theme and scale.
 * A developer tool in test scope.
 *
 * <pre>
 * java -cp target/test-classes:target/classes:$(cat target/cp.txt) brightside.tools.LegibilitySheet &lt;outDir&gt;
 * </pre>
 */
public final class LegibilitySheet {

	private static final List<String> LINES = List.of(
		"Il1 | 0O | rn m | cl d | vv w | 5S 8B 6b",
		"Asked by Release notes · 20 Nov 2026, 15:28 · expires in 27 min",
		"09:44:10  scribe a91e…04d  peer.follow docs-bot  Waiting on you",
		"Running · Signed · Waiting on you · Refused: billing.write",
		"Acting as Sam · This computer · Running · 12 waiting · Search Cmd+K",
		"did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK · 7f3a…c21",
		"Letters, digits, dots, dashes, and underscores: how operations reference it",
		"0123456789  $1,250.00  99+  1.0.12  v0.12.0");

	private LegibilitySheet() {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "target/legibility");
		Files.createDirectories(out);
		for (String theme : List.of(LAF.TAU_DARK, LAF.TAU_LIGHT)) {
			LAF.init(theme, null, null, null);
			String family = UIManager.getFont("defaultFont").getFamily();
			for (int scale : new int[] { 1, 2 }) {
				Color[] grounds = { Theme.panel(), Theme.card(), Theme.sidebar(), Theme.chrome(), Theme.warmSurface() };
				String[] names = { "content", "card", "sidebar", "chrome", "warm surface" };
				int rowH = 22, block = (LINES.size() * 2 * 2) * rowH + 40;
				int w = 760 * scale, h = (60 + grounds.length * block) * scale;
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = img.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
				g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
				g.scale(scale, scale);
				g.setColor(Theme.panel());
				g.fillRect(0, 0, w, h);
				g.setColor(Theme.foreground());
				g.setFont(new Font(family, Font.BOLD, 16));
				g.drawString(family + " at 12 and 13 px · " + theme + " · " + scale + "x", 16, 32);
				int y = 60;
				for (int i = 0; i < grounds.length; i++) {
					g.setColor(grounds[i]);
					g.fillRect(0, y, 760, block);
					g.setColor(Theme.muted());
					g.setFont(new Font(family, Font.PLAIN, 12));
					g.drawString(names[i], 16, y + 20);
					int ly = y + 44;
					for (int size : new int[] { 12, 13 }) {
						for (int style : new int[] { Font.PLAIN, Font.BOLD }) {
							g.setFont(new Font(family, style, size));
							for (String line : LINES) {
								g.setColor(line.startsWith("did:") ? Theme.muted() : Theme.foreground());
								g.drawString(size + (style == Font.BOLD ? "b " : "  ") + line, 16, ly);
								ly += rowH;
							}
						}
					}
					y += block;
				}
				g.dispose();
				Path f = out.resolve("legibility-" + theme + "-" + scale + "x.png");
				ImageIO.write(img, "png", f.toFile());
				System.out.println(f.toAbsolutePath());
			}
		}
	}
}
