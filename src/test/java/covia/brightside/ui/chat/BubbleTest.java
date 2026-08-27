package covia.brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

final class BubbleTest {

	@Test
	void preferredSizeDoesNotCountTextPaddingTwice() throws Exception {
		AtomicReference<Bubble> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> result.set(new Bubble("A short message", Color.BLACK, Color.WHITE)));
		Bubble bubble = result.get();

		Dimension preferred = bubble.getPreferredSize();
		assertEquals(preferred.width, bubble.textArea().getWidth());
		assertEquals(preferred.height, bubble.textArea().getPreferredSize().height);
	}
}
