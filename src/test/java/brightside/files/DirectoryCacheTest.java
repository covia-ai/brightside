package brightside.files;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import brightside.files.DirectoryCache.Location;
import brightside.files.FileSource.Entry;
import brightside.files.FileSource.Listing;
import brightside.files.FileSource.Root;

class DirectoryCacheTest {

	private static final Root ROOT = new Root("dlfs:personal", "personal", "Personal drive");
	private static final Location HOME = new Location(ROOT, "");

	@Test void expansionAndSelectionShareOneLazyReadWithoutWalkingDescendants() {
		Queue executor = new Queue();
		Source source = new Source();
		DirectoryCache cache = new DirectoryCache(source, executor);
		assertEquals(0, source.calls);
		var expanded = cache.list(HOME);
		var selected = cache.list(HOME);
		assertSame(expanded, selected);
		assertFalse(selected.isDone());
		executor.next();
		assertEquals(1, source.calls);
		assertEquals("Notes", selected.join().entries().get(0).name());
		assertTrue(executor.tasks.isEmpty(), "reading a parent must not scan its children");
		var child = cache.list(HOME.child("Notes"));
		assertFalse(child.isDone());
		executor.next();
		assertEquals(2, source.calls);
		assertEquals(HOME, HOME.child("Notes").parent());
	}

	@Test void refreshAndPrincipalBindingsCannotReuseOldCompletions() {
		Queue executor = new Queue();
		Source source = new Source();
		DirectoryCache cache = new DirectoryCache(source, executor);
		var old = cache.list(HOME);
		cache.clear();
		var fresh = cache.list(HOME);
		assertNotSame(old, fresh);
		executor.next();
		assertTrue(old.isDone());
		assertFalse(fresh.isDone());
		assertSame(fresh, cache.list(HOME), "late reads cannot overwrite the refreshed cache");
		executor.next();
		DirectoryCache otherUser = new DirectoryCache(new Source(), executor);
		assertNotSame(fresh, otherUser.list(HOME));
		executor.next();
		assertEquals(2, source.calls);
	}

	@Test void failedReadsCanBeRetried() {
		Queue executor = new Queue();
		Source source = new Source();
		source.fail = true;
		DirectoryCache cache = new DirectoryCache(source, executor);
		var failed = cache.list(HOME);
		executor.next();
		assertThrows(CompletionException.class, failed::join);
		source.fail = false;
		var retry = cache.list(HOME);
		assertNotSame(failed, retry);
		executor.next();
		assertEquals(1, retry.join().entries().size());
		assertEquals(2, source.calls);
	}

	private static final class Queue implements Executor {
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		@Override public void execute(Runnable task) { tasks.add(task); }
		void next() { tasks.remove().run(); }
	}

	private static final class Source implements FileSource {
		int calls;
		boolean fail;
		@Override public List<Root> roots() { return List.of(ROOT); }
		@Override public Listing list(Root root, String path) throws IOException {
			calls++;
			if (fail) throw new IOException("Unavailable");
			return new Listing(List.of(new Entry("Notes", "directory", 0, 0)), "");
		}
		@Override public Preview preview(Root root, String path) { return new Preview("", false, false); }
	}
}
