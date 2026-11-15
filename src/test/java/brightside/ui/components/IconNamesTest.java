package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every {@link Lucide} icon the UI names is in the bundle. A name that is not
 * paints FlatSVGIcon's red placeholder, and nothing else notices, so this reads
 * the names out of the UI's sources: each literal passed where a method,
 * constructor or enum constant takes a {@code String icon} or {@code String
 * glyph}, and each literal a {@code String icon(…)} method returns.
 */
class IconNamesTest {

	private static final Path UI = Path.of("src/main/java/brightside/ui");
	/** A parameter that carries an icon name, as a declaration writes it. */
	private static final Pattern DECLARATION = Pattern.compile(
		"\\b(\\w+)\\s*\\(([^()]*\\bString\\s+(?:icon|glyph)\\b[^()]*)\\)\\s*(?:throws[^{]*)?\\{");
	private static final Pattern ICON_METHOD = Pattern.compile("\\bString\\s+icon\\s*\\([^)]*\\)\\s*\\{");
	private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
	private static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
	private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new");

	@Test
	void everyIconTheUiNamesResolves() throws IOException {
		Map<Path, String> sources = sources();
		Map<String, Set<Integer>> takers = takers(sources);
		Set<String> names = new TreeSet<>();
		for (String code : sources.values()) {
			collectCalls(code, takers, names);
			collectReturned(code, names);
		}
		assertFalse(names.isEmpty(), "found no icon names: the scan is broken");
		List<String> missing = new ArrayList<>();
		for (String name : names) {
			if (!Lucide.icon(name, 16, Color.BLACK).hasFound()) missing.add(name);
		}
		assertTrue(missing.isEmpty(), "icons named but not in icons/lucide: " + missing);
	}

	/** The UI's sources with comments removed, so prose in them is not read as code. */
	private static Map<Path, String> sources() throws IOException {
		Map<Path, String> sources = new HashMap<>();
		try (Stream<Path> files = Files.walk(UI)) {
			for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				sources.put(f, stripComments(Files.readString(f)));
			}
		}
		return sources;
	}

	/**
	 * Each method, constructor or enum constructor taking an icon name, by
	 * name, with the argument positions that carry one. An enum's constants
	 * call its constructor under their own names, so they are added too.
	 */
	private static Map<String, Set<Integer>> takers(Map<Path, String> sources) {
		Map<String, Set<Integer>> takers = new HashMap<>();
		for (String code : sources.values()) {
			Matcher m = DECLARATION.matcher(code);
			while (m.find()) {
				String name = m.group(1);
				if (KEYWORDS.contains(name)) continue;
				List<String> params = splitArgs(m.group(2), true);
				for (int i = 0; i < params.size(); i++) {
					if (params.get(i).matches("(?s).*\\bString\\s+(icon|glyph)\\s*")) {
						takers.computeIfAbsent(name, k -> new TreeSet<>()).add(i);
						if (Pattern.compile("\\benum\\s+" + name + "\\b").matcher(code).find()) {
							Matcher constant = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*\\(").matcher(code);
							while (constant.find()) takers.computeIfAbsent(constant.group(1), k -> new TreeSet<>()).add(i);
						}
					}
				}
			}
		}
		return takers;
	}

	/** The names in the icon-carrying arguments of every call to a taker. */
	private static void collectCalls(String code, Map<String, Set<Integer>> takers, Set<String> names) {
		Matcher m = CALL.matcher(code);
		while (m.find()) {
			Set<Integer> positions = takers.get(m.group(1));
			if (positions == null) continue;
			int close = closing(code, m.end() - 1);
			if (close < 0) continue;
			List<String> args = splitArgs(code.substring(m.end(), close), false);
			for (int i : positions) {
				if (i < args.size()) literals(args.get(i), names);
			}
		}
	}

	/** The names a {@code String icon(…)} method returns. */
	private static void collectReturned(String code, Set<String> names) {
		Matcher m = ICON_METHOD.matcher(code);
		while (m.find()) {
			int close = closing(code, m.end() - 1);
			if (close > 0) literals(code.substring(m.end(), close), names);
		}
	}

	private static void literals(String text, Set<String> names) {
		Matcher m = LITERAL.matcher(text);
		while (m.find()) {
			if (NAME.matcher(m.group(1)).matches()) names.add(m.group(1));
		}
	}

	/** The index of the bracket closing the one at {@code open}, skipping literals; -1 if none. */
	private static int closing(String code, int open) {
		char o = code.charAt(open);
		char c = (o == '(') ? ')' : '}';
		int depth = 0;
		for (int i = open; i < code.length(); i++) {
			char ch = code.charAt(i);
			if (ch == '"' || ch == '\'') {
				i = endOfLiteral(code, i);
			} else if (ch == o) {
				depth++;
			} else if (ch == c && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Top-level comma-separated pieces of an argument or parameter list; angle
	 * brackets nest only in a parameter list, where they are type arguments.
	 */
	private static List<String> splitArgs(String list, boolean params) {
		List<String> args = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < list.length(); i++) {
			char ch = list.charAt(i);
			if (ch == '"' || ch == '\'') {
				i = endOfLiteral(list, i);
			} else if (ch == '(' || ch == '{' || ch == '[' || (params && ch == '<')) {
				depth++;
			} else if (ch == ')' || ch == '}' || ch == ']' || (params && ch == '>')) {
				depth--;
			} else if (ch == ',' && depth == 0) {
				args.add(list.substring(start, i));
				start = i + 1;
			}
		}
		if (!list.isBlank()) args.add(list.substring(start));
		return args;
	}

	/** The index of the quote closing the literal opening at {@code i}. */
	private static int endOfLiteral(String s, int i) {
		char q = s.charAt(i);
		if (q == '"' && s.startsWith("\"\"\"", i)) {
			int end = s.indexOf("\"\"\"", i + 3);
			return (end < 0) ? s.length() : end + 2;
		}
		for (int j = i + 1; j < s.length(); j++) {
			char ch = s.charAt(j);
			if (ch == '\\') j++;
			else if (ch == q) return j;
		}
		return s.length();
	}

	private static String stripComments(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '"' || ch == '\'') {
				int end = endOfLiteral(s, i);
				out.append(s, i, Math.min(end + 1, s.length()));
				i = end;
			} else if (s.startsWith("//", i)) {
				int nl = s.indexOf('\n', i);
				i = (nl < 0) ? s.length() : nl - 1;
			} else if (s.startsWith("/*", i)) {
				int end = s.indexOf("*/", i + 2);
				i = (end < 0) ? s.length() : end + 1;
			} else {
				out.append(ch);
			}
		}
		return out.toString();
	}
}
