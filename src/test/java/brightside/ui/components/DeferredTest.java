package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

/** A deferred view costs nothing until it is wanted, and is built once. */
class DeferredTest {

	@Test
	void buildsOnceWhenFirstWanted() {
		AtomicInteger builds = new AtomicInteger();
		Deferred view = new Deferred(() -> {
			builds.incrementAndGet();
			return new JLabel("built");
		});
		assertEquals(0, builds.get(), "nothing built until shown");
		assertEquals(0, view.getComponentCount());
		view.built();
		view.built();
		assertEquals(1, builds.get(), "built once");
		assertEquals(1, view.getComponentCount());
	}
}
