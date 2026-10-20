package brightside;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.sun.net.httpserver.HttpServer;

import convex.auth.jwt.JWT;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/** Real subprocesses ensure library startup cannot contaminate the MCP stdout. */
@Execution(ExecutionMode.CONCURRENT)
class McpStdioTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@TempDir
	Path home;

	@Test
	void stdioReachesTheExistingVenueAsItsUser() throws Exception {
		EmbeddedVenue venue = shared.venue();
		String user = Identity.of("stdio-test").userDID(venue.did());
		long now = System.currentTimeMillis() / 1000;
		String token = JWT.signPublic(Maps.of("sub", user, "iss", venue.did(), "aud", venue.did(),
			"iat", now, "exp", now + 300), venue.engine().getKeyPair()).toString();
		config(venue.port());
		ToolToken.write(home, token);
		try (Client client = new Client()) {
			client.send(request(1, "initialize", initialise()));
			ACell init = client.read();
			assertEquals(CVMLong.ONE, RT.getIn(init, "id"));
			assertNotNull(RT.getIn(init, "result", "protocolVersion"));
			client.send(Maps.of("jsonrpc", "2.0", "method", "notifications/initialized"));
			client.send(request(2, "tools/list", Maps.empty()));
			assertTrue(RT.count(RT.getIn(client.read(), "result", "tools")) > 0);
			client.send(request(3, "tools/call", Maps.of("name", "grid_run",
				"arguments", Maps.of("operation", "v/ops/auth/whoami", "input", Maps.empty()))));
			ACell reply = client.read();
			assertNull(RT.getIn(reply, "error"));
			assertTrue(JSON.print(reply).toString().contains(user));
			assertEquals(0, client.finish());
		}
	}

	@Test
	void streamsAndCancellationKeepFlowingAndEveryRequestReadsTheToken() throws Exception {
		var cancelled = new CompletableFuture<Void>();
		var deleted = new CompletableFuture<Void>();
		var failure = new CompletableFuture<Throwable>();
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(executor);
		server.createContext("/mcp", exchange -> {
			try (exchange) {
				String method = exchange.getRequestMethod();
				if (method.equals("GET")) {
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				if (method.equals("DELETE")) {
					assertEquals("session-test", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
					exchange.sendResponseHeaders(200, -1);
					deleted.complete(null);
					return;
				}
				ACell message = JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				String rpc = RT.getIn(message, "method").toString();
				if (rpc.equals("initialize")) {
					assertEquals("Bearer first", exchange.getRequestHeaders().getFirst("Authorization"));
					exchange.getResponseHeaders().set("Mcp-Session-Id", "session-test");
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					byte[] body = JSON.print(Maps.of("jsonrpc", "2.0", "id", 1, "result",
						Maps.of("protocolVersion", "2025-06-18", "capabilities", Maps.empty(),
							"serverInfo", Maps.of("name", "test", "version", "1")))).toString().getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
					return;
				}
				assertEquals("Bearer second", exchange.getRequestHeaders().getFirst("Authorization"));
				assertEquals("session-test", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
				assertEquals("2025-06-18", exchange.getRequestHeaders().getFirst("Mcp-Protocol-Version"));
				if (rpc.equals("notifications/cancelled")) {
					assertEquals(CVMLong.create(2), RT.getIn(message, "params", "requestId"));
					exchange.sendResponseHeaders(202, -1);
					cancelled.complete(null);
					return;
				}
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, 0);
				Writer writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8);
				writer.write(": keepalive\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
					+ "\"params\":{\"progressToken\":\"work\",\"progress\":1}}\n\n");
				writer.flush();
				cancelled.get(5, TimeUnit.SECONDS);
				writer.write("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\n"
					+ "data: \"error\":{\"code\":-32800,\"message\":\"Cancelled\"}}\n\n");
				writer.flush();
			} catch (Throwable e) {
				failure.complete(e);
				cancelled.completeExceptionally(e);
			}
		});
		server.start();
		try {
			config(server.getAddress().getPort());
			ToolToken.write(home, "first");
			try (Client client = new Client()) {
				client.send(request(1, "initialize", initialise()));
				assertNotNull(RT.getIn(client.read(), "result"));
				ToolToken.write(home, "second");
				client.send(request(2, "tools/call", Maps.of("name", "slow", "arguments", Maps.empty(),
					"_meta", Maps.of("progressToken", "work"))));
				assertEquals("notifications/progress", RT.getIn(client.read(), "method").toString());
				client.send(Maps.of("jsonrpc", "2.0", "method", "notifications/cancelled",
					"params", Maps.of("requestId", 2)));
				assertEquals(CVMLong.create(-32800), RT.getIn(client.read(), "error", "code"));
				assertEquals(0, client.finish());
				deleted.get(5, TimeUnit.SECONDS);
				assertFalse(failure.isDone(), () -> String.valueOf(failure.join()));
			}
		} finally {
			server.stop(0);
			executor.shutdownNow();
		}
	}

	@Test
	void rejectedAuthenticationEndsTheProcessEvenWithStdinOpen() throws Exception {
		config(shared.venue().port());
		ToolToken.write(home, "not-a-valid-token");
		try (Client client = new Client()) {
			client.send(request(1, "initialize", initialise()));
			assertEquals(1, client.exit());
			assertNull(client.reader.readLine());
			assertFalse(Files.readString(client.errors).contains("not-a-valid-token"));
		}
	}

	@Test
	void missingLocalConsentFailsWithoutStartingTheAppOrCreatingFiles() throws Exception {
		config(shared.venue().port());
		try (Client client = new Client()) {
			assertEquals(1, client.exit());
			assertNull(client.reader.readLine());
			assertFalse(Files.exists(home.resolve("venue.etch")));
			assertFalse(Files.exists(home.resolve("files")));
		}
	}

	private void config(int port) throws Exception {
		Files.writeString(home.resolve("config.json"), "{venue:{port:" + port + "}}");
	}

	private static AMap<?, ?> request(int id, String method, ACell params) {
		return Maps.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
	}

	private static AMap<?, ?> initialise() {
		return Maps.of("protocolVersion", "2025-11-25", "capabilities", Maps.empty(),
			"clientInfo", Maps.of("name", "brightside-stdio-test", "version", "1"));
	}

	private final class Client implements AutoCloseable {
		final Path errors = home.resolve("stderr.log");
		final Process process;
		final Writer writer;
		final BufferedReader reader;

		Client() throws Exception {
			String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
			List<String> command = new ArrayList<>(List.of(java, "-Djava.awt.headless=true"));
			String jar = System.getProperty("brightside.tests.stdioJar");
			if (jar != null) {
				command.addAll(List.of("-jar", jar));
			} else {
				command.addAll(List.of("-cp",
					System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
					"brightside.Main"));
			}
			command.addAll(List.of("--mcp-stdio", home.resolve("config.json").toString()));
			process = new ProcessBuilder(command)
				.redirectError(errors.toFile()).start();
			writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
			reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		}

		void send(ACell message) throws Exception {
			writer.write(JSON.print(message).toString() + "\n");
			writer.flush();
		}

		ACell read() throws Exception {
			String line = CompletableFuture.supplyAsync(() -> {
				try {
					return reader.readLine();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}).get(5, TimeUnit.SECONDS);
			assertNotNull(line, () -> {
				try {
					return Files.readString(errors);
				} catch (Exception e) {
					return e.toString();
				}
			});
			return JSON.parse(line);
		}

		int finish() throws Exception {
			writer.close();
			return exit();
		}

		int exit() throws Exception {
			return process.onExit().get(5, TimeUnit.SECONDS).exitValue();
		}

		@Override
		public void close() throws Exception {
			process.destroyForcibly();
			process.onExit().get(5, TimeUnit.SECONDS);
		}
	}
}
