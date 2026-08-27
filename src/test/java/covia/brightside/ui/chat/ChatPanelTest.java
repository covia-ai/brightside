package covia.brightside.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultEditorKit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.brightside.AppConfig;
import covia.brightside.BrightsideAdapter;
import covia.brightside.BrightsideSkillsAdapter;
import covia.brightside.Identity;
import covia.brightside.SessionHistory;
import covia.brightside.chat.ChatSession;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.LocalVenue;

final class ChatPanelTest {

	@Test
	void composerMatchesTheSendButtonAndGrowsForMultilineInput() throws Exception {
		AtomicReference<ChatPanel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> result.set(new ChatPanel()));
		ChatPanel panel = result.get();
		JTextArea input = descendants(panel, JTextArea.class).getFirst();
		JButton send = descendants(panel, JButton.class).stream()
			.filter(b -> "Send".equals(b.getText())).findFirst().orElseThrow();
		JScrollPane composer = descendants(panel, JScrollPane.class).stream()
			.filter(s -> s.getViewport().getView() == input).findFirst().orElseThrow();

		int initialHeight = composer.getPreferredSize().height;
		assertEquals(initialHeight, send.getPreferredSize().height);
		assertEquals("send", input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
		assertEquals(DefaultEditorKit.insertBreakAction,
			input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)));
		assertEquals(DefaultEditorKit.insertBreakAction,
			input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)));

		SwingUtilities.invokeAndWait(() -> input.setText("one\ntwo\nthree"));
		SwingUtilities.invokeAndWait(() -> {
		}); // flush the deferred resize
		assertEquals(3, input.getRows());
		assertTrue(composer.getPreferredSize().height > initialHeight);

		SwingUtilities.invokeAndWait(() -> input.setText("one\ntwo\nthree\nfour\nfive\nsix\nseven"));
		SwingUtilities.invokeAndWait(() -> {
		});
		assertEquals(6, input.getRows());

		SwingUtilities.invokeAndWait(() -> input.setText(""));
		SwingUtilities.invokeAndWait(() -> {
		});
		assertEquals(1, input.getRows());
		assertEquals(initialHeight, composer.getPreferredSize().height);
	}

	@Test
	void emptyStateOnlyOccupiesAnEmptyTranscript() throws Exception {
		AtomicReference<ChatPanel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> result.set(new ChatPanel()));
		ChatPanel panel = result.get();
		assertEquals(1, descendants(panel, EmptyChatState.class).size());

		SwingUtilities.invokeAndWait(() -> panel.restore(List.of(
			new SessionHistory.Message("assistant", "A completed reply"))));
		assertTrue(descendants(panel, EmptyChatState.class).isEmpty());

		SwingUtilities.invokeAndWait(() -> panel.restore(List.of()));
		assertEquals(1, descendants(panel, EmptyChatState.class).size());
	}

	@Test
	void composerAcceptsAFollowUpDuringAnAgentCycle() throws Exception {
		Engine engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
		engine.registerAdapter(new BrightsideAdapter());
		engine.registerAdapter(new BrightsideSkillsAdapter());
		LocalVenue venue = LocalVenue.create(engine);
		venue.setUser(Identity.of("queue-ui").userDID(engine.getDIDString().toString()));

		String agentId = "queue-ui-agent";
		ChatSession session = new ChatSession(venue, new AppConfig.Chat(agentId,
			"v/test/ops/taskcomplete", AppConfig.ECHO_LLM_OPERATION, "", 30));
		session.ensureAgent();
		String gateName = "brightside-queue-ui";
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			venue.invoke("v/ops/agent/update", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of("testGate", gateName)))
				.get(5, TimeUnit.SECONDS).future().get(5, TimeUnit.SECONDS);

			AtomicReference<ChatPanel> result = new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				ChatPanel panel = new ChatPanel();
				panel.setSession(session);
				result.set(panel);
			});
			ChatPanel panel = result.get();
			JTextArea input = descendants(panel, JTextArea.class).getFirst();
			JButton send = descendants(panel, JButton.class).stream()
				.filter(b -> "Send".equals(b.getText())).findFirst().orElseThrow();

			SwingUtilities.invokeAndWait(() -> {
				input.setText("first");
				send.doClick();
			});
			assertTrue(gate.awaitEntered(5, TimeUnit.SECONDS), "the first agent cycle is held open");
			assertTrue(session.sessionId() != null, "the active session is known before the reply");
			SwingUtilities.invokeAndWait(() ->
				assertEquals(1, descendants(panel, ThinkingBubble.class).size()));

			SwingUtilities.invokeAndWait(() -> {
				assertTrue(input.isEnabled(), "the composer stays enabled while the agent is running");
				assertTrue(send.isEnabled(), "Send stays enabled while the agent is running");
				input.setText("follow-up");
				send.doClick();
				assertTrue(input.getText().isEmpty(), "the follow-up was accepted by the composer");
			});

			String sid = session.sessionId();
			await(() -> {
				ACell record = SessionHistory.readAgentValue(venue, agentId);
				ACell pending = RT.getIn(record, "sessions", Blob.fromHex(sid), "pending");
				return pending instanceof AVector<?> vector && vector.count() >= 2;
			}, 5_000);
			gate.release();
			await(() -> !SessionHistory.isSessionActive(
				SessionHistory.readAgentValue(venue, agentId), sid), 5_000);
			await(() -> {
				AtomicReference<Boolean> hidden = new AtomicReference<>();
				try {
					SwingUtilities.invokeAndWait(() ->
						hidden.set(descendants(panel, ThinkingBubble.class).isEmpty()));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return hidden.get();
			}, 5_000);
			SwingUtilities.invokeAndWait(panel::clearSession);
		} finally {
			engine.close();
		}
	}

	private static void await(BooleanSupplier condition, long timeoutMs) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() >= deadline) throw new AssertionError("condition was not reached");
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
		}
	}

	private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
		List<T> found = new ArrayList<>();
		if (type.isInstance(root)) found.add(type.cast(root));
		if (root instanceof Container container) {
			for (Component child : container.getComponents()) found.addAll(descendants(child, type));
		}
		return found;
	}
}
