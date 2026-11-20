package brightside.ui.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JComponent;

import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.Inbox;

/**
 * The Inbox's decision cards: one filled primary in the whole view, verb
 * buttons for a single approval, the self-approval reason in plain words,
 * and the summary of what is waiting. Headless.
 */
class DecisionCardsTest {

	private static final long NOW = System.currentTimeMillis();
	private final List<String> answered = new ArrayList<>();
	private final InboxScreen screen = new InboxScreen(new RequestForm.Listener() {
		@Override
		public void onAnswer(Inbox.Request r, Inbox.Answer a) {
			answered.add(r.id() + " " + a.answers());
		}

		@Override
		public void onReject(Inbox.Request r, String reason) {
			answered.add(r.id() + " rejected");
		}
	});

	private static Inbox.Request approval(String id, String from, long expires) {
		Inbox.Ask ask = new Inbox.Ask("ok", "approval", "Send the weekly summary?", true, true, List.of(), List.of(),
			List.of());
		return new Inbox.Request(id, from, "Release notes", "Send email to 2 recipients", "Ready to send.",
			List.of(ask), covia.grid.hitl.Hitl.OPEN.toString(), NOW, expires, null, null);
	}

	@Test
	void onlyOneFilledPrimaryInTheWholeInbox() {
		screen.setRequests(List.of(approval("r1", "did:a", 0), approval("r2", "did:a", 0), approval("r3", "did:a", 0)));
		screen.renderNow();
		assertEquals(1, filledPrimaries(screen), "one filled primary across every open card");
		// Working in another card moves it there.
		RequestCard second = screen.cardsShown().get(1);
		second.form().setPrimaryActive(true);
		screen.cardsShown().get(0).form().setPrimaryActive(false);
		assertEquals(1, filledPrimaries(screen));
	}

	@Test
	void theFilledPrimarySurvivesANewSetOfRequests() {
		screen.setRequests(List.of(approval("r1", "did:a", 0)));
		screen.renderNow();
		screen.setRequests(List.of(approval("r1", "did:a", NOW + 60_000), approval("r2", "did:a", NOW + 60_000)));
		screen.renderNow();
		assertEquals(1, filledPrimaries(screen));
	}

	@Test
	void aSingleApprovalGetsVerbButtons() {
		screen.setRequests(List.of(approval("r1", "did:a", 0)));
		screen.renderNow();
		RequestForm form = screen.cardsShown().get(0).form();
		assertEquals("Approve", form.answerButton.getText());
		assertEquals("Decline", form.declineButton.getText());
		assertTrue(!form.single().yes.isVisible() && !form.single().no.isVisible(), "the verb buttons answer it");
		form.declineButton.doClick();
		assertEquals(List.of("r1 {ok=false}"), answered);
	}

	@Test
	void anAgentCannotApproveItsOwnRequestAndTheCardSaysWhy() {
		screen.setActing("did:agent");
		screen.setRequests(List.of(approval("r1", "did:agent", 0), approval("r2", "did:someone-else", 0)));
		screen.renderNow();
		RequestForm own = screen.cardsShown().get(0).form();
		RequestForm other = screen.cardsShown().get(1).form();
		assertTrue(own.selfRequest());
		assertFalse(own.answerButton.isEnabled());
		assertTrue(own.selfNote.isVisible());
		assertTrue(own.selfNote.getText().contains("Switch to the owner"));
		assertTrue(own.declineButton.isEnabled() || own.rejectButton.isEnabled(), "it can still be turned down");
		assertFalse(other.selfRequest());
		assertTrue(other.answerButton.isEnabled());
	}

	@Test
	void theSummarySaysTheSoonestExpiry() {
		assertEquals("Next expires in 28 min", InboxScreen.summary(
			List.of(approval("a", "x", NOW + 28 * 60_000L - 1000), approval("b", "x", NOW + 90 * 60_000L)), NOW));
		assertEquals(null, InboxScreen.summary(List.of(approval("a", "x", 0)), NOW));
	}

	private static int filledPrimaries(Component c) {
		int n = 0;
		if (c instanceof AbstractButton b && c instanceof JComponent j
				&& "primary".equals(j.getClientProperty(FlatClientProperties.STYLE_CLASS)) && visibleInTree(b)) {
			n++;
		}
		if (c instanceof Container k) for (Component child : k.getComponents()) n += filledPrimaries(child);
		return n;
	}

	/** Visible as far as the tree goes (no window when headless): every ancestor visible, folded bodies excluded. */
	private static boolean visibleInTree(Component c) {
		for (Component p = c; p != null; p = p.getParent()) if (!p.isVisible()) return false;
		return true;
	}
}
