package brightside;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.venue.Engine;
import covia.venue.UcanJwtValidator;

/**
 * Short-lived grants the venue signs over its own resources and the users it
 * issued, for one call to present: how the gods' bridges and the treasury lend
 * exactly the authority a call needs and no more. Each is the same claims
 * {@code ucan:issue} mints, and passes through Covia's own JWT trust boundary
 * rather than being trusted by construction. The venue never signs for a
 * foreign DID.
 */
public final class VenueGrants {

	/** Lifetime of a grant: long enough to submit one call, never to keep. */
	public static final long SECONDS = 300;

	private final Engine engine;

	public VenueGrants(Engine engine) {
		this.engine = engine;
	}

	/** A grant to {@code aud} of the capabilities {@code att}, as the verified token map a request context presents. */
	public ACell grant(AString aud, AVector<ACell> att) {
		long now = engine.currentTimestamp().longValue() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			UCAN.ISS, engine.getDIDString(),
			UCAN.AUD, aud,
			UCAN.UCV, UCAN.VERSION,
			UCAN.ATT, att,
			UCAN.PRF, Vectors.empty());
		claims = claims.assoc(UCAN.EXP, CVMLong.create(now + SECONDS));
		AString jwt = JWT.signPublic(claims, engine.getKeyPair());
		UCAN token = UcanJwtValidator.validateJWT(jwt, now, engine.didVerifier());
		if (token == null) throw new IllegalStateException("The venue could not verify its own delegation");
		return token.toMap();
	}

	/** One capability: {@code can} over {@code with}. */
	public static AMap<AString, ACell> cap(AString with, AString can) {
		return Maps.of(Capability.WITH, with, Capability.CAN, can);
	}
}
