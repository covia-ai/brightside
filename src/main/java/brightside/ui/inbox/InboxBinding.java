package brightside.ui.inbox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import brightside.Inbox;
import brightside.ui.binding.Watcher;
import convex.core.data.ACell;
import convex.core.data.AVector;

/**
 * One principal's Inbox, observed from the venue's lattice for as long as it
 * is bound, whatever is on screen ({@link Watcher#observe}), so a request that
 * arrives while the window is minimised or in the tray is still noticed.
 * Cards, counts and notifications consume the same projection. Existing
 * requests form a quiet initial baseline; only newly observed open requests
 * notify. All methods run on the event thread.
 */
public final class InboxBinding implements AutoCloseable {

	private final Inbox.Source source;
	private final Consumer<List<Inbox.Request>> changed;
	private final Consumer<Inbox.Request> arrived;
	private final Set<String> notified = new HashSet<>();
	private Watcher.Watch<AVector<ACell>> watch;
	private List<Inbox.Request> requests = List.of();
	private boolean initial = true;

	public InboxBinding(Inbox.Source source, Consumer<List<Inbox.Request>> changed, Consumer<Inbox.Request> arrived) {
		this.source = source;
		this.changed = changed;
		this.arrived = arrived;
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

	/** Starts observing the inbox. */
	public void start() {
		if (watch == null) watch = Watcher.observe(source::read, this::accept);
	}

	/** Reads the inbox now rather than at the next pass, as after a response. */
	public void refresh() {
		if (watch != null) watch.refresh();
	}

	/** A new value of the inbox, as the watcher reads it. */
	void accept(AVector<ACell> value) {
		List<Inbox.Request> next = source.project(value);
		requests = next;
		changed.accept(next);
		for (Inbox.Request request : next) {
			if (request.open() && notified.add(key(request)) && !initial) arrived.accept(request);
		}
		initial = false;
	}

	@Override
	public void close() {
		if (watch != null) watch.close();
		requests = List.of();
		notified.clear();
	}
}
