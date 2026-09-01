package brightside;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The owner's assistant on Discord: one bot, owned by the user, answering as
 * their chat agent.
 *
 * <p>Covia's {@code covia-discord} adapter does the work — the Gateway session,
 * per-channel sessions, replies, the allow-list — and persists the bot it is
 * asked to create, re-arming it at every launch. Brightside adds only what a
 * desktop owner needs: the token lives in the owner's encrypted secret store on
 * the venue (as {@code DISCORD_BOT_TOKEN}, referenced by the bot record as
 * {@code s/DISCORD_BOT_TOKEN}), and Settings → Integrations creates, shows and
 * removes the bot through the adapter's own operations. Nothing is written to
 * {@code config.json}.
 *
 * <p>Access fails closed: only the Discord users the owner lists may talk to the
 * bot. A stranger who DMs it is told their own user id, which is what the
 * owner pastes into the list. Direct messages always reach the assistant; in a
 * server it answers only when mentioned.
 */
public final class Discord {

	/** The bot's name in the adapter — the owner has one. */
	public static final String BOT = "brightside";
	/** The vault/secret name of the bot token. */
	public static final String TOKEN_SECRET = "DISCORD_BOT_TOKEN";

	private static final String OP_SECRET_SET = "v/ops/secret/set";
	private static final String OP_CREATE = "v/ops/discord/create";
	private static final String OP_DELETE = "v/ops/discord/delete";
	private static final String OP_BOTS = "v/ops/discord/bots";
	private static final long TIMEOUT_SECONDS = 30;

	/**
	 * The bot as the adapter reports it: its {@code state} ({@code STARTING},
	 * {@code PENDING}, {@code RUNNING}, {@code STOPPED}), the Discord
	 * {@code username} once connected, an {@code error} while pending, the users
	 * it {@code allows}, and its message counters.
	 */
	public record Bot(String state, String username, String error, List<String> allows, long received, long sent) {
		public boolean running() {
			return "RUNNING".equals(state);
		}
	}

	private Discord() {
	}

	/**
	 * Creates (or replaces) the owner's bot as {@code user}: stores the token in
	 * the user's secret store when one is given, then declares the bot answering
	 * as {@code agentId} for the listed Discord users. The adapter starts it and
	 * keeps it across restarts.
	 */
	public static void configure(Venue user, String agentId, String token, List<String> allow) throws Exception {
		if (token != null && !token.isBlank()) {
			run(user, OP_SECRET_SET, Maps.of("name", TOKEN_SECRET, "value", token.trim()));
		}
		try {
			run(user, OP_DELETE, Maps.of("name", BOT));
		} catch (Exception e) {
			// nothing to replace
		}
		AVector<ACell> allowed = Vectors.empty();
		for (String a : allow) {
			String s = a.strip();
			if (!s.isEmpty()) allowed = allowed.conj(Strings.create(s));
		}
		run(user, OP_CREATE, Maps.of(
			"name", BOT,
			"token", "s/" + TOKEN_SECRET,
			"agent", agentId,
			"allow", allowed,
			"mentionOnly", CVMBool.TRUE));
	}

	/** Removes the owner's bot and its channel sessions. */
	public static void remove(Venue user) throws Exception {
		run(user, OP_DELETE, Maps.of("name", BOT));
	}

	/**
	 * The owner's bot as the adapter sees it, or null when there is none.
	 * Read directly in-process — visiting Settings must not write jobs.
	 * {@code record} is the persisted bot record (for the allow-list), read
	 * in-process by the operator from the adapter's workspace, or null.
	 */
	public static Bot status(EmbeddedVenue venue, String userDID, ACell record) throws Exception {
		ACell out = venue.invokeAdapterDirect("discord:bots", userDID, Maps.empty(), TIMEOUT_SECONDS);
		if (!(RT.getIn(out, "bots") instanceof AVector<?> bots)) return null;
		for (long i = 0; i < bots.count(); i++) {
			ACell b = (ACell) bots.get(i);
			if (!BOT.equals(str(RT.getIn(b, "name")))) continue;
			return new Bot(str(RT.getIn(b, "state")), str(RT.getIn(b, "username")), str(RT.getIn(b, "error")),
				allows(record), lng(RT.getIn(b, "received")), lng(RT.getIn(b, "sent")));
		}
		return null;
	}

	/** Where the adapter keeps the owner's runtime bot record — under the venue's own workspace. */
	public static String recordPath(String userDID) {
		return "w/adapters/discord/users/" + userDID + "/bots/" + BOT;
	}

	private static List<String> allows(ACell record) {
		List<String> out = new ArrayList<>();
		if (RT.getIn(record, "allow") instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) out.add(String.valueOf(v.get(i)));
		}
		return List.copyOf(out);
	}

	private static ACell run(Venue client, String op, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(op, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	private static long lng(ACell cell) {
		return (cell instanceof convex.core.data.prim.CVMLong l) ? l.longValue() : 0L;
	}
}
