package brightside.ui.parity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Nothing the UI shows is lost in the redesign: every user-facing string in
 * the UI's sources before the redesign began ({@code baseline-strings.txt},
 * taken from {@code develop} at {@code dfd6db1}) is still somewhere in the
 * UI's sources now. Moving a string to another class or screen passes; a
 * string that is gone fails, unless {@code reworded.txt} records it as
 * deliberately reworded and its new wording is present. A static check of the
 * sources, so it runs headless and fast; the reachability tests prove each
 * screen can still be reached.
 *
 * <p>Regenerate the baseline only on purpose, from a checkout of the
 * baseline commit: {@code UiParityTest.main(<src/main/java/brightside/ui>, <out>)}.
 * Strings are kept as the source writes them, escapes included ({@code \"}).
 */
class UiParityTest {

	private static final Path UI = Path.of("src/main/java/brightside/ui");
	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^\\s*//.*$");

	@Test
	void everyBaselineStringIsStillShown() throws IOException {
		Set<String> now = strings(UI);
		List<String> baseline = resourceLines("baseline-strings.txt");
		Map<String, String> reworded = reworded();
		assertFalse(baseline.isEmpty(), "the baseline is empty");
		List<String> missing = new ArrayList<>();
		for (String s : baseline) {
			if (now.contains(s)) continue;
			String instead = reworded.get(s);
			if (instead != null && now.contains(instead)) continue;
			missing.add(s + (instead != null ? "  (reworded to \"" + instead + "\", also missing)" : ""));
		}
		assertTrue(missing.isEmpty(), "UI strings no longer in the UI: " + String.join("\n  ", missing));
	}

	@Test
	void everyRewordingIsToSomethingNew() throws IOException {
		for (Map.Entry<String, String> e : reworded().entrySet()) {
			assertFalse(e.getKey().equals(e.getValue()), "reworded to itself: " + e.getKey());
		}
	}

	/** The user-facing string literals in the UI's sources: text with a letter and a space, or a capitalised word. */
	static Set<String> strings(Path root) throws IOException {
		Set<String> out = new TreeSet<>();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String code = Files.readString(f);
				code = BLOCK_COMMENT.matcher(code).replaceAll("");
				code = LINE_COMMENT.matcher(code).replaceAll("");
				Matcher m = LITERAL.matcher(code);
				while (m.find()) {
					String s = m.group(1);
					if (userFacing(s)) out.add(s);
				}
			}
		}
		return out;
	}

	static boolean userFacing(String s) {
		String t = s.strip();
		if (t.length() < 2) return false;
		if (!t.matches(".*\\p{L}.*")) return false;
		if (t.startsWith("<html") || t.contains("{") || t.startsWith("#")) return false;
		// Layout constraints ("span 3, gaptop 8, wmin 0"): lower-case keyword lists, not words anyone reads.
		if (t.matches("[a-z0-9 ,:%.\\[\\]-]+") && t.matches(".*\\b(insets|span|spany|gap|gapx|gapy|gaptop|gapright|gapleft|gapbottom|wmin|hmin|grow|growx|growy|fill|fillx|wrap|align|aligny|alignx|push|sg|dock|hidemode|w|h)\\b.*")) return false;
		// Keys, paths, ids and icon names: no spaces and not a capitalised word.
		if (!t.contains(" ") && t.matches(".*\\.\\p{L}.*")) return false;
		if (!t.contains(" ")) return Character.isUpperCase(t.charAt(0)) && t.matches("[\\p{L}][\\p{L}’'!?.,…:-]*");
		return true;
	}

	private static List<String> resourceLines(String name) throws IOException {
		try (InputStream in = UiParityTest.class.getResourceAsStream(name)) {
			if (in == null) return List.of();
			List<String> out = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
			}
			return out;
		}
	}

	/** {@code old<TAB>new}, one per line: strings changed on purpose, each with its new wording. */
	static Map<String, String> reworded() throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : resourceLines("reworded.txt")) {
			int tab = line.indexOf('\t');
			if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
		}
		return out;
	}

	public static void main(String[] args) throws IOException {
		Set<String> s = strings(Path.of(args[0]));
		Files.writeString(Path.of(args[1]), "# UI strings at the redesign's baseline (develop dfd6db1). Regenerate only on purpose.\n"
			+ String.join("\n", s) + "\n");
		System.out.println(s.size() + " strings");
	}
}
