package brightside.ui.inbox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import brightside.Inbox;
import brightside.ui.binding.LatticeBinding;
import convex.core.data.ACell;
import convex.core.data.AVector;

/**
 * One principal's Inbox view. Cards, counts and notifications consume the
 * same projection. Existing requests form a quiet initial baseline; only
 * newly observed open requests notify. All methods run on the event thread.
 */
public final class InboxBinding implements AutoCloseable {

	private final LatticeBinding<AVector<ACell>, List<Inbox.Request>> binding;
	private final Set<String> notified = new HashSet<>();
	private List<Inbox.Request> requests = List.of();
	private boolean initial = true;

	public InboxBinding(Inbox.Source source, BooleanSupplier active,
			Consumer<List<Inbox.Request>> changed, Consumer<Inbox.Request> arrived,
			Consumer<Throwable> failed) {
		binding = new LatticeBinding<>(source::read, source::project, active, next -> {
			requests = next;
			changed.accept(next);
			for (Inbox.Request request : next) {
				if (request.open() && notified.add(key(request)) && !initial) arrived.accept(request);
			}
			initial = false;
		}, failed, 2500);
	}

	/** Owner and id together identify a request, including in a composed inbox. */
	static String key(Inbox.Request request) {
		return request.owner() + "/" + request.id();
	}

	public List<Inbox.Request> requests() {
		return requests;
	}

	/** Only an open request in the current snapshot may supply the authority for a response. */
	public boolean canRespond(Inbox.Request request) {
		return request != null && request.open() && requests.contains(request);
	}

	public CompletableFuture<Void> start() {
		return binding.start();
	}

	public CompletableFuture<Void> refresh() {
		return binding.refresh();
	}

	@Override
	public void close() {
		binding.close();
		requests = List.of();
		notified.clear();
	}
}
