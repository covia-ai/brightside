package brightside;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.venue.RequestContext;

/** Personal DLFS storage, always bound to a captured principal rather than the selected agent. */
public final class PersonalDrives {

	public static final String DEFAULT = "personal";
	static final String INITIALISED = "w/settings/storage/default-drive-initialised";
	private final EmbeddedVenue venue;
	private final String did;

	public PersonalDrives(EmbeddedVenue venue, String did) {
		this.venue = venue;
		this.did = did;
	}

	public List<String> list() throws Exception {
		DLFSAdapter adapter = (DLFSAdapter) venue.engine().getAdapter("dlfs");
		if (adapter == null) throw new IllegalStateException("Personal drives are unavailable.");
		ACell result = adapter.listDrives(RequestContext.of(Strings.create(did)));
		List<String> names = new ArrayList<>();
		if (RT.getIn(result, "drives") instanceof AVector<?> drives) {
			for (long i = 0; i < drives.count(); i++) names.add(drives.get(i).toString());
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return List.copyOf(names);
	}

	/** Once per user; an intentionally deleted default stays deleted on later launches. */
	public void initialise() throws Exception {
		synchronized (PersonalDrives.class) {
			if (CVMBool.TRUE.equals(venue.resolve(did, INITIALISED))) return;
			if (list().isEmpty()) create(DEFAULT);
			venue.clientAs(did).run("v/ops/covia/write", Maps.of("path", INITIALISED, "value", true))
				.get(5, TimeUnit.SECONDS);
		}
	}

	public void create(String name) throws Exception {
		if (name == null || name.isBlank() || name.length() > 64 || name.matches(".*[/\\\\:].*")) {
			throw new IllegalArgumentException("Use a drive name of 1–64 characters without slashes or colons.");
		}
		if (list().contains(name)) throw new IllegalArgumentException("That drive already exists.");
		venue.clientAs(did).run("v/ops/dlfs/create-drive", Maps.of("name", name)).get(5, TimeUnit.SECONDS);
	}

}
