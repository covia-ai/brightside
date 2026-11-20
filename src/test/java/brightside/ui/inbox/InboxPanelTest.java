package brightside.ui.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import brightside.Inbox;

/** The docked Inbox panel lists only the requests still waiting, and opens each in the Inbox. Headless. */
class InboxPanelTest {

	private static Inbox.Request request(String id, String status) {
		Inbox.Ask ask = new Inbox.Ask("ok", "approval", "Go?", true, false, List.of(), List.of(), List.of());
		return new Inbox.Request(id, "did:a", "Release notes", "Send it", null, List.of(ask), status,
			System.currentTimeMillis(), 0, null, null);
	}

	@Test
	void listsOnlyWhatIsWaiting() {
		List<Inbox.Request> opened = new ArrayList<>();
		InboxPanel panel = new InboxPanel(opened::add);
		panel.setRequests(List.of(request("a", covia.grid.hitl.Hitl.OPEN.toString()), request("b", "answered"),
			request("c", covia.grid.hitl.Hitl.OPEN.toString())));
		assertEquals(2, panel.waiting());
		panel.setRequests(List.of());
		assertEquals(0, panel.waiting());
	}
}
