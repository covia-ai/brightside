package brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.SessionHistory;

/**
 * A conversation with activity since it was last seen open is marked new, in
 * words as well as a dot; nothing is marked on first sight, and opening it
 * clears the mark. Headless.
 */
class AttentionDotsTest {

	private final ConversationList list = new ConversationList((ConversationList.Listener) Proxy.newProxyInstance(
		getClass().getClassLoader(), new Class<?>[] { ConversationList.Listener.class }, (p, m, a) -> null));

	@Test
	void laterActivityMarksARowNewUntilOpened() {
		SessionHistory.Session a1 = new SessionHistory.Session("a", "Plan the week", 1000, false);
		SessionHistory.Session b1 = new SessionHistory.Session("b", "Release notes", 1000, false);
		list.setSessions(List.of(a1, b1), "a");
		assertEquals(List.of(), fresh(), "nothing is new on first sight");

		SessionHistory.Session b2 = new SessionHistory.Session("b", "Release notes", 2000, false);
		list.setSessions(List.of(a1, b2), "a");
		assertEquals(List.of("Release notes, new activity"), fresh());

		list.setSessions(List.of(a1, b2), "b");
		assertEquals(List.of(), fresh(), "opening it clears the mark");
	}

	private List<String> fresh() {
		List<String> out = new ArrayList<>();
		collect(list, out);
		return out;
	}

	private static void collect(Component c, List<String> out) {
		if (c instanceof javax.swing.JComponent j && j.getAccessibleContext() != null) {
			String n = j.getAccessibleContext().getAccessibleName();
			if (n != null && n.endsWith(", new activity")) out.add(n);
		}
		if (c instanceof Container k) for (Component child : k.getComponents()) collect(child, out);
	}
}
