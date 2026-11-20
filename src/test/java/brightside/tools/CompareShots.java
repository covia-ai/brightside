package brightside.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Pairs two runs of {@link ScreenShots} side by side, before on the left and
 * after on the right, one PNG per screen, size and theme present in both, for
 * review between branches. A developer tool in test scope.
 *
 * <pre>
 * java -cp target/test-classes brightside.tools.CompareShots &lt;beforeDir&gt; &lt;afterDir&gt; &lt;outDir&gt; [nameFilter]
 * </pre>
 */
public final class CompareShots {

	private CompareShots() {
	}

	public static void main(String[] args) throws IOException {
		Path before = Path.of(args[0]), after = Path.of(args[1]), out = Path.of(args[2]);
		String filter = args.length > 3 ? args[3] : "";
		int n = 0;
		for (String mode : List.of("dark", "light")) {
			Path b = before.resolve(mode), a = after.resolve(mode);
			if (!Files.isDirectory(b) || !Files.isDirectory(a)) continue;
			try (Stream<Path> files = Files.list(a)) {
				for (Path f : files.filter(p -> p.getFileName().toString().contains(filter)).sorted().toList()) {
					Path old = b.resolve(f.getFileName());
					if (!Files.exists(old)) continue;
					Path dst = out.resolve(mode).resolve(f.getFileName());
					Files.createDirectories(dst.getParent());
					ImageIO.write(pair(ImageIO.read(old.toFile()), ImageIO.read(f.toFile())), "png", dst.toFile());
					n++;
				}
			}
		}
		System.out.println(n + " pairs written to " + out.toAbsolutePath());
	}

	static BufferedImage pair(BufferedImage left, BufferedImage right) {
		int gap = 16, head = 28;
		int w = left.getWidth() + gap + right.getWidth();
		int h = head + Math.max(left.getHeight(), right.getHeight());
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(0x808080));
		g.fillRect(0, 0, w, h);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		g.drawString("Before", 8, 20);
		g.drawString("After", left.getWidth() + gap + 8, 20);
		g.drawImage(left, 0, head, null);
		g.drawImage(right, left.getWidth() + gap, head, null);
		g.dispose();
		return img;
	}
}
