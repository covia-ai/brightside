package brightside;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import covia.venue.RequestContext;

/**
 * How many job records a user's namespace holds — what a screen's reads must
 * not add to. Actions leave a record; reads run as transient jobs and leave
 * none.
 */
final class RecordedJobs {

	private RecordedJobs() {
	}

	static long of(EmbeddedVenue venue, String userDID) {
		Index<Blob, ACell> jobs = venue.engine().jobs().getJobs(RequestContext.of(Strings.create(userDID)));
		return (jobs != null) ? jobs.count() : 0;
	}
}
