package covia.brightside.ui.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import covia.brightside.Inbox;

/** The form answers exactly what was entered, and a grant is echoed only when its choice is made and its box ticked. */
final class RequestFormTest {

	private static final Inbox.Grant GRANT = new Inbox.Grant("w/payments/", "crud/read", null);

	@Test
	void collectsTypedAnswersAndOnlyTheGrantsTheOwnerTicks() throws Exception {
		Inbox.Request r = new Inbox.Request("abc", "did:key:z6Mk:u:me", "Bob", "Pay invoice", "Acme", List.of(
			new Inbox.Ask("pay", "approval", "Approve payment?", true, false, List.of(), List.of(GRANT), List.of()),
			new Inbox.Ask("note", "text", "Anything to add?", false, true, List.of(), List.of(), List.of()),
			new Inbox.Ask("when", "choice", "When?", false, false,
				List.of(new Inbox.Option("now", "Now", List.of()), new Inbox.Option("later", "Later", List.of())),
				List.of(), List.of()),
			new Inbox.Ask("notify", "checkboxes", "Notify by", false, false,
				List.of(new Inbox.Option("email", "Email", List.of()), new Inbox.Option("sms", "SMS", List.of())),
				List.of(), List.of())),
			"open", 1L, 0L, null, null);
		List<Inbox.Answer> answered = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		AtomicReference<RequestForm> ref = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> ref.set(new RequestForm(r, new RequestForm.Listener() {
			@Override
			public void onAnswer(String id, Inbox.Answer answer) {
				ids.add(id);
				answered.add(answer);
			}

			@Override
			public void onReject(String id, String reason) {
			}
		})));
		RequestForm form = ref.get();

		AtomicReference<Inbox.Answer> got = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> got.set(form.collect()));
		assertNull(got.get(), "a required ask left unanswered blocks the answer");

		RequestForm.ApprovalInput pay = (RequestForm.ApprovalInput) form.input("pay");
		JCheckBox consent = pay.grantBoxes.getFirst().box();
		assertFalse(consent.isEnabled(), "an offered grant is inert until the approval is given");

		SwingUtilities.invokeAndWait(() -> {
			pay.yes.setSelected(true);
			consent.setSelected(true);
			((RequestForm.TextInput) form.input("note")).text.setText("Fine");
			form.input("note").comment.setText("per-ask");
			((RequestForm.ChoiceInput) form.input("when")).buttons.get("now").setSelected(true);
			((RequestForm.CheckboxesInput) form.input("notify")).boxes.get("email").setSelected(true);
			form.comment.setText("Approved once");
			form.answerButton.doClick();
		});
		assertTrue(consent.isEnabled());
		assertEquals(List.of("abc"), ids);
		Inbox.Answer a = answered.getFirst();
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("pay", true);
		expected.put("note", "Fine");
		expected.put("when", "now");
		expected.put("notify", List.of("email"));
		assertEquals(expected, a.answers());
		assertEquals(Map.of("note", "per-ask"), a.comments());
		assertEquals(List.of(GRANT), a.echoes());
		assertEquals("Approved once", a.comment());
		assertFalse(form.answerButton.isEnabled(), "buttons rest while the response is in flight");

		// Declining withdraws the consent box, so nothing is echoed.
		SwingUtilities.invokeAndWait(() -> {
			form.setBusy(false);
			pay.no.setSelected(true);
			got.set(form.collect());
		});
		assertFalse(consent.isEnabled());
		assertEquals(Boolean.FALSE, got.get().answers().get("pay"));
		assertTrue(got.get().echoes().isEmpty());
	}

	@Test
	void aResolvedRequestShowsItsResponseWithoutControls() throws Exception {
		Inbox.Request r = new Inbox.Request("abc", "did:key:z6Mk:u:me", "Bob", "Pay invoice", null, List.of(
			new Inbox.Ask("pay", "approval", "Approve payment?", true, false, List.of(), List.of(GRANT), List.of())),
			"answered", 1L, 0L, new Inbox.Response("answer", Map.of("pay", "Approved"), "ok", List.of(GRANT)), null);
		AtomicReference<RequestForm> ref = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> ref.set(new RequestForm(r, new RequestForm.Listener() {
			@Override
			public void onAnswer(String id, Inbox.Answer answer) {
			}

			@Override
			public void onReject(String id, String reason) {
			}
		})));
		RequestForm form = ref.get();
		assertNull(form.answerButton.getParent(), "no Answer button on a resolved request");
		assertFalse(((RequestForm.ApprovalInput) form.input("pay")).yes.isEnabled());
	}
}
