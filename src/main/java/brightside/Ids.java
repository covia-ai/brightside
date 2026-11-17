package brightside;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;

/**
 * Venue ids — job, session and Inbox request ids — as Brightside holds them:
 * hex text without {@code 0x}. The venue keeps ids as blobs; an older record
 * may still hold one as text, which is taken as it is. Reading an id the other
 * way, from text a caller gave, is Covia's {@code HexId}.
 */
public final class Ids {

	private Ids() {}

	/** The id in a record's field as hex text, or null when the field holds no id. */
	public static String text(ACell cell) {
		if (cell instanceof ABlob b) return b.toHexString();
		if (cell instanceof AString s) return s.toString();
		return null;
	}
}
