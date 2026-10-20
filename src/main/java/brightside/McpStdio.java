package brightside;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import convex.core.data.AMap;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;

/**
 * A stdio connection to the already-running desktop venue. The MCP SDK owns
 * HTTP sessions, SSE, cancellation messages and transport shutdown; this class
 * preserves the client's messages and supplies the opt-in local credential.
 * It never opens the store, unlocks the app or takes over another instance.
 */
final class McpStdio {

	private static final String CONNECTION_FAILED =
		"Brightside MCP connection failed. Keep Brightside running and unlocked, enable "
		+ "'Keep a token for local tools' in Settings > Auth, then reconnect the client.";
	private static final TypeRef<Map<String, String>> HEADERS = new TypeRef<>() {};
	private final McpJsonMapper json = McpJsonDefaults.getMapper();
	private final CompletableFuture<Void> finished = new CompletableFuture<>();
	private final HttpClientStreamableHttpTransport transport;
	private final PrintStream output;
	private volatile Object initializeId;
	private volatile String protocolVersion;

	private McpStdio(int port, Path tokenFile, PrintStream output) {
		this.output = output;
		transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
			.endpoint("/mcp")
			.connectTimeout(Duration.ofSeconds(3))
			.httpRequestCustomizer((builder, method, endpoint, body, context) ->
				builder.header("Authorization", authorization(tokenFile)))
			.build();
		transport.setExceptionHandler(error -> failed());
	}

	/** Read on every request, so rotation and disabling local access take effect. */
	private String authorization(Path tokenFile) {
		try {
			String header = json.readValue(Files.readString(tokenFile), HEADERS).get("Authorization");
			if (header != null && header.startsWith("Bearer ") && header.length() > 7
					&& header.indexOf('\r') < 0 && header.indexOf('\n') < 0) return header;
		} catch (Exception ignored) {
			// Never include a credential, parser excerpt or server body in diagnostics.
		}
		throw new IllegalStateException(CONNECTION_FAILED);
	}

	private void failed() {
		finished.completeExceptionally(new IOException(CONNECTION_FAILED));
	}

	private synchronized void receive(JSONRPCMessage message) {
		try {
			if (message instanceof JSONRPCResponse response && response.id().equals(initializeId)
					&& response.result() instanceof Map<?, ?> result
					&& result.get("protocolVersion") instanceof String version) {
				protocolVersion = version;
			}
			write(json.writeValueAsString(message));
		} catch (IOException e) {
			failed();
		}
	}

	private synchronized void write(String message) {
		// PrintStream's encoding is inherited from the caller; write UTF-8 explicitly.
		output.writeBytes((message + "\n").getBytes(StandardCharsets.UTF_8));
		output.flush();
		if (output.checkError()) failed();
	}

	private void read(InputStream input) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
			String line;
			while (!finished.isDone() && (line = reader.readLine()) != null) {
				JSONRPCMessage message;
				try {
					message = McpSchema.deserializeJsonRpcMessage(json, line);
				} catch (Exception e) {
					write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Invalid JSON-RPC message\"}}");
					continue;
				}
				if (message instanceof JSONRPCRequest request && request.method().equals("initialize")) {
					initializeId = request.id();
				}
				var send = transport.sendMessage(message);
				String version = protocolVersion;
				if (version != null) {
					send = send.contextWrite(context -> context.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, version));
				}
				// Keep reading while a tool is running: cancellation and concurrent
				// requests must reach the venue without waiting for that tool.
				send.subscribe(unused -> {}, error -> failed());
			}
			finished.complete(null);
		} catch (Exception e) {
			failed();
		}
	}

	private void serve(InputStream input) throws InterruptedException, ExecutionException {
		transport.connect(messages -> messages.doOnNext(this::receive)).block();
		// A transport failure must also end the process when stdin is still open.
		Thread reader = Thread.ofVirtual().name("brightside-mcp-stdin").start(() -> read(input));
		try {
			finished.get();
		} finally {
			reader.interrupt();
			try {
				var close = transport.closeGracefully();
				if (protocolVersion != null) {
					close = close.contextWrite(context -> context.put(
						McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, protocolVersion));
				}
				close.block(Duration.ofSeconds(2));
			} catch (Exception ignored) {
				// The app may already have stopped, or removed the local credential.
			}
		}
	}

	static int run(String[] args, InputStream input, PrintStream output, PrintStream errors) {
		try {
			Path config = AppConfig.DEFAULT_FILE;
			boolean hasConfig = false;
			for (String arg : args) {
				if (arg.equals("--mcp-stdio")) continue;
				if (arg.startsWith("--") || hasConfig) {
					errors.println("Usage: java -jar brightside.jar --mcp-stdio [path/to/config.json]");
					return 64;
				}
				config = Path.of(arg).toAbsolutePath().normalize();
				hasConfig = true;
			}
			// Read only the endpoint setting: AppConfig.load would create files and
			// directories, which a connection to an existing app must not do.
			var raw = JSON.parseJSON5(Files.readString(config));
			if (!(raw instanceof AMap<?, ?>)) throw new IOException("Invalid configuration");
			var configuredPort = RT.getIn(raw, "venue", "port");
			long port = configuredPort == null ? AppConfig.DEFAULT_PORT
				: configuredPort instanceof CVMLong value ? value.longValue() : -1;
			if (port < 1 || port > 65535) throw new IOException("Invalid port");
			Path token = ToolToken.file(config.toAbsolutePath().getParent());
			McpStdio bridge = new McpStdio((int) port, token, output);
			bridge.authorization(token); // Fail promptly even before initialize arrives.
			bridge.serve(input);
			return 0;
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			errors.println(CONNECTION_FAILED + " Check the configuration path and venue port.");
			return 1;
		}
	}
}
