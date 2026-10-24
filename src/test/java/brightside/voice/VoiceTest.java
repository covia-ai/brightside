package brightside.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import brightside.AppConfig;
import brightside.EmbeddedVenue;
import brightside.Identity;
import brightside.SharedVenue;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * Voice typing goes through Covia's speech adapter on the embedded venue, as
 * the owner. Brightside's own choice of engine transcribes on this computer and
 * fetches nothing until the owner agrees; a recording travels inline and leaves
 * no job record, and what was said comes back as plain text. A loopback
 * stand-in for an OpenAI-compatible provider plays the engine, so no model is
 * ever loaded here.
 */
class VoiceTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final AString SPEECH = Strings.create("speech");
	private static final AString LOCAL = Strings.create("local");

	private static EmbeddedVenue venue;
	private static String userDID;
	private static Voice voice;
	private static HttpServer provider;
	private static final BlockingQueue<byte[]> heard = new LinkedBlockingQueue<>();
	private static volatile String reply;
	private static final BlockingQueue<String> asked = new LinkedBlockingQueue<>();
	private static final byte[] SPOKEN = "RIFF-a-spoken-reply".getBytes(StandardCharsets.US_ASCII);

	@BeforeAll
	static void boot() throws IOException {
		venue = shared.venue();
		userDID = Identity.of("voice-owner").userDID(venue.did());
		voice = new Voice(venue.clientAs(userDID));
		provider = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		provider.createContext("/v1/audio/transcriptions", exchange -> {
			heard.add(exchange.getRequestBody().readAllBytes());
			byte[] body = reply.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		provider.createContext("/v1/audio/speech", exchange -> {
			asked.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.getResponseHeaders().add("Content-Type", "audio/wav");
			exchange.sendResponseHeaders(200, SPOKEN.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(SPOKEN);
			}
		});
		provider.start();
	}

	@AfterAll
	static void stop() {
		provider.stop(0);
	}

	@Test
	void brightsidesOwnEngineWaitsForTheOwnerBeforeFetchingAnything(@TempDir Path home) throws Exception {
		// Brightside's choice exactly, with the cache somewhere this test owns.
		AMap<AString, ACell> speech = RT.ensureMap(RT.getIn(AppConfig.defaultVenue(home), "adapters", "speech"));
		Path cache = home.resolve("speech-cache");
		AMap<AString, ACell> local = RT.ensureMap(speech.get(LOCAL));
		local = local.assoc(Strings.create("cacheDir"), Strings.create(cache.toString()));
		venue.engine().configureAdapter(SPEECH.toString(), speech.assoc(LOCAL, local));

		Voice.Engine engine = voice.engine().get();
		assertTrue(engine.local(), "what the owner says stays on this computer");
		assertTrue(engine.needsDownload(), "the model is not here, and nothing is fetching it");
		assertTrue(engine.bytes() > 0, "its size is known, to ask the owner about");
		assertFalse(engine.needsKey());

		Voice.Speaking speaking = voice.speaking().get();
		assertTrue(speaking.local(), "replies are spoken on this computer too");
		assertTrue(speaking.needsDownload(), "the voice is not here, and nothing is fetching it");
		assertTrue(speaking.bytes() > 0, "its size is known, to ask the owner about");
		assertFalse(Files.exists(cache), "asking fetched nothing");
	}

	@Test
	void aRecordingIsTranscribedInlineAndLeavesNoRecord() throws Exception {
		AMap<AString, ACell> loopback = Maps.of("engine", "openai",
			"baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
		venue.engine().configureAdapter(SPEECH.toString(), loopback);
		heard.clear();
		reply = "{\"text\":\" Remind me to call Sam at four. \"}";
		byte[] pcm = new byte[16_000];
		long jobs = jobs();

		assertEquals("Remind me to call Sam at four.", voice.transcribe(new Microphone.Clip(pcm, 16_000)).get());
		assertEquals(jobs, jobs(), "a read: no job record holds the recording or what was said");
		byte[] sent = heard.take();
		int riff = new String(sent, StandardCharsets.ISO_8859_1).indexOf("RIFF");
		assertTrue(riff > 0, "the recording reached the engine as WAV");
		assertEquals(16_000, ByteBuffer.wrap(sent, riff + 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(),
			"at the rate it was recorded at");
		assertEquals(36 + pcm.length, ByteBuffer.wrap(sent, riff + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(),
			"all of it");

		reply = "{\"text\":\"  \"}";
		assertEquals("", voice.transcribe(new Microphone.Clip(pcm, 48_000)).get(), "silence is no words");

		Voice.Engine engine = voice.engine().get();
		assertFalse(engine.needsDownload());
		assertTrue(engine.local() && !engine.needsKey(), "a server on this computer needs no key");
	}

	@Test
	void aReplyIsSpokenInlineAndLeavesNoRecord() throws Exception {
		venue.engine().configureAdapter(SPEECH.toString(), Maps.of("engine", "openai",
			"baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1"));
		asked.clear();
		long jobs = jobs();

		assertArrayEquals(SPOKEN, voice.say("Your train leaves at four.").get(), "the audio itself, inline");
		assertEquals(jobs, jobs(), "a read: no job record holds the words or the audio");
		String request = asked.take();
		assertTrue(request.contains("Your train leaves at four."), request);
		assertTrue(request.contains("\"wav\""), "asked for WAV, which the computer plays: " + request);
		assertFalse(voice.speaking().get().needsDownload(), "a provider needs nothing fetched");
	}

	private static long jobs() {
		Index<Blob, ACell> jobs = venue.engine().jobs().getJobs(RequestContext.of(Strings.create(userDID)));
		return (jobs != null) ? jobs.count() : 0;
	}
}
