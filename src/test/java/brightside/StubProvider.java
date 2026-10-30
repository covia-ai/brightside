package brightside;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpServer;

/**
 * A model provider on loopback that answers every request with one scripted
 * reply, in the provider's own wire format, and records what it was sent — so
 * a test can drive the venue's real model operation end to end (venue,
 * adapter, HTTP client, error mapping) with no network and no key, and check
 * the request that left: which key, in which header, how many calls.
 */
final class StubProvider implements AutoCloseable {

	/** What the stub received: method, path, headers (lower-case names), body. */
	record Request(String method, String path, Map<String, String> headers, String body) {
	}

	private final HttpServer server;
	private final List<Request> received = new ArrayList<>();
	private volatile int status;
	private volatile String body;

	private StubProvider(int status, String body) throws IOException {
		this.status = status;
		this.body = body;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			Map<String, String> headers = new TreeMap<>();
			exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
			String in;
			try (InputStream is = exchange.getRequestBody()) {
				in = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			}
			synchronized (received) {
				received.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, in));
			}
			byte[] out = this.body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(this.status, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();
	}

	/** A stub answering {@code status} with {@code body}. */
	static StubProvider replying(int status, String body) throws IOException {
		return new StubProvider(status, body);
	}

	/** Base URL in the shape the Anthropic client expects (trailing {@code /v1/}). */
	String anthropicUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
	}

	/** Base URL in the shape OpenAI-compatible clients expect ({@code /v1}). */
	String openAiUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	List<Request> received() {
		synchronized (received) {
			return List.copyOf(received);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}

	// Replies as the providers send them (shapes from their API references).

	static final String ANTHROPIC_OK = """
		{"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-5-5",
		 "content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","stop_sequence":null,
		 "usage":{"input_tokens":12,"output_tokens":2}}""";

	static String anthropicError(String type, String message) {
		return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
	}

	static final String OPENAI_OK = """
		{"id":"chatcmpl-1","object":"chat.completion","created":1761400000,"model":"gpt-5.6-terra",
		 "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],
		 "usage":{"prompt_tokens":12,"completion_tokens":1,"total_tokens":13}}""";

	static String openAiError(String type, String code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type + "\",\"param\":null,\"code\":"
			+ (code == null ? "null" : "\"" + code + "\"") + "}}";
	}
}
