package covia.brightside.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class IconsTest {

	@Test
	void paintsASquareDiscWithTransparentCorners() {
		BufferedImage img = assertInstanceOf(BufferedImage.class, Icons.icon(32));
		assertEquals(32, img.getWidth());
		assertEquals(32, img.getHeight());
		assertNotEquals(0, img.getRGB(16, 16) >>> 24, "centre is painted");
		assertEquals(0, img.getRGB(0, 0) >>> 24, "corner is transparent");
	}

	@Test
	void appIconsCoverTheCommonSizes() {
		assertTrue(Icons.appIcons().size() >= 4);
		assertEquals(16, ((BufferedImage) Icons.appIcons().get(0)).getWidth());
	}

	@Test
	void illuminatedPhaseSpansTheMoonDiameter() {
		int size = 256;
		float pad = size * 0.04f;
		Shape phase = Icons.brightPhase(size, pad);
		PathIterator path = phase.getPathIterator(null);
		double[] points = new double[6];

		assertEquals(PathIterator.SEG_MOVETO, path.currentSegment(points));
		double startX = points[0];
		double startY = points[1];
		path.next();
		assertEquals(PathIterator.SEG_CUBICTO, path.currentSegment(points));
		path.next();
		assertEquals(PathIterator.SEG_CUBICTO, path.currentSegment(points));
		double oppositeX = points[4];
		double oppositeY = points[5];

		assertEquals(size - 2.0 * pad,
			Math.hypot(oppositeX - startX, oppositeY - startY), 0.0001,
			"the terminator's shared endpoints are antipodal on the moon");
	}
}
