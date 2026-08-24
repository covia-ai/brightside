package covia.brightside.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
