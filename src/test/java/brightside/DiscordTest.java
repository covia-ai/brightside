package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.AppConfig;
import brightside.Discord;
import brightside.EmbeddedVenue;
import brightside.Identity;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Venue;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * The Discord adapter is part of the embedded venue, and the owner's bot goes
 * through it: created as the user with the token held as a secret, listed with
 * the allow-list it was given, and removed again. No Discord is reached — an
 * unusable token leaves the bot pending, which is what the adapter reports.
 */
class DiscordTest {

	@TempDir
	static Path home;

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;
	private static ExpectedLogs.Scope gatewayLogs;
	private static ExpectedLogs.Scope requestLogs;

	@BeforeAll
	static void boot() throws IOException {
		// The fake token cannot connect. Keep filtering through shutdown: startup runs asynchronously.
		gatewayLogs = ExpectedLogs.expect("covia.adapter.discord.BotRunner", ch.qos.logback.classic.Level.WARN,
			"Discord bot 'brightside' not started: Discord Gateway connection failed:");
		requestLogs = ExpectedLogs.expect("net.dv8tion.jda.internal.requests.Requester", ch.qos.logback.classic.Level.ERROR,
			"There was an I/O error while executing a REST request:");
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		AMap<AString, ACell> config = AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(port));
		venue = EmbeddedVenue.launch(config);
		userDID = Identity.of("owner").userDID(venue.did());
		user = venue.clientAs(userDID);
	}

	@AfterAll
	static void shutdown() {
		try {
			if (venue != null) venue.close();
		} finally {
			if (requestLogs != null) requestLogs.close();
			if (gatewayLogs != null) gatewayLogs.close();
		}
	}

	@Test
	void theAdapterIsOnTheVenue() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : new String[] { "v/ops/discord/create", "v/ops/discord/delete", "v/ops/discord/bots",
			"v/ops/discord/send", "v/ops/secret/set" }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(op), ctx), "op present: " + op);
		}
		ACell skill = venue.engine().resolvePath(Strings.create("v/skills/adapters/discord"), ctx);
		assertNotNull(skill, "the module's skill is installed for the assistant to discover");
		// The venue jar ships a different skills/discord.json (covia-ai/covia#510);
		// the module's must be the one on the venue, or the assistant is taught
		// raw REST instead of the adapter's operations.
		assertTrue(String.valueOf(RT.getIn(skill, "skill", "tools")).contains("v/ops/discord/send"),
			"the module's own skill, not the venue's generic copy: " + RT.getIn(skill, "skill", "tools"));
	}

	@Test
	void theOwnersBotIsCreatedListedAndRemovedAsTheUser() throws Exception {
		assertNull(Discord.status(user, null), "nothing before setup");

		Discord.configure(user, "Brightside", "not-a-real-token", List.of("123456789012345678", " @mike "));

		ACell record = venue.resolve(venue.did(), Discord.recordPath(userDID));
		assertNotNull(record, "the adapter persisted the bot under its own workspace");
		assertEquals(Strings.create("s/" + Discord.TOKEN_SECRET), RT.getIn(record, "token"),
			"the record holds a secret reference, never the token");
		assertEquals(Strings.create("Brightside"), RT.getIn(record, "agent"));

		long jobsBefore = RecordedJobs.of(venue, userDID);
		Discord.Bot bot = Discord.status(user, record);
		assertNotNull(bot, "listed for its owner");
		assertEquals(jobsBefore, RecordedJobs.of(venue, userDID), "status is a read: visiting Settings leaves no job record");
		assertNotNull(bot.state());
		assertEquals(List.of("123456789012345678", "@mike"), bot.allows(), "the allow-list, trimmed");

		// A second configure replaces the bot rather than duplicating it.
		Discord.configure(user, "Brightside", null, List.of("@mike"));
		assertEquals(List.of("@mike"),
			Discord.status(user, venue.resolve(venue.did(), Discord.recordPath(userDID))).allows());

		// Another user sees no bot of theirs and cannot remove the owner's.
		String otherDID = Identity.of("someone-else").userDID(venue.did());
		Venue other = venue.clientAs(otherDID);
		assertNull(Discord.status(other, null));
		assertThrows(Exception.class, () -> Discord.remove(other));

		Discord.remove(user);
		assertNull(Discord.status(user, null), "gone");
	}
}
