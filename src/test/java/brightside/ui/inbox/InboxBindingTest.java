package brightside.ui.inbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import brightside.Identity;
import brightside.Inbox;
import brightside.SharedVenue;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.hitl.Hitl;
import covia.venue.HitlRequest;
import covia.venue.User;
import covia.venue.Users;

/** Real domain components and binding lifecycle, without constructing Swing screens. */
class InboxBindingTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void sourceComposesTypedInboxesWithoutCreatingUsersOrJobs() {
		Users users = users();
		String owner = did("inbox-source-owner"), operator = did("inbox-source-operator");
		Inbox.Source source = new Inbox.Source(users, owner, operator);
		var before = users.value();
		var absent = source.read();
		assertNull(absent.get(0));
		assertNull(absent.get(1));
		assertEquals(before, users.value(), "reading absent accounts never creates them");
		User own = users.ensure(owner), ops = users.ensure(operator);
		var empty = source.read();
		assertEquals(Maps.empty(), empty.get(0));
		assertEquals(Maps.empty(), empty.get(1));
		assertNotEquals(absent, empty, "absence and a present empty inbox remain distinct snapshots");
		open(own, "one", 1);
		open(ops, "two", 2);
		var jobsBefore = own.jobs().getAll();
		List<Inbox.Request> requests = source.project(source.read());
		assertEquals(List.of("two", "one"), requests.stream().map(Inbox.Request::id).toList());
		assertEquals(List.of(operator, owner), requests.stream().map(Inbox.Request::owner).toList());
		assertEquals(2, Inbox.pending(requests));
		assertEquals(jobsBefore, own.jobs().getAll(), "a view read submits no job");
		Inbox.Source asOperator = new Inbox.Source(users, operator, operator);
		assertEquals(1, asOperator.project(asOperator.read()).size(), "the same inbox is composed only once");
	}

	@Test
	void countsAndArrivalsShareSnapshotsAndInitialRequestsStayQuiet() throws Exception {
		Users users = users();
		User owner = users.ensure(did("inbox-binding-owner"));
		User operator = users.ensure(did("inbox-binding-operator"));
		open(owner, "waiting", 1);
		Inbox.Source source = new Inbox.Source(users, owner.getDID().toString(), operator.getDID().toString());
		List<List<Inbox.Request>> snapshots = new ArrayList<>();
		List<Inbox.Request> arrivals = new ArrayList<>();
		InboxBinding binding = new InboxBinding(source, () -> true, snapshots::add, arrivals::add, error -> fail(error));
		try {
			settle(binding::refresh);
			assertEquals(1, snapshots.size());
			assertTrue(arrivals.isEmpty());
			settle(binding::refresh);
			assertEquals(1, snapshots.size(), "unchanged requests do not disturb the view");
			// Same local id under different owners exercises composed identity.
			open(owner, "new", 2);
			open(operator, "new", 3);
			settle(binding::refresh);
			assertEquals(2, arrivals.size());
			assertNotEquals(InboxBinding.key(arrivals.get(0)), InboxBinding.key(arrivals.get(1)));
			assertEquals(3, Inbox.pending(snapshots.get(1)));
			Inbox.Request pending = arrivals.get(0);
			assertTrue(binding.canRespond(pending));
			operator.inbox().request(Strings.create("new")).resolve(Strings.create("answered"), Maps.empty());
			settle(binding::refresh);
			assertFalse(binding.canRespond(pending), "an old form cannot choose authority for a changed request");
			assertEquals(2, Inbox.pending(snapshots.get(2)));
			assertEquals(2, arrivals.size(), "resolution and repeated snapshots do not notify again");
			owner.inbox().clear();
			operator.inbox().clear();
			settle(binding::refresh);
			assertTrue(snapshots.get(3).isEmpty(), "removal clears the cards and count");
			assertFalse(binding.canRespond(arrivals.get(1)));
		} finally {
			SwingUtilities.invokeAndWait(binding::close);
		}
		assertTrue(binding.requests().isEmpty());
		assertFalse(binding.canRespond(arrivals.get(1)), "a closed binding grants no response authority");
	}

	@Test
	void accountRemovalClearsOnlyItsPartOfTheBoundView() throws Exception {
		Users users = users();
		User owner = users.ensure(did("inbox-deleted-owner"));
		User operator = users.ensure(did("inbox-retained-operator"));
		open(owner, "removed", 1);
		open(operator, "retained", 2);
		Inbox.Source source = new Inbox.Source(users, owner.getDID().toString(), operator.getDID().toString());
		List<List<Inbox.Request>> snapshots = new ArrayList<>();
		InboxBinding binding = new InboxBinding(source, () -> true, snapshots::add, request -> {}, error -> fail(error));
		try {
			settle(binding::refresh);
			var engine = shared.venue().engine();
			engine.deleteUser(engine.venueContext(), owner.getDID());
			settle(binding::refresh);
			assertEquals(List.of("retained"), snapshots.get(1).stream().map(Inbox.Request::id).toList());
			assertNull(source.read().get(0));
		} finally {
			SwingUtilities.invokeAndWait(binding::close);
		}
	}

	private static Users users() {
		return shared.venue().engine().getVenueState().users();
	}

	private static String did(String name) {
		return Identity.of(name).userDID(shared.venue().did());
	}

	private static void open(User user, String id, long created) {
		assertTrue(user.inbox().open(HitlRequest.record(Strings.create(id), user.getDID(), user.getDID(),
			null, null, Strings.create(id), null, Vectors.empty(), created, null)));
	}

	private static void settle(Supplier<CompletableFuture<Void>> action) throws Exception {
		AtomicReference<CompletableFuture<Void>> started = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> started.set(action.get()));
		started.get().get(5, TimeUnit.SECONDS);
	}
}
