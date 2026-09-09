package brightside;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A stand-in for the {@code claude} CLI for {@link ClaudeCodeTest}: the
 * subset of the headless stream-json protocol the adapter relies on — an
 * {@code init} line, then for every user message on stdin an
 * {@code assistant} text block and a {@code result}; exit at stdin EOF.
 * Replies {@code echo: <prompt>}, or the working directory for the prompt
 * {@code cwd}, so a test can see where it ran.
 */
public final class FakeClaudeCli {

	private FakeClaudeCli() {
	}

	public static void main(String[] args) throws Exception {
		String sessionId = UUID.randomUUID().toString();
		String cwd = Path.of("").toAbsolutePath().toString();
		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		out.println("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":" + q(cwd) + ",\"session_id\":" + q(sessionId)
			+ ",\"tools\":[\"Read\"],\"model\":\"fake-model\",\"permissionMode\":\"default\"}");
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		int turns = 0;
		while ((line = in.readLine()) != null) {
			String prompt = content(line);
			if (prompt == null) continue;
			turns++;
			String reply = "cwd".equals(prompt.trim()) ? "cwd: " + cwd : "echo: " + prompt;
			out.println("{\"type\":\"assistant\",\"message\":{\"model\":\"fake-model\",\"id\":\"msg_1\",\"type\":\"message\","
				+ "\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + q(reply) + "}],\"stop_reason\":null},"
				+ "\"parent_tool_use_id\":null,\"session_id\":" + q(sessionId) + "}");
			out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":1,\"num_turns\":" + turns
				+ ",\"session_id\":" + q(sessionId) + ",\"total_cost_usd\":0.0,\"terminal_reason\":\"completed\","
				+ "\"permission_denials\":[],\"result\":" + q(reply) + "}");
		}
		System.exit(0);
	}

	/** The argv that runs this fake as the {@code claude} command. */
	static List<String> command() throws Exception {
		String exe = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
		Path classes = Path.of(FakeClaudeCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<String> cmd = new ArrayList<>();
		cmd.add(Path.of(System.getProperty("java.home"), "bin", exe).toString());
		cmd.add("-cp");
		cmd.add(classes.toString());
		cmd.add(FakeClaudeCli.class.getName());
		return cmd;
	}

	private static String q(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	/** The {@code "content":"…"} string of a user message line. */
	private static String content(String line) {
		int i = line.indexOf("\"content\":\"");
		if (i < 0) return null;
		i += "\"content\":\"".length();
		StringBuilder sb = new StringBuilder();
		while (i < line.length()) {
			char c = line.charAt(i++);
			if (c == '"') break;
			if (c == '\\' && i < line.length()) {
				char e = line.charAt(i++);
				switch (e) {
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> {
						sb.append((char) Integer.parseInt(line.substring(i, i + 4), 16));
						i += 4;
					}
					default -> sb.append(e);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
