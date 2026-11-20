package brightside.ui.inspect;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import brightside.AgentContext;
import brightside.ui.LAF;
import brightside.ui.shell.ShellLayout;

/**
 * In the inspector docked beside the chat (320 pt wide) the Overview shows
 * every caption, value and line in full, in both Tau themes: no label is cut
 * short, and nothing runs past the column's edge. Isolated: it swaps the
 * JVM's look and feel. Headless: the Overview is laid out without a window.
 */
@Isolated
class DockedInspectorLayoutTest {

	@Test
	void nothingIsCutShortAt320InEitherTheme() {
		AgentContext.Report r = new AgentContext.Report("", 180_000, 26_007, 153_993,
			"13,168 total (12,000 in, 1,168 out, 9,000 cached)", List.of(), List.of(), List.of(), List.of(),
			List.of("calendar.read", "mail.send"), List.of(), "{}");
		for (String theme : List.of(LAF.TAU_DARK, LAF.TAU_LIGHT)) {
			LAF.init(theme, null, null, null);
			// The column, less the scroll bar's room.
			int width = ShellLayout.SIDE - 12;
			JComponent overview = ContextInspector.overviewFor(r);
			overview.setSize(width, 2000);
			for (ComponentListener l : overview.getComponentListeners()) {
				l.componentResized(new ComponentEvent(overview, ComponentEvent.COMPONENT_RESIZED));
			}
			for (int pass = 0; pass < 3; pass++) {
				overview.setSize(width, Math.max(400, overview.getPreferredSize().height));
				layout(overview);
			}
			List<String> problems = new ArrayList<>();
			check(overview, overview, width, problems);
			assertTrue(problems.isEmpty(), theme + ": " + String.join("\n  ", problems));
		}
	}

	private static void layout(Container c) {
		c.doLayout();
		for (Component child : c.getComponents()) {
			if (child instanceof Container k) layout(k);
		}
	}

	private static void check(Component c, JComponent root, int width, List<String> problems) {
		if (c instanceof JLabel label && label.getText() != null && !label.getText().isBlank()) {
			if (label.getWidth() + 1 < label.getPreferredSize().width) {
				problems.add("cut short: '" + label.getText() + "' (" + label.getWidth() + " of "
					+ label.getPreferredSize().width + ")");
			}
		}
		if (c.isVisible() && c.getWidth() > 0 && c != root) {
			int right = SwingUtilities.convertPoint(c.getParent(), c.getX() + c.getWidth(), 0, root).x;
			if (right > width + 1) problems.add("runs past the edge: " + c.getClass().getSimpleName() + " to " + right);
		}
		if (c instanceof Container k) {
			for (Component child : k.getComponents()) check(child, root, width, problems);
		}
	}
}
